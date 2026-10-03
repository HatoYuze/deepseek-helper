package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.impl.DEFAULT_BASE_URL
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekFilesApiImpl
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekFimApi
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekFimApiImpl
import io.github.hatoyuze.deepseek.protocol.api.impl.requireImagesAllowed
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekResponsesApiImpl
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekStandardApiImpl
import io.github.hatoyuze.deepseek.protocol.api.entity.InlineToolCallPolicy
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.toolcall.DEEPSEEK_WEB_SEARCH_TOOL
import io.github.hatoyuze.deepseek.toolcall.Logger
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.executor.ToolResult
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

/** 请求侧不变量告警的日志出口：内容侧另有 `InlineToolCallRecovery` 自己的 logger。 */
private val coreLogger = Logger("DeepseekCore")

/**
 * 去重用的参数视图：解析成 [JsonElement] 后比较是**结构性**的（键序、空白无关），
 * 解析不了就退化成原始字符串比较（与结构化通道的 wire 文本逐字一致时才判等）。
 */
private fun parsedArgumentsOf(arguments: String): JsonElement =
    runCatching { Json.parseToJsonElement(arguments) }.getOrDefault(JsonPrimitive(arguments))

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
    val baseUrl: String = DEFAULT_BASE_URL,
    val sharingPool: DeepseekHttpClientPool = DeepseekHttpClientPool.Global,
    private val singleSession: Boolean = false,
    backend: DeepseekApiBackend? = null,
    fimApi: DeepseekFimApi? = null,
    filesApi: DeepseekFiles? = null,
) {

    /** 按 [api] 选择对应的 wire format 后端实现 */
    val backend: DeepseekApiBackend = backend ?: when (api) {
        DeepseekApi.STANDARD -> DeepseekStandardApiImpl(apiKey, sharingPool, baseUrl)
        DeepseekApi.RESPONSES -> DeepseekResponsesApiImpl(apiKey, sharingPool, baseUrl)
    }

    /** FIM 补全 API 的网络后端 */
    val fimApi: DeepseekFimApi = fimApi ?: DeepseekFimApiImpl(apiKey, sharingPool, baseUrl)

    /**
     * Files API 后端（上传/查询/列出/删除）。
     *
     * 与 [api] 无关：Files API 是独立端点，STANDARD 与 RESPONSES 两种 wire format 下都可用。
     *
     * **不可变**：替换只通过构造参数完成（测试注入假实现），因此不存在「一个协程正在用旧实例、
     * 另一个已经拿到新实例」的窗口，也不需要可见性修饰符。`DeepseekFilesApiImpl` 本身无状态，
     * 同一实例可被并发使用。
     */
    val filesApi: DeepseekFiles = filesApi ?: DeepseekFilesApiImpl(apiKey, sharingPool, baseUrl)

    /** 工具调用宿主，设置后 [streamLoop] 自动执行模型请求的工具 */
    var toolHost: ToolCallHost? = null

    /** 工具执行上下文（用户/会话/权限等元信息），供每次工具调用时使用 */
    var executionContext: ToolExecutionContext = ToolExecutionContext("", "")

    /** 系统提示词对应的初始 system 消息；未设置 prompt 时为 `null` */
    val systemPromptMessage: Message? = prompt?.let { Message(Role.System, MessageContent.of(it)) }

    /** 活跃流会话快照；写入时通过 [sessionLock] 保护 */
    @Volatile
    private var sessions: Set<StreamSession> = emptySet()

    private val sessionLock = Mutex()

    /** 当前使用的模型；未显式指定时固定使用库内硬编码的 [Model.Flash]，不会发起网络请求 */
    val resolvedModel: Model get() = model ?: Model.Flash

    /** FIM 补全使用的模型，默认 [Model.Flash]（`deepseek-v4-pro` 已退役） */
    var modelForFim: Model = Model.Flash

    /**
     * 取消当前实例上的活跃流。
     *
     * 有状态模式（[singleSession] 为 `true`）下最多只有一个活跃流；无状态模式下会
     * 取消全部活跃流。除置位会话取消标志外，还会取消收集协程，级联中止底层 HTTP 请求。
     */
    fun cancelStream() {
        sessions.forEach { it.cancel() }
    }

    /**
     * 活跃流会话数。
     *
     * 只给测试用：`unregister` 在取消路径上如果被跳过，长生命周期实例的 [sessions] 会无限增长，
     * 而这个集合本身没有可观测出口，因此留一个内部只读视图（不是公开 API）。
     */
    internal fun activeSessionCount(): Int = sessions.size

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
        // 必须 NonCancellable：`finally` 里跑的这一刻协程往往已经处于取消状态，而 `Mutex.lock` 是
        // 可取消的挂起函数——它会在拿到锁之前就抛 CancellationException，导致这条会话永远留在
        // `sessions` 里（长生命周期实例上表现为集合无限增长）。锁内没有任何挂起点，不会死锁。
        withContext(NonCancellable) {
            sessionLock.withLock {
                sessions = sessions - session
            }
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
     *
     * @param reasoningContent 这一轮 assistant 消息的思考内容。官方规定（Thinking Mode 文档）：
     *   请求带 `tools` 时，历史里**所有轮次**的 `reasoning_content` 都必须完整回传（包括没有发生
     *   工具调用的轮次），缺任意一轮 API 直接返回 400；请求不带 `tools` 时服务端忽略该字段。
     *   这里写下的 assistant(`tool_calls`) 属于历史，同样要带上产生它的那一轮思考。
     */
    internal suspend fun handleToolCalls(
        pendingToolCalls: List<ChatChunk.ToolCallRequest>,
        history: MutableList<Message>,
        reasoningContent: String? = null,
    ): List<ToolExecResult> {
        if (pendingToolCalls.isEmpty()) return emptyList()
        val host = toolHost
        // 客户端工具调用需要 host；纯 web_search 调用由服务端执行，仅需保留历史
        if (host == null && pendingToolCalls.any { it.call.name != DEEPSEEK_WEB_SEARCH_TOOL }) {
            return emptyList()
        }

        history.add(
            Message(
                role = Role.Assistant,
                content = null,
                toolCalls = pendingToolCalls.map { it.call },
                reasoningContent = reasoningContent,
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
                        content = MessageContent.of(result.content),
                        name = DEEPSEEK_WEB_SEARCH_TOOL,
                    )
                )
            } else {
                history.add(
                    Message(
                        role = Role.Tool,
                        content = MessageContent.of(result.content),
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
 * 失败或取消时回滚本轮自己追加的消息（按引用精确删除，见 `ownMessages`），
 * 不会删除其它来源写入同一张历史的、并不属于本轮的消息。
 *
 * 取消时**不冲刷**内容过滤器：被扣留的尾部（至多一个定界符前缀的长度）连同本轮一起丢弃——
 * 该轮本来就会被回滚，而且它只可能是定界符碎片或畸形信封的尾巴，绝不是已经展示过的正文。
 * [hook] 只收到外层 Flow 可见的事件（含最终累计 Done）。
 *
 * `reasoningContent` 是 Beta 字段（[ExperimentalDeepseekApi]），本函数是库内唯一把**流里的**思考内容
 * 写进历史的位置，因此 opt-in 精确落在这一层。
 */
@OptIn(ExperimentalDeepseekApi::class)
internal suspend fun FlowCollector<ChatChunk>.streamLoop(
    core: DeepseekCore,
    history: MutableList<Message>,
    userContent: MessageContent?,
    hook: SseHook?,
    session: StreamSession,
) {
    // 本轮自己追加的消息（按引用记录）：回滚时只删除这些实例，
    // 避免误删并发写入或历史被整体替换后新表上的、并不属于本轮的消息
    val ownMessages = mutableListOf<Message>()
    if (userContent != null) {
        // 内容块（可含图片）原样作为 user 消息入历史。「图片只能在 user 消息中」的限制针对的是
        // 历史里**别人的**消息，由两个后端在请求装配前统一校验（requireImagesAllowed）；本轮输入
        // 本身就是 user 消息，无需自检
        val userMessage = Message(Role.User, content = userContent)
        history.add(userMessage)
        ownMessages += userMessage
    }
    val contentBuilder = StringBuilder()
    // 思考内容要跟着 assistant 消息一起进历史。规则是**请求级**的（官方 Thinking Mode 文档）：
    // 请求带 `tools` 时，历史里所有轮次的 reasoning_content 都要完整回传，漏掉任意一轮，后续请求
    // 就是非法请求（400 "The `reasoning_content` in the thinking mode must be passed back to the
    // API"）；请求不带 `tools` 时服务端忽略该字段。模型确实没思考的轮次没有内容可回传，字段保持 null。
    // 空串一律归一成 null：`Message` 序列化时 null 不写字段，空串会写出一个无意义的空字段。
    val reasoningBuilder = StringBuilder()
    var iterations = 0

    var totalPromptTokens = 0L
    var totalCompletionTokens = 0L
    var totalTokens = 0L
    var finalFinishReason: String? = null
    var committed = false

    try {
        // 工具定义在一次流式对话中不会变化，只取一次；host 也一起快照，避免同一轮里
        // 「tools 用的是旧 host、canExecute 用的是新 host」这种撕裂读
        val host = core.toolHost
        val tools = host?.getDefinitions()?.ifEmpty { null }
        // 已知的上游触发条件（deepseek-ai/DeepSeek-V3#1678）：请求不带 `tools` 而历史里仍有 tool
        // 轮次时，服务端不会把模型的工具调用语法转成结构化 tool_calls，而是把它当正文原样下发。
        // 库补不出工具定义（没有 host 就没有 schema），所以这里只把破防点写进日志；
        // 响应侧的兜底见下方 InlineToolCallRecovery。
        replayToolChannelDiagnostic(history, tools)?.let { diagnostic ->
            coreLogger.error { "chat.request $diagnostic" }
        }
        // 上游泄漏的处理策略：默认 RECOVER（恢复执行 + 剔除），可用 ChatConfig 显式退出。
        // PASSTHROUGH 是逃生舱：完全不干预内容与历史，保持改动前的逐字透传
        val inlineToolCallPolicy = core.config.inlineToolCallPolicy

        // 允许恢复的工具名 = 注册表 ∩ 调用方声明的工具策略。`toolChoice` 是开发者的显式声明
        // （`None` = 一个都别调、`Named(x)` = 只允许 x），而恢复是**客户端**解释出来的调用，
        // 服务端无从否决，因此这里必须自己把关（安全评审 HIGH-1）
        val allowedToolNames = when (val choice = core.config.toolChoice) {
            null, ToolChoice.Auto, ToolChoice.Required -> tools?.mapTo(mutableSetOf()) { it.name }.orEmpty()
            ToolChoice.None -> emptySet()
            is ToolChoice.Named -> tools?.mapNotNullTo(mutableSetOf()) { if (it.name == choice.name) it.name else null }
                .orEmpty()
        }

        while (iterations < core.config.maxToolIterations) {
            // ★ 检查点 1: 每次 tool-call 循环迭代前
            if (session.cancelled) return
            iterations++
            val pendingToolCalls = mutableListOf<ChatChunk.ToolCallRequest>()
            var hasToolCallInResponse = false
            // 过滤器的生命周期是「一条模型响应」：信封不跨响应，而 contentBuilder 是整条流共享的，
            // 两者生命周期不同——复用同一个实例会把上一轮扣留的尾部漏进下一轮
            val recovery = InlineToolCallRecovery(
                allowedTools = allowedToolNames,
                executionDisabledReason = when {
                    host == null -> "no-tool-host"
                    inlineToolCallPolicy != InlineToolCallPolicy.RECOVER -> "policy-strip-only"
                    else -> null
                },
            )
            // 本轮结构化 tool_calls 的指纹：用来给恢复出来的调用去重（网关可能同时下发两条通道，
            // 不去重就会把同一个副作用执行两次）
            val structuredCalls = mutableListOf<Pair<String, JsonElement>>()
            // 恢复出来的调用**攒到本轮收尾**再注入：信封在正文之后才出现，立刻 emit 会让工具调用
            // 排在自己那句正文前面；攒着也才能与结构化通道做去重
            val recoveredCalls = mutableListOf<ToolCall>()

            // 收尾时统一注入：去重 + 保证「先正文、后工具调用」的发射顺序
            suspend fun adoptRecovered(calls: List<ToolCall>) {
                if (calls.isEmpty()) return
                // 取消在 collect 与本行之间落地时，先在这里抛出：钩子不能看到 Flow 看不到的事件
                currentCoroutineContext().ensureActive()
                for (call in calls) {
                    val recoveredArguments = parsedArgumentsOf(call.arguments)
                    if (structuredCalls.any { it.first == call.name && it.second == recoveredArguments }) {
                        coreLogger.error {
                            "内联信封与结构化 tool_calls 重复（同名同参数），已跳过恢复出来的那次：${call.name}"
                        }
                        continue
                    }
                    val request = ChatChunk.ToolCallRequest(call)
                    pendingToolCalls += request
                    hasToolCallInResponse = true
                    hook?.onChunk(request)
                    emit(request)
                }
            }

            core.backend.completions(
                // 必须是拷贝：序列化期间 handleToolCalls 会继续往 history 追加，别名会导致并发修改
                messages = history.toList(),
                model = core.resolvedModel,
                config = core.config,
                tools = tools,
            ).collect { chunk ->
                // ★ 检查点 2: 每个 SSE chunk 到达时
                if (session.cancelled) return@collect
                when (chunk) {
                    is ChatChunk.ContentDelta -> if (inlineToolCallPolicy == InlineToolCallPolicy.PASSTHROUGH) {
                        // 逃生舱：完全不干预，与改动前逐字一致
                        if (chunk.content.isNotEmpty()) contentBuilder.append(chunk.content)
                        chunk.reasoningContent?.let { reasoningBuilder.append(it) }
                        hook?.onChunk(chunk)
                        emit(chunk)
                    } else {
                        val filtered = if (chunk.content.isEmpty()) null else recovery.acceptContent(chunk.content)
                        if (filtered != null) recoveredCalls += filtered.recovered
                        // 思考通道只剔除信封、绝不执行：thinking 是模型的内心草稿。
                        // 过滤后为空串时归一成 null（与历史里「空串一律归一成 null」同一口径），
                        // 免得下游为一段被剔干净的思考渲染一个空气泡
                        val reasoning = chunk.reasoningContent
                            ?.let { recovery.acceptThinking(it) }
                            ?.takeIf { it.isNotEmpty() }
                        val visible = filtered?.visible.orEmpty()
                        if (visible.isNotEmpty() || reasoning != null) {
                            if (visible.isNotEmpty()) contentBuilder.append(visible)
                            reasoning?.let { reasoningBuilder.append(it) }
                            val out = ChatChunk.ContentDelta(visible, reasoning)
                            hook?.onChunk(out)
                            emit(out)
                        }
                    }
                    is ChatChunk.ToolCallRequest -> {
                        hasToolCallInResponse = true
                        structuredCalls += chunk.call.name to parsedArgumentsOf(chunk.call.arguments)
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

            // 冲刷两个过滤器：信封可能在最后一个 delta 之后才补齐，也可能永远不补齐。必须在
            // `hasToolCallInResponse` 判定之前结算，否则「末尾才到达的调用」会被当成没有工具调用而收尾。
            // PASSTHROUGH 下过滤器从未被喂过，这里的冲刷天然是空操作
            val tail = recovery.flushContent()
            recoveredCalls += tail.recovered
            if (tail.visible.isNotEmpty()) {
                contentBuilder.append(tail.visible)
                val out = ChatChunk.ContentDelta(tail.visible, null)
                hook?.onChunk(out)
                emit(out)
            }
            val tailThinking = recovery.flushThinking()
            if (tailThinking.isNotEmpty()) {
                reasoningBuilder.append(tailThinking)
                val out = ChatChunk.ContentDelta("", tailThinking)
                hook?.onChunk(out)
                emit(out)
            }
            if (recovery.recoveredEnvelopes > 0 || recovery.droppedEnvelopes > 0 || recovery.thinkingEnvelopes > 0) {
                coreLogger.error {
                    "本轮响应检测到内联工具调用信封：recovered=${recovery.recoveredEnvelopes} " +
                        "dropped=${recovery.droppedEnvelopes} thinking=${recovery.thinkingEnvelopes} " +
                        "droppedChars=${recovery.droppedChars}"
                }
            }
            // 正文/思考都发完了，再注入恢复出来的工具调用：保持「内容增量 → 工具调用请求」的顺序
            adoptRecovered(recoveredCalls)

            if (!hasToolCallInResponse) break // No tool calls → stream is truly done

            // ★ 检查点 3: 工具调用执行前
            if (session.cancelled) return
            val toolWriteStart = history.size
            // bookkeeping 必须放 finally：`handleToolCalls` 先写 assistant(tool_calls) 再逐个执行工具，
            // 中途被取消（工具挂起时抛 CancellationException）会直接跳出，若不在这里记账，那条
            // assistant(tool_calls) 就既不在回滚范围内、也永远等不到配对的 tool 结果
            val toolResults = try {
                core.handleToolCalls(
                    pendingToolCalls,
                    history,
                    reasoningContent = reasoningBuilder.toString().ifEmpty { null },
                )
            } finally {
                ownMessages += history.subList(toolWriteStart, history.size)
            }
            // 思考内容按轮归属：这一轮已经随 assistant(tool_calls) 写进历史，下一轮的思考要重新累积。
            //
            // **只有真的写下了那条 assistant(tool_calls) 才清**：`handleToolCalls` 唯一的空返回路径是
            // 「没有 toolHost 且存在非 web_search 调用」（见其早期的 `return emptyList()`），此时历史里
            // 什么都没写，这里的思考是这个回合唯一的推理记录。清掉它，这一轮的 reasoning 就凭空消失，
            // 而带 `tools` 的后续请求要求历史里所有轮次的 reasoning 完整回传——少一轮就是 400。
            if (toolResults.isNotEmpty()) reasoningBuilder.clear()
            for (tr in toolResults) {
                val data = ChatChunk.ToolResultData(tr.toolCallId, tr.functionName, tr.content, tr.isError)
                hook?.onChunk(data)
                emit(data)
            }
            // 工具结果一律在历史里配了对，才继续下一轮；否则收尾（保留思考给最终消息）。
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
            val assistantMessage = Message(
                role = Role.Assistant,
                content = MessageContent.of(contentBuilder.toString()),
                reasoningContent = reasoningBuilder.toString().ifEmpty { null },
            )
            history.add(assistantMessage)
            ownMessages += assistantMessage
        }
        committed = true
    } finally {
        if (!committed) {
            // 只回滚本轮的写入：按引用查找并删除，绝不按位置截断
            //（按位置截断会连带删除同一张表上由其它来源追加的消息）
            for (i in ownMessages.indices.reversed()) {
                val message = ownMessages[i]
                val index = history.indexOfFirst { it === message }
                if (index >= 0) history.removeAt(index)
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
