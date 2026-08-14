package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekFimApi
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekFimApiImpl
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekResponsesApiImpl
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekStandardApiImpl
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.toolcall.DEEPSEEK_WEB_SEARCH_TOOL
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.executor.ToolResult
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Deepseek 客户端共享的内部核心：网络后端、取消机制与流式对话循环。
 *
 * [Deepseek] 与 [StatelessDeepseek] 通过组合复用此核心，避免继承带来的
 * 语义污染（如无状态客户端暴露历史操作）。
 */
@OptIn(ExperimentalDeepseekApi::class)
internal class DeepseekCore(
    val apiKey: String,
    val model: Model?,
    val prompt: String?,
    val config: ChatConfig,
    val api: DeepseekApi,
    val sharingPool: DeepseekHttpClientPool = DeepseekHttpClientPool.Global,
    private val singleSession: Boolean = false,
    backend: DeepseekApiBackend? = null,
    fimApi: DeepseekFimApi? = null,
) {

    /** 按 [api] 选择对应的 wire format 后端实现 */
    val backend: DeepseekApiBackend = backend ?: when (api) {
        DeepseekApi.STANDARD -> DeepseekStandardApiImpl(apiKey, sharingPool)
        DeepseekApi.RESPONSES -> DeepseekResponsesApiImpl(apiKey, sharingPool)
    }

    /** FIM 补全 API 的网络后端 */
    val fimApi: DeepseekFimApi = fimApi ?: DeepseekFimApiImpl(apiKey, sharingPool)

    /** 工具调用宿主，设置后 [streamLoop] 自动执行模型请求的工具 */
    var toolHost: ToolCallHost? = null

    /** 工具执行上下文（用户/会话/权限等元信息），供每次工具调用时使用 */
    var executionContext: ToolExecutionContext = ToolExecutionContext("", "")

    /** 系统提示词对应的初始 system 消息；未设置 prompt 时为 `null` */
    val systemPromptMessage: Message? = prompt?.let { Message(Role.System, it) }

    /** 活跃流会话快照；写入时通过 [sessionLock] 保护 */
    @Volatile
    private var sessions: Set<StreamSession> = emptySet()

    private val sessionLock = Mutex()

    /** 当前使用的模型；未显式指定时固定使用库内硬编码的 [Model.Flash]，不会发起网络请求 */
    val resolvedModel: Model get() = model ?: Model.Flash

    /** FIM 补全使用的模型，默认 [Model.Pro] */
    var modelForFim: Model = Model.Pro

    /**
     * 取消当前实例上的活跃流。
     *
     * 有状态模式（[singleSession] 为 `true`）下最多只有一个活跃流；无状态模式下会
     * 取消全部活跃流。除置位会话取消标志外，还会取消收集协程，级联中止底层 HTTP 请求。
     */
    fun cancelStream() {
        sessions.forEach { it.cancel() }
    }

    /** 注册新会话；有状态模式下在同一把锁内取消旧会话，保证只有一个活跃流 */
    private suspend fun register(session: StreamSession) {
        sessionLock.withLock {
            if (singleSession) {
                sessions.forEach { it.cancel() }
                sessions = setOf(session)
            } else {
                sessions = sessions + session
            }
        }
    }

    private suspend fun unregister(session: StreamSession) {
        sessionLock.withLock {
            sessions = sessions - session
        }
    }

    /** 包装流式事件的公共骨架：注册会话并绑定当前收集协程 */
    fun <C : Chunk> streamFlow(body: suspend FlowCollector<C>.(StreamSession) -> Unit): Flow<C> = flow {
        val session = StreamSession()
        register(session)
        session.attach(currentCoroutineContext()[Job])
        try {
            body(session)
        } finally {
            unregister(session)
        }
    }

    /**
     * 发起 FIM 流：把后端 [FimChunk] 事件转发给 [hook] 与调用方 Flow。
     */
    internal fun fimFlow(
        prompt: String,
        suffix: String?,
        echo: Boolean?,
        hook: SseHook?,
    ): Flow<FimChunk> = streamFlow { session ->
        fimApi.fim(prompt, suffix, echo, modelForFim, config).collect { chunk ->
            if (session.cancelled) return@collect
            hook?.onChunk(chunk)
            emit(chunk)
        }
    }

    /**
     * 执行一轮 tool calls 并把 assistant/tool 消息追加到历史。
     */
    internal suspend fun handleToolCalls(
        pendingToolCalls: List<ChatChunk.ToolCallRequest>,
        history: MutableList<Message>,
    ): List<ToolExecResult> {
        if (pendingToolCalls.isEmpty()) return emptyList()
        val host = toolHost
        // 客户端工具调用需要 host；纯 web_search 调用由服务端执行，仅需保留历史
        if (host == null && pendingToolCalls.any { it.call.name != DEEPSEEK_WEB_SEARCH_TOOL }) {
            return emptyList()
        }

        history.add(
            Message(
                role = Role.Assistance,
                content = null,
                toolCalls = pendingToolCalls.map { it.call },
            )
        )

        val results = mutableListOf<ToolExecResult>()
        for (tc in pendingToolCalls) {
            val call = tc.call
            val result = if (call.name == DEEPSEEK_WEB_SEARCH_TOOL) {
                if (host != null) {
                    host.execute(call, executionContext)
                } else {
                    ToolResult.success(call.id, "{\"status\":\"completed\"}")
                }
            } else {
                host!!.execute(call, executionContext)
            }
            if (call.name == DEEPSEEK_WEB_SEARCH_TOOL) {
                // 服务端已执行搜索；魔法名消息在输入转换时被跳过，不要求配对
                history.add(
                    Message(
                        role = Role.Tool,
                        content = result.content,
                        name = DEEPSEEK_WEB_SEARCH_TOOL,
                    )
                )
            } else {
                history.add(
                    Message(
                        role = Role.Tool,
                        content = result.content,
                        toolCallId = call.id,
                    )
                )
            }
            results.add(ToolExecResult(call.id, call.name, result.content, result.isError))
        }
        return results
    }

    /** 获取当前 API Key 可用的模型列表 */
    suspend fun availableModels(): List<Model> = backend.models()

    /** 获取当前 API Key 的账户余额信息 */
    suspend fun balance(): UserBalance = backend.userBalance()
}

