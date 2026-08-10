package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.ResponseFormat
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.StopToken
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import kotlinx.coroutines.flow.Flow

/**
 * 对话补全的控制参数。
 *
 * 修改 [Deepseek.config] 的属性后，对后续所有请求生效：
 *
 * ```kotlin
 * val ds = Deepseek("sk-xxx")
 * ds.config.maxTokens = 2048
 * ds.config.temperature = 0.7
 * ds.config.thinkingMode = ThinkingMode.Disabled()
 * ds.config.responseFormat = ResponseFormat.JSON_OBJECT
 * ```
 *
 * 也可通过 [deepseek] DSL 在构造时一次性配置：
 *
 * ```kotlin
 * val ds = deepseek("sk-xxx") {
 *     config {
 *         maxTokens = 2048
 *         temperature = 0.7
 *     }
 * }
 * ```
 */
class ChatConfig {
    /** 最大生成 token 数，`null` 表示不限制 */
    var maxTokens: Int? = null

    /** 采样温度，范围 `(0, 2]`，越高越随机 */
    var temperature: Double? = null

    /** 核采样参数，范围 `(0, 1]` */
    var topP: Double? = null

    /**
     * 思考模式。
     *
     * - `null` 或 [io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode.Enabled] — 默认开启思考
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode.Disabled] — 关闭思考，不发送 reasoning_content
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode.WithEffort] — 指定推理强度
     */
    var thinkingMode: ThinkingMode? = null

    /**
     * 响应格式。
     *
     * - `null` — 默认 text
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ResponseFormat.JSON_OBJECT] — 强制模型输出合法 JSON
     */
    var responseFormat: ResponseFormat? = null

    /** 停止词。`null` 表示不设置 */
    var stop: StopToken? = null

    /**
     * 工具调用策略。
     *
     * - `null` — API 默认行为（无 tool 时为 [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.None]，有 tool 时为 [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Auto]）
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.None] — 不调用任何工具
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Auto] — 模型自行决定
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Required] — 必须调用工具
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Named] — 强制调用指定工具
     */
    var toolChoice: ToolChoice? = null

    /** 是否在响应中返回 token 用量统计，默认 `true` */
    var includeUsage: Boolean = true

    /** 是否返回输出 token 的对数概率 */
    var logprobs: Boolean? = null

    /** `0` 到 `20` 之间，每个输出 token 返回的对数概率数量 */
    var topLogprobs: Int? = null

    /**
     * 是否启用服务端联网搜索（内置 `web_search` 工具）。
     *
     * **仅在 `api == DeepseekApi.RESPONSES` 时生效；STANDARD 模式忽略该配置。**
     * 开启后，请求的 `tools` 会追加 `{"type":"web_search"}`，模型可自主发起联网搜索；
     * 搜索结果由服务端在同一流内完成，库不会因 `web_search_call` 发起下一轮请求。
     */
    var enableWebSearch: Boolean = false

    /** 工具调用最大迭代次数，防止无限循环，默认 `15` */
    var maxToolIterations: Int = 15
}

/**
 * DeepSeek 对话客户端的公共能力接口。
 *
 * [Deepseek]（有状态，持有历史）与 [StatelessDeepseek]（无状态）都实现该接口；
 * 无状态客户端不提供历史相关操作。
 */
public interface ChatClient {
    /** DeepSeek API 密钥 */
    public val apiKey: String

    /** 对话补全控制参数，修改后对后续所有请求生效 */
    public val config: ChatConfig

    /** 工具调用宿主，设置后 [chatStream] 自动执行模型请求的工具 */
    public var toolHost: ToolCallHost?

    /** 工具执行上下文（用户/会话/权限等元信息），供每次工具调用时使用 */
    public var executionContext: ToolExecutionContext

    /** 当前使用的模型；未显式指定时固定使用 [Model.Flash] */
    public val resolvedModel: Model

    /**
     * 发起流式对话补全请求。
     *
     * @param userContent 用户输入文本
     * @param hook 可选的实时回调，与 Flow 事件一致
     * @return 流式响应的 [Flow]，发射 [ChatChunk] 事件
     */
    public fun chatStream(userContent: String, hook: SseHook? = null): Flow<ChatChunk>

    /** 中断当前正在进行的流，并中止底层 HTTP 请求 */
    public fun cancelStream()

    /** 获取当前 API Key 可用的模型列表 */
    public suspend fun availableModels(): List<Model>

