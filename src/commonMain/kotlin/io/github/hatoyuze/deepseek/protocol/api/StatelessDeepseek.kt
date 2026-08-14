package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import kotlinx.coroutines.flow.Flow

/**
 * 不保存聊天历史的 [ChatClient] 实现。
 *
 * 每次调用 [chatStream] 都会创建一个仅含 system prompt 的本地消息缓冲，
 * 对话结束后即丢弃，不会在实例上累积任何历史；适合无状态的一次性问答场景。
 *
 * ```kotlin
 * val ds = statelessDeepseek("sk-xxx") {
 *     prompt = "You are a helpful assistant."
 *     config { thinkingMode = ThinkingMode.Max }
 * }
 * ds.chatStream("你好").collect { ... }
 * ```
 *
 * 与 [Deepseek] 不同，无状态实例不提供 `messages`、`addMessage`、`truncateAt`
 * 等历史操作；[toolHost]、[executionContext]、[config]、[cancelStream]、
 * [availableModels]、[balance] 等行为与 [Deepseek] 一致。
 *
 * @param apiKey DeepSeek API 密钥
 * @param model 指定使用的模型，为 `null` 时使用库内硬编码的 [Model.Flash]
 * @param prompt 系统提示词，作为每次对话的初始 system 消息
 * @param sharingPool 客户端共享的 HttpClient 池，默认使用 [DeepseekHttpClientPool.Global]
 * @param config 对话补全控制参数
 * @param api 使用的 API wire format
 *
 * @see statelessDeepseek 推荐通过 DSL 方式构造
 */
public class StatelessDeepseek(
    public override val apiKey: String,
    model: Model? = null,
    internal val prompt: String? = null,
    public override val config: ChatConfig = ChatConfig(),
    private val api: DeepseekApi = DeepseekApi.STANDARD,
    public val sharingPool: DeepseekHttpClientPool = DeepseekHttpClientPool.Global,
) : ChatClient {

    /** 共享的客户端核心（网络后端、取消机制与流式对话循环） */
    internal var core: DeepseekCore = DeepseekCore(
        apiKey = apiKey,
        model = model,
        prompt = prompt,
        config = config,
        api = api,
        sharingPool = sharingPool,
        singleSession = false,
    )

    /**
     * 测试注入内部构造：允许用 Fake 后端/核心构建客户端而不触网。
     * 不改变公开构造签名。
     */
    internal constructor(apiKey: String, core: DeepseekCore) : this(apiKey) {
        this.core = core
    }

    /** 按 [api] 选择对应的 wire format 后端实现 */
    internal val backend: DeepseekApiBackend get() = core.backend

    public override var toolHost: ToolCallHost?
        get() = core.toolHost
        set(value) {
            core.toolHost = value
        }

    public override var executionContext: ToolExecutionContext
        get() = core.executionContext
        set(value) {
            core.executionContext = value
        }

    /** 系统提示词对应的初始 system 消息；未设置 prompt 时为 `null` */
    internal val systemPromptMessage: Message? get() = core.systemPromptMessage

    public override val resolvedModel: Model get() = core.resolvedModel

    /**
     * FIM 补全使用的模型，默认 [Model.Pro]。
     *
     * **Beta**：见 [fimStream]。
     */
    @ExperimentalDeepseekApi
    public var modelForFim: Model
        get() = core.modelForFim
        set(value) {
            core.modelForFim = value
        }

    /**
     * 发起一次性流式对话。
     *
     * 每次调用都会创建仅含 system prompt 的本地消息缓冲，结束后即丢弃，
     * 不会在实例上累积历史；失败或取消时同样回滚本次调用产生的缓冲消息。
     *
     * @param userContent 用户输入文本
     * @param hook 可选的实时回调，与 Flow 事件一致
     * @return 流式响应的 [Flow]
     */
    public override fun chatStream(userContent: String, hook: SseHook?): Flow<ChatChunk> =
        core.streamFlow { session ->
            val history = mutableListOf<Message>().apply {
                core.systemPromptMessage?.let { add(it) }
            }
            streamLoop(core, history, userContent, hook, session)
        }

    /**
     * 发起流式 FIM（Fill In the Middle）补全请求。
     *
     * **Beta**：请求发送到 `/beta/completions` 端点，标注 [ExperimentalDeepseekApi]，
     * 使用时需 `@OptIn(ExperimentalDeepseekApi::class)`；契约可能在后续版本调整。
     *
     * 请求固定发送到 `https://api.deepseek.com/beta/completions`；模型使用
     * [modelForFim]，其余参数复用 [config] 中的 `maxTokens`、`temperature`、`topP`、
     * `stop`、`includeUsage` 与 `topLogprobs`。
     *
     * ```kotlin
     * val response = ds.fimStream("def add(a, b):").collectFimResponse()
     * println(response.text)
     * ```
     *
     * @param prompt 补全提示（前缀）
     * @param suffix 被补全内容的后缀，可为 `null`
     * @param echo 是否在输出中回显 prompt，可为 `null`
     * @param hook 可选的实时回调，与 Flow 事件一致
     * @return 流式响应的 [Flow]，发射 [FimChunk] 事件
     */
    @ExperimentalDeepseekApi
    public fun fimStream(
        prompt: String,
        suffix: String? = null,
        echo: Boolean? = null,
        hook: SseHook? = null,
    ): Flow<FimChunk> = core.fimFlow(prompt, suffix, echo, hook)

    /**
     * 取消该实例当前全部活跃流（[chatStream] 与 [fimStream]）。
     *
     * `StatelessDeepseek` 支持并发流；如需只取消单个任务，建议由调用方取消对应
     * `collect` 协程，而不是调用本方法。
     */
    public override fun cancelStream(): Unit = core.cancelStream()

    internal suspend fun handleToolCalls(
        pendingToolCalls: List<ChatChunk.ToolCallRequest>,
        history: MutableList<Message>,
    ): List<ToolExecResult> = core.handleToolCalls(pendingToolCalls, history)

    public override suspend fun availableModels(): List<Model> = core.availableModels()

    public override suspend fun balance(): UserBalance = core.balance()
}