/**
 * 流式对话循环：追加 user 消息、执行补全请求与工具调用循环、累计 usage，
 * 结束时发射一次累计 [ChatChunk.Done] 并把 assistant 回复写入历史。
 *
 * 失败或取消时把 [history] 回滚到本次调用前的状态，避免残留消息污染下轮请求。
 * [hook] 只收到外层 Flow 可见的事件（含最终累计 Done）。
 */
internal suspend fun FlowCollector<ChatChunk>.streamLoop(
    core: DeepseekCore,
    history: MutableList<Message>,
    userContent: String?,
    hook: SseHook?,
    session: StreamSession,
) {
    val historyStart = history.size
    if (userContent != null) history.add(Message(Role.User, content = userContent))
    val contentBuilder = StringBuilder()
    var iterations = 0

    var totalPromptTokens = 0L
    var totalCompletionTokens = 0L
    var totalTokens = 0L
    var finalFinishReason: String? = null
    var committed = false

    try {
        // 工具定义在一次流式对话中不会变化，只取一次
        val tools = core.toolHost?.getDefinitions()?.ifEmpty { null }

        while (iterations < core.config.maxToolIterations) {
            // ★ 检查点 1: 每次 tool-call 循环迭代前
            if (session.cancelled) return
            iterations++
            val pendingToolCalls = mutableListOf<ChatChunk.ToolCallRequest>()
            var hasToolCallInResponse = false

            core.backend.completions(
                messages = history.toList(),
                model = core.resolvedModel,
                config = core.config,
                tools = tools,
            ).collect { chunk ->
                // ★ 检查点 2: 每个 SSE chunk 到达时
                if (session.cancelled) return@collect
                when (chunk) {
                    is ChatChunk.ContentDelta -> {
                        if (chunk.content.isNotEmpty()) contentBuilder.append(chunk.content)
                        hook?.onChunk(chunk)
                        emit(chunk)
                    }
                    is ChatChunk.ToolCallRequest -> {
                        hasToolCallInResponse = true
                        pendingToolCalls.add(chunk)
                        hook?.onChunk(chunk)
                        emit(chunk)
                    }
                    is ChatChunk.ToolResultData -> {
                        hook?.onChunk(chunk)
                        emit(chunk)
                    }
                    is ChatChunk.Done -> {
                        // 工具循环中各轮请求的 Done 不对外发射，仅累计用量
                        totalPromptTokens += chunk.promptTokens
                        totalCompletionTokens += chunk.completionTokens
                        totalTokens += chunk.totalTokens
                        if (chunk.finishReason != null) finalFinishReason = chunk.finishReason
                    }
                }
            }

            // After collect: check if cancelled and exit
            if (session.cancelled) return

            if (!hasToolCallInResponse) break // No tool calls → stream is truly done

            // ★ 检查点 3: 工具调用执行前
            if (session.cancelled) return
            val toolResults = core.handleToolCalls(pendingToolCalls, history)
            for (tr in toolResults) {
                val data = ChatChunk.ToolResultData(tr.toolCallId, tr.functionName, tr.content, tr.isError)
                hook?.onChunk(data)
                emit(data)
            }
            if (toolResults.isEmpty()) break // Tool execution failed → stop

            // web_search 由服务端在同一流内完成作答，不再进入下一轮循环
            if (pendingToolCalls.all { it.call.name == DEEPSEEK_WEB_SEARCH_TOOL }) break
        }

        // Distinguish "hit iteration limit on tool_calls" from a natural stop
        if (iterations >= core.config.maxToolIterations && finalFinishReason == "tool_calls") {
            finalFinishReason = "max_tool_iterations"
        }

        val done = ChatChunk.Done(
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            totalTokens = totalTokens,
            finishReason = finalFinishReason,
        )
        hook?.onChunk(done)
        emit(done)

        if (contentBuilder.isNotEmpty()) {
            history.add(Message(Role.Assistance, content = contentBuilder.toString()))
        }
        committed = true
    } finally {
        if (!committed) {
            while (history.size > historyStart) {
                history.removeAt(history.lastIndex)
            }
        }
    }
}

/** 单次工具执行的内部结果记录 */
internal data class ToolExecResult(
    val toolCallId: String,
    val functionName: String,
    val content: String,
    val isError: Boolean,
)

/**
 * 单个流式调用的取消会话。
 *
 * 持有取消标志与收集协程 [Job]；[cancel] 可安全地在协程启动前后调用。
 */
internal class StreamSession {
    /** 是否已被 [cancel] 置位 */
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    private var job: Job? = null

    /** 绑定当前收集协程；若已取消则立即取消该协程 */
    fun attach(job: Job?) {
        this.job = job
        if (cancelled) job?.cancel()
    }

    /** 置位取消标志并取消绑定协程 */
    fun cancel() {
        cancelled = true
        job?.cancel()
    }
}