    /** 获取当前 API Key 的账户余额信息 */
    public suspend fun balance(): UserBalance
}

/**
 * DeepSeek API 客户端实例。
 *
 * 每一个实例会自动持有历史聊天记录 [messages]（即在调用 [chatStream] 时自动更新 [messages]），
 * 设计上考虑的是每一个会话持有一个 [Deepseek] 实例。
 *
 * 如果你需要的是无状态的客户端可参见 [StatelessDeepseek]；[StatelessDeepseek] 不会自动存储历史聊天记录，
 * 每次 [chatStream] 提交的 `messages` 都只包含 `system prompt` + `user message`。
 *
 * ## 快速开始
 *
 * ```kotlin
 * // 基础构造
 * val ds = Deepseek("sk-your-api-key")
 *
 * // 流式对话
 * ds.chatStream("你好").collect { chunk ->
 *     when (chunk) {
 *         is ChatChunk.ContentDelta -> print(chunk.content)
 *         is ChatChunk.ToolCallRequest -> println("调用工具: ${chunk.call.name}")
 *         is ChatChunk.Done -> println("完成: ${chunk.totalTokens} tokens")
 *     }
 * }
 * ```
 *
 * 推荐使用 [deepseek] DSL 进行更简洁的构造：
 *
 * ```kotlin
 * val ds = deepseek("sk-xxx") {
 *     model { flash() }
 *     prompt = "You are a helpful assistant."
 *     config { maxTokens = 2048 }
 *     tools {
 *         tool("get_weather") { ... }
 *     }
 * }
 * ```
 *
 * @property apiKey DeepSeek API 密钥，从 [DeepSeek 平台](https://platform.deepseek.com) 获取
 * @param model 指定使用的模型；为 `null` 时使用库内硬编码的 [Model.Flash]，不会发起网络请求
 * @property prompt 系统提示词，作为对话历史中的初始 system 消息；为 `null` 时历史以第一条用户消息开始
 * @param enableBeta 是否使用 Beta API endpoint（在原请求 baseurl 基础上加一个 `/beta`）
 * @property config 对话补全控制参数，修改后对后续所有请求生效
 * @param api 使用的 API wire format（[DeepseekApi.STANDARD] 或 [DeepseekApi.RESPONSES]）
 *
 * @see [deepseek] 推荐通过 DSL 方式构造
 * @see collectResponse 对流式结果的结构化收集扩展
 * @see ChatConfig
 */
public open class Deepseek(
    public override val apiKey: String,
    model: Model? = null,
    internal val prompt: String? = null,
    private val enableBeta: Boolean = false,
    public override val config: ChatConfig = ChatConfig(),
    private val api: DeepseekApi = DeepseekApi.STANDARD,
) : ChatClient {

    /** 共享的客户端核心（网络后端、取消机制与流式对话循环） */
    internal val core: DeepseekCore = DeepseekCore(apiKey, model, prompt, enableBeta, config, api)

    /** 按 [api] 选择对应的 wire format 后端实现 */
    internal val backend: DeepseekApiBackend get() = core.backend

    /**
     * 工具调用宿主。
     *
     * 设置后，[chatStream] 自动处理模型返回的 `tool_calls`：执行工具、把 assistant 与
     * tool 消息写入历史并继续对话循环，直到模型不再请求工具调用或达到
     * [ChatConfig.maxToolIterations]；未设置时模型请求的工具调用不会被执行。
     *
     * 可通过 [io.github.hatoyuze.deepseek.toolcall.dsl.toolHost] DSL 构建：
     *
     * ```kotlin
     * ds.toolHost = toolHost {
     *     tool("get_weather") {
     *         description = "获取指定城市的天气"
     *         parameters { string("city") { required = true } }
     *         execute { bag, _ ->
     *             WeatherResult(city = bag["city"] as String, weather = "晴", temp = 25)
     *         }
     *     }
     * }
     * ```
     */
    override var toolHost: ToolCallHost?
        get() = core.toolHost
        set(value) {
            core.toolHost = value
        }

    /** 工具执行上下文（用户/会话/权限等元信息），供每次工具调用时使用 */
    override var executionContext: ToolExecutionContext
        get() = core.executionContext
        set(value) {
            core.executionContext = value
        }

    /** 系统提示词对应的初始 system 消息；未设置 prompt 时为 `null` */
    internal val systemPromptMessage: Message? get() = core.systemPromptMessage

    /** 取消标志：由 [cancelStream] 置位，每次流式调用开始时自动复位 */
    public var isCancelled: Boolean
        get() = core.isCancelled
        set(value) {
            core.isCancelled = value
        }

    /**
     * 中断当前正在进行的 [chatStream] 流。
     *
     * 除设置取消标志外，还会取消当前流的收集协程，级联中止底层 HTTP 请求：
     * 连接被关闭，服务端停止生成，不再继续消耗 token。
     * 下一次 [chatStream] / [continueStream] 调用会自动重置取消状态；
     * 调用方的 `collect` / [collectResponse] 可能抛出 [kotlinx.coroutines.CancellationException]。
     */
    override fun cancelStream() = core.cancelStream()

    /**
     * 截断消息历史，仅保留下标 `[0, index]` 的消息（含两端）。
     *
     * 下标按内部历史计算，system prompt 位于 0（若设置了 [prompt]）；下标越界时不做任何操作。
     * 常用于“重新生成”：先截断到目标 user 消息，再调用 [continueStream]。
     *
     * @param index 保留的最后一个消息下标
     */
    open fun truncateAt(index: Int) {
        if (index >= 0 && index < _messages.lastIndex) {
            while (_messages.size > index + 1) {
                _messages.removeAt(_messages.lastIndex)
            }
        }
    }

    /** 返回当前消息历史的消息数（含 system prompt） */
    open fun getMessageCount(): Int = _messages.size

    /**
     * 按内容在消息历史中查找第一条 user 消息的下标，未找到时返回 -1。
     *
     * @param content 要匹配的 user 消息文本
     */
    open fun findUserMessageIndex(content: String): Int {
        return _messages.indexOfFirst {
            it.role == Role.User && it.content == content
        }
    }

    /** 当前使用的模型；未显式指定时固定为库内硬编码的 [Model.Flash] */
    override val resolvedModel: Model get() = core.resolvedModel

    private val _messages by lazy {
        mutableListOf<Message>().apply {
            systemPromptMessage?.let { add(it) }
        }
    }

    /** 当前对话历史（只读），首条为 system prompt（若设置了 [prompt]） */
    open val messages: List<Message> get() = _messages

    /**
     * 手动向对话历史追加一条消息，追加后参与后续 [chatStream] / [continueStream] 请求。
     *
     * @param message 要追加的消息
     */
    open fun addMessage(message: Message) {
        _messages.add(message)
    }

    /**
     * 发起流式对话补全请求。
     *
     * 内部流程：把 [userContent] 作为 user 消息追加到历史 → 发射逐块 [ChatChunk]
     * （含工具调用循环）→ 流结束时发射一次累计 usage 的 [ChatChunk.Done]，并把
     * assistant 回复写入历史。工具循环中各轮请求的 Done 不会对外发射。
     *
     * 失败或取消时历史会回滚到本次调用前的状态。
     *
     * ```kotlin
     * // 实时打印
     * ds.chatStream("今天天气怎么样？").collect { chunk ->
     *     when (chunk) {
     *         is ChatChunk.ContentDelta -> print(chunk.content)
     *         is ChatChunk.ToolCallRequest -> println("[工具调用] ${chunk.call.name}")
     *         is ChatChunk.Done -> println("[完成] ${chunk.totalTokens} tokens")
     *     }
     * }
     *
     * // 使用 Flow 扩展简化
     * val response = ds.chatStream("你好")
     *     .onThinking { print("思考: $it") }
     *     .onContent { print(it) }
     *     .collectResponse()
     * ```
     *
     * @param userContent 用户输入文本，会追加到消息历史
     * @param hook 可选的实时回调，与 Flow 事件一致，先于 Flow 触发
     * @return 流式响应的 [Flow]，发射 [ChatChunk] 事件
     */
    override fun chatStream(userContent: String, hook: SseHook?): Flow<ChatChunk> =
        core.streamFlow { streamLoop(core, _messages, userContent, hook) }

    /**
     * 继续流式对话，与 [chatStream] 相同但不追加 user 消息。
     *
     * 用于“重新生成”场景：历史已截断至目标 user 消息，直接基于现有历史继续补全，
     * 避免重复添加用户输入。行为与取消语义同 [chatStream]。
     *
     * @param hook 可选的实时回调，与 Flow 事件一致，先于 Flow 触发
     * @return 流式响应的 [Flow]，发射 [ChatChunk] 事件
     */
    open fun continueStream(hook: SseHook? = null): Flow<ChatChunk> =
        core.streamFlow { streamLoop(core, _messages, null, hook) }

    internal suspend fun handleToolCalls(
        pendingToolCalls: List<ChatChunk.ToolCallRequest>,
    ): List<ToolExecResult> = core.handleToolCalls(pendingToolCalls, _messages)

    internal suspend fun handleToolCalls(
        pendingToolCalls: List<ChatChunk.ToolCallRequest>,
        history: MutableList<Message>,
    ): List<ToolExecResult> = core.handleToolCalls(pendingToolCalls, history)

    /**
     * 获取当前 API Key 可用的模型列表。
     *
     * @return 模型列表，可配合 [Model.flash] / [Model.pro] 等辅助方法选择
     *
     * @see Model
     */
    override suspend fun availableModels(): List<Model> = core.availableModels()

    /**
     * 获取当前 API Key 的账户余额信息。
     *
     * @return 账户余额信息
     *
     * @see UserBalance
     */
    override suspend fun balance(): UserBalance = core.balance()
}

/**
 * 流式响应的增量事件。
 *
 * 一次 [Deepseek.chatStream] 调用会依次发射内容增量、工具调用请求/结果，
 * 并以 [Done] 收尾；使用 `when` 分支处理不同类型：
 *
 * ```kotlin
 * when (chunk) {
 *     is ChatChunk.ContentDelta -> {
 *         print(chunk.content)                     // 正常内容
 *         chunk.reasoningContent?.let { ... }      // 思考内容 (Beta)
 *     }
 *     is ChatChunk.ToolCallRequest -> { ... }
 *     is ChatChunk.Done -> { ... }
 * }
 * ```
 *
 * @see onThinking
 * @see onContent
 * @see collectResponse
 */
sealed class ChatChunk {
    /**
     * 内容增量（流式传输的基本单位）。
     *
     * @property content 模型生成的纯文本增量，可能为空字符串
     * @property reasoningContent 思考（推理）内容增量，非空时表示模型正在思考（Beta）
     */
    data class ContentDelta(
        val content: String,
        val reasoningContent: String? = null,
    ) : ChatChunk()

    /**
     * 工具调用请求（完整的 tool call，流式累积完毕后一次性发射）。
     *
     * @property call 统一的工具调用领域模型
     */
    data class ToolCallRequest(
        val call: ToolCall,
    ) : ChatChunk()

    /**
     * 工具调用结果（执行完毕后一次性发射），模型可在后续轮次中据此继续。
     *
     * @property toolCallId 对应的工具调用 ID
     * @property functionName 函数名称
     * @property content 工具返回的 JSON 内容或错误信息
     * @property isError 是否执行失败
     */
    data class ToolResultData(
        val toolCallId: String,
        val functionName: String,
        val content: String,
        val isError: Boolean,
    ) : ChatChunk()

    /**
     * 流结束事件，携带完整的 token 用量。
     *
     * 含工具调用循环时，各轮请求的用量会被累加，最终只发射一次 [Done]。
     *
     * @property promptTokens 提示词消耗的 token 数
     * @property completionTokens 补全消耗的 token 数
     * @property totalTokens 总计消耗的 token 数
     * @property finishReason 结束原因（如 `stop`、`length`、`tool_calls`、`max_tool_iterations`）
     */
    data class Done(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val finishReason: String? = null,
    ) : ChatChunk()
}

/**
 * SSE 流钩子，提供不依赖 [Flow] 的实时回调。
 *
 * 每个外层 Flow 可见的 [ChatChunk] 在进入 [Flow] 的 emit 前先回调 [onChunk]
 * （与 Flow 事件完全一致，工具循环中只收到最终累计 [ChatChunk.Done]）。
 * 适合需要实时反馈的场景（如逐字打印到终端）。
 *
 * ```kotlin
 * val hook = SseHook { chunk ->
 *     if (chunk is ChatChunk.ContentDelta && chunk.content.isNotEmpty()) {
 *         print(chunk.content)
 *     }
 * }
 * ds.chatStream("hello", hook = hook).collect { ... }
 * ```
 *
 * 大多数场景推荐使用 [onContent]、[onThinking] 等扩展函数替代 `SseHook`，
 * 它们提供更声明式的写法且不牺牲实时性。
 */
fun interface SseHook {
    /**
     * 处理到达的流式事件。
     *
     * @param chunk 流式增量事件
     */
    suspend fun onChunk(chunk: ChatChunk)
}
