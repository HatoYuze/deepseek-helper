package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.ResponseFormat
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.StopToken
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.entity.InlineToolCallPolicy
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.net.normalizeBaseUrl
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import kotlinx.coroutines.flow.Flow
import kotlin.concurrent.Volatile

/**
 * 对话补全的控制参数。
 *
 * 修改 [Deepseek.config] 的属性后，对后续所有请求生效：
 *
 * ```kotlin
 * val ds = Deepseek("sk-xxx")
 * ds.config.maxTokens = 2048
 * ds.config.temperature = 0.7
 * ds.config.thinkingMode = ThinkingMode.Disabled
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
public class ChatConfig {
    /** 最大生成 token 数，`null` 表示不限制 */
    public var maxTokens: Int? = null

    /** 采样温度，范围 `(0, 2]`，越高越随机 */
    public var temperature: Double? = null

    /** 核采样参数，范围 `(0, 1]` */
    public var topP: Double? = null

    /**
     * 思考模式。
     *
     * - `null` 或 [io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode.Enabled] — 默认开启思考
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode.Disabled] — 关闭思考，不发送 reasoning_content
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode.WithEffort] — 指定推理强度
     */
    public var thinkingMode: ThinkingMode? = null

    /**
     * 响应格式。
     *
     * - `null` — 默认 text
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ResponseFormat.JSON_OBJECT] — 强制模型输出合法 JSON
     */
    public var responseFormat: ResponseFormat? = null

    /** 停止词。`null` 表示不设置 */
    public var stop: StopToken? = null

    /**
     * 工具调用策略。
     *
     * - `null` — API 默认行为（无 tool 时为 [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.None]，有 tool 时为 [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Auto]）
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.None] — 不调用任何工具
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Auto] — 模型自行决定
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Required] — 必须调用工具
     * - [io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice.Named] — 强制调用指定工具
     */
    public var toolChoice: ToolChoice? = null

    /** 是否在响应中返回 token 用量统计，默认 `true` */
    public var includeUsage: Boolean = true

    /** 是否返回输出 token 的对数概率 */
    public var logprobs: Boolean? = null

    /** `0` 到 `20` 之间，每个输出 token 返回的对数概率数量 */
    public var topLogprobs: Int? = null

    /**
     * 是否启用服务端联网搜索（内置 `web_search` 工具）。
     *
     * **仅在 `api == DeepseekApi.RESPONSES` 时生效；STANDARD 模式忽略该配置。**
     * 开启后，请求的 `tools` 会追加 `{"type":"web_search"}`，模型可自主发起联网搜索；
     * 搜索结果由服务端在同一流内完成，库不会因 `web_search_call` 发起下一轮请求。
     */
    public var enableWebSearch: Boolean = false

    /** 工具调用最大迭代次数，防止无限循环，默认 `15` */
    public var maxToolIterations: Int = 15

    /**
     * 上游把**内部工具调用语法**（信封）当正文下发时的处理策略，默认
     * [InlineToolCallPolicy.RECOVER]（恢复执行 + 剔除）。
     *
     * 这是面向上游已知缺陷（`deepseek-ai/DeepSeek-V3#1678` / `#1244`）的**逃生舱**：
     * 默认值修掉线上泄漏，而自行后处理正文的上层（例如应用侧已有自己的解析器、
     * 或需要逐字保真地展示模型输出）可以显式退出。
     *
     * 与其它 [ChatConfig] 字段一致：**请在开始收集流之前设置**，同一实例在流中途被改动
     * 不保证对进行中的请求生效（`ChatConfig` 是共享的可变对象）。
     *
     * @see InlineToolCallPolicy
     */
    @property:ExperimentalDeepseekApi
    public var inlineToolCallPolicy: InlineToolCallPolicy = InlineToolCallPolicy.RECOVER
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


    /**
     * 发起携带内容块（可含图片）的流式对话补全请求。
     *
     * @param content 本条 user 消息的内容（纯文本或内容块数组）
     * @param hook 可选的实时回调，与 Flow 事件一致
     * @return 流式响应的 [Flow]，发射 [ChatChunk] 事件
     */
    public fun chatStream(content: MessageContent, hook: SseHook? = null): Flow<ChatChunk>

    /** 中断当前正在进行的流，并中止底层 HTTP 请求 */
    public fun cancelStream()

    /** 获取当前 API Key 可用的模型列表 */
    public suspend fun availableModels(): List<Model>

    /** 获取当前 API Key 的账户余额信息 */
    public suspend fun balance(): UserBalance

    /**
     * Files API：上传图片一次，之后在请求里用 `file_id` 反复引用。
     *
     * 与 wire format（[DeepseekApi]）无关，STANDARD / RESPONSES 两种模式都可用。
     *
     * @see DeepseekFiles
     */
    public fun files(): DeepseekFiles
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
 * ## 图片输入
 *
 * user 消息的内容可以是「内容块数组」，从而携带图片（[MessageContent] / [ContentPart]）。
 * 三种官方传图方式都支持：
 *
 * ```kotlin
 * // ① base64 内联（本地文件最简单，单图 ≤ 32 MiB）
 * ds.chatStream(
 *     MessageContent.of(
 *         MessageContent.textPart("这张图片里有什么？"),
 *         MessageContent.imageDataUrl("image/jpeg", jpegBytes),
 *     ),
 * ).collect { ... }
 *
 * // ② 外部 URL（可公开访问的 http(s) 链接，≤ 8192 字符）
 * ds.chatStream(MessageContent.of(MessageContent.image("https://example.com/cat.jpg"))).collect { ... }
 *
 * // ③ Files API：上传一次，多请求复用（单图可达 64 MiB）
 * val uploaded = ds.files().upload("cat.jpg", "image/jpeg")
 * ds.chatStream(MessageContent.imageFile(uploaded.id)).collect { ... }
 * ```
 *
 * 图片**只能出现在 user 消息**中；非 user 消息携带图片会在发起请求前抛
 * [IllegalArgumentException]。上传/内联的大小与数量限制见 [MessageContent]。
 *
 * ## 线程模型与并发契约
 *
 * - **单会话语义**：同一时刻最多存在一个活跃流（[chatStream]、[continueStream]、[fimStream] 均参与）；
 *   启动新流、[replaceHistory]、[clearHistory]、[truncateAt] 都会先取消当前活跃流
 *   （[addMessage] 只做追加，不取消任何流）。
 * - **历史访问非线程安全**：[addMessage]、[truncateAt]、[replaceHistory]、[clearHistory] 以及历史
 *   读取（[messages]、[getMessageCount]、[findUserMessageIndex]）都不得与活跃流的收集并发调用；
 *   需要并发访问时由调用方自行串行化（Mutex / 单线程调度器 / 队列）。库内不加锁：这些公共历史
 *   API 均为非 suspend 函数，而 commonMain 没有可用的阻塞锁；且互斥锁无法跨越收集窗口，
 *   解决不了流在自己 `finally` 中回滚历史的竞态。
 * - **所有内部历史写入都发生在收集协程内**：[chatStream] / [continueStream] 在收集期间追加 user
 *   消息、工具调用循环写入 assistant/tool 消息、结束时提交 assistant 回复；失败或取消时按引用
 *   回滚**本轮自己追加**的消息（不会删除其它来源写入的消息），被取消的流不会把本轮消息留在历史里。
 *   Flow 是冷的：重复收集同一个 Flow 会再发起一轮请求、再追加一轮消息。
 * - **历史替换是整体换表而非就地修改**：[replaceHistory]、[clearHistory]、[truncateAt] 走换表路径，
 *   被取消的流只持有替换前的旧表引用，其回滚只作用于旧表，不会覆盖或撤销替换后的新历史。
 *   换表是对历史表引用的单次原子写（[Volatile] 保证可见性），但**内容级并发不受保护**：
 *   并发追加（活跃流的写入或 [addMessage]）期间的读取不保证得到任何曾经存在的历史。
 * - 取消是协作式的：取消标志在每个 chunk 与每轮工具循环前检查，因此取消与 `Done` 的送达之间存在
 *   窗口 —— 调用方在 `Done` 送达过程中取消收集协程时，本轮仍按取消回滚。
 * - [messages] 返回不可变快照（每次读取都会拷贝，轮询请用 [getMessageCount]）；实例本身不实现
 *   `List` / `MutableList`（历史是会话状态而不是容器，需要多态时请在包装层定义自己的窄接口）。
 *
 * @property apiKey DeepSeek API 密钥，从 [DeepSeek 平台](https://platform.deepseek.com) 获取
 * @param model 指定使用的模型；为 `null` 时使用库内硬编码的 [Model.Flash]，不会发起网络请求
 * @property prompt 系统提示词，作为对话历史中的初始 system 消息；为 `null` 时历史以第一条用户消息开始
 * @param sharingPool 客户端共享的 HttpClient 池，默认使用 [DeepseekHttpClientPool.Global]
 * @property config 对话补全控制参数，修改后对后续所有请求生效
 * @param api 使用的 API wire format（[DeepseekApi.STANDARD] 或 [DeepseekApi.RESPONSES]）
 * @param baseUrl API 服务地址（base URL），默认官方地址 `https://api.deepseek.com`；
 *   可指向任意 OpenAI/DeepSeek 兼容的 API 服务供应商，支持带路径前缀（如 `https://host/v1`）；
 *   不支持 userinfo / query / fragment
 *
 * @see [deepseek] 推荐通过 DSL 方式构造
 * @see collectResponse 对流式结果的结构化收集扩展
 * @see ChatConfig
 */
public open class Deepseek(
    public override val apiKey: String,
    model: Model? = null,
    internal val prompt: String? = null,
    public override val config: ChatConfig = ChatConfig(),
    private val api: DeepseekApi = DeepseekApi.STANDARD,
    public val sharingPool: DeepseekHttpClientPool = DeepseekHttpClientPool.Global,
    baseUrl: String = "https://api.deepseek.com",
) : ChatClient {

    /** API 服务地址（归一化：无尾部 `/`，不含 userinfo / query / fragment） */
    public val baseUrl: String = normalizeBaseUrl(baseUrl)

    /** 共享的客户端核心（网络后端、取消机制与流式对话循环） */
    internal var core: DeepseekCore = DeepseekCore(
        apiKey = apiKey,
        model = model,
        prompt = prompt,
        config = config,
        api = api,
        baseUrl = baseUrl,
        sharingPool = sharingPool,
        singleSession = true,
    )

    /**
     * 测试注入内部构造：允许用 Fake 后端/核心构建客户端而不触网。
     * 不改变公开构造签名。
     */
    internal constructor(apiKey: String, core: DeepseekCore) : this(apiKey) {
        this.core = core
        // 历史按注入后的 core 重建（system prompt 来自注入的核心）
        _messages = initialHistory()
    }

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
     *             WeatherResult(city = bag.getString("city"), weather = "晴", temp = 25)
     *         }
     *     }
     * }
     * ```
     */
    public override var toolHost: ToolCallHost?
        get() = core.toolHost
        set(value) {
            core.toolHost = value
        }

    /** 工具执行上下文（用户/会话/权限等元信息），供每次工具调用时使用 */
    public override var executionContext: ToolExecutionContext
        get() = core.executionContext
        set(value) {
            core.executionContext = value
        }

    /** 系统提示词对应的初始 system 消息；未设置 prompt 时为 `null` */
    internal val systemPromptMessage: Message? get() = core.systemPromptMessage

    /**
     * 中断当前正在进行的流（[chatStream]、[continueStream] 或 [fimStream]）。
     *
     * `Deepseek` 是单会话语义：同一时间最多只有一个活跃流，启动新流会先取消旧流；
     * [cancelStream] 取消当前流，级联中止底层 HTTP 请求。
     * 调用方的 `collect` / [collectResponse] 可能抛出 [kotlinx.coroutines.CancellationException]。
     */
    public override fun cancelStream(): Unit = core.cancelStream()

    /**
     * 截断消息历史，仅保留下标 `[0, index]` 的消息（含两端）。
     *
     * 下标按内部历史计算，system prompt 位于 0（若设置了 [prompt]）；`index == lastIndex` 合法，
     * 此时历史内容不变。**任何** `truncateAt` 调用（含 `index == lastIndex` 的空操作）都会先取消
     * 当前活跃流并重新安装历史表，与其他历史操作一致。
     *
     * 越界（`index < 0` 或 `index > lastIndex`）**fail-fast**：抛 [IndexOutOfBoundsException]，
     * 不再像 0.3.0 及更早版本那样静默无操作（调用方以为改了上下文、实际历史原封不动）。
     * 无 prompt 且无消息的空历史 `lastIndex == -1`，因此任何下标都会抛；要清空历史请用
     * [clearHistory]，要整体替换请用 [replaceHistory]，被取消流的回滚不会撤销本次截断。
     *
     * 新的“重新生成”写法推荐使用 [replaceHistory]（IDE 也会按此提示自动替换）：
     *
     * ```kotlin
     * val userIndex = ds.findUserMessageIndex("用一句话介绍你自己")
     * ds.replaceHistory(ds.messages.take(userIndex + 1))
     * ds.continueStream()
     * ```
     *
     * @param index 保留的最后一个消息下标，合法范围 `0..lastIndex`
     * @throws IndexOutOfBoundsException 下标越界时
     */
    @Deprecated(
        message = "越界下标已改为抛 IndexOutOfBoundsException（旧版本会静默无操作）；" +
            "整体替换请用 replaceHistory(...)，清空请用 clearHistory()",
        replaceWith = ReplaceWith("replaceHistory(messages.take(index + 1))"),
    )
    public open fun truncateAt(index: Int) {
        if (index < 0 || index > _messages.lastIndex) {
            throw IndexOutOfBoundsException(
                "truncateAt(index=$index) 越界：当前历史 size=${_messages.size}，" +
                    "合法范围 0..${_messages.lastIndex}；清空历史请用 clearHistory()",
            )
        }
        installHistory(_messages.take(index + 1))
    }

    /** 返回当前消息历史的消息数（含 system prompt） */
    public open fun getMessageCount(): Int = _messages.size

    /**
     * 按内容在消息历史中查找第一条 user 消息的下标，未找到时返回 -1。
     *
     * @param content 要匹配的 user 消息文本
     */
    public open fun findUserMessageIndex(content: String): Int {
        return _messages.indexOfFirst {
            it.role == Role.User && it.content?.asText() == content
        }
    }

    /** 当前使用的模型；未显式指定时固定为库内硬编码的 [Model.Flash] */
    public override val resolvedModel: Model get() = core.resolvedModel

    /**
     * FIM 补全使用的模型，默认 [Model.Flash]。
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
     * 当前历史表。
     *
     * 历史替换/清空采用**整体换表**而非就地修改：被取消的流只持有替换前的旧表引用，
     * 它的 `finally` 回滚就不可能覆盖或撤销替换后的新历史。[Volatile] 保证换表这一次引用写
     * 对其它线程可见；引用替换本身是单次原子写，内容级别的并发仍由调用方串行化
     * （见类文档「线程模型与并发契约」）。
     */
    @Volatile
    private var _messages: MutableList<Message> = initialHistory()

    /** 初始历史：构造期 system prompt（若设置了 `prompt`）作为首条消息 */
    private fun initialHistory(): MutableList<Message> =
        mutableListOf<Message>().apply {
            systemPromptMessage?.let { add(it) }
        }

    /**
     * 当前对话历史（不可变快照），首条为 system prompt（若设置了 [prompt]）。
     *
     * 返回的是调用时的快照：之后实例的追加/替换都不会改变它，内部可变列表也不会暴露给调用方。
     * 快照本身仍是一次对内部表的无锁拷贝，因此并发契约不变：同一线程（含 `collect` 回调内）读取
     * 是安全的，但不得与活跃流的收集并发读取或写入；需要跨线程访问时请在应用层把历史操作与流
     * 收集串行化（见类文档「线程模型与并发契约」）。
     *
     * 每次读取都会拷贝一次（O(n)）：高频轮询请用 [getMessageCount]，需要多次使用同一份历史时
     * 请自行缓存这份快照，不要在每个 chunk 的回调里重复读取。
     *
     * 实例本身不实现 `List` / `MutableList` —— 历史是会话状态而不是容器，
     * 写入请走 [addMessage]、[replaceHistory]、[clearHistory]。
     */
    public open val messages: List<Message> get() = _messages.toList()

    /**
     * 手动向对话历史追加一条消息，追加后参与后续 [chatStream] / [continueStream] 请求。
     *
     * 非线程安全：不得与活跃流的收集并发调用（见类文档「线程模型与并发契约」）。
     *
     * @param message 要追加的消息
     */
    public open fun addMessage(message: Message) {
        _messages.add(message)
    }

    /**
     * 把对话历史整体替换为 [messages]（**精确替换**，不会自动附加构造期 system prompt）。
     *
     * 语义（已由测试固化）：
     * - 非空列表：历史立即变为 [messages]，逐条完全一致；调用方之后修改传入列表不影响实例
     *   （调用时防御性拷贝）。
     * - 空列表：等价于 [clearHistory]，即重置为初始状态 —— 构造期 system prompt 若存在则作为
     *   唯一消息，否则历史为空。
     * - 返回后 [getMessageCount] 与 [messages] 立即与新历史一致。
     * - 若存在活跃流：返回前先取消该流（同「启动新流会先取消旧流」的单会话语义）；被取消流的
     *   回滚只作用于替换前的旧历史，**不会撤销本次替换**。在 `collect` 回调内调用会取消当前
     *   收集协程（`CancellationException`），按常规取消处理即可。
     * - 只做浅拷贝：传入的 [Message] 对象本身（及其 `toolCalls` 列表）按引用持有，替换后请勿再
     *   修改这些对象，否则会改变实例历史与后续请求体。
     *
     * 需要「构造期 system prompt + 自有消息」时，请自行把 system 消息放进列表：
     *
     * ```kotlin
     * // 构造期 prompt 对应的 system 消息可由初始历史取得
     * val system = ds.messages.firstOrNull()?.takeIf { it.role == Role.System }
     * ds.replaceHistory(listOfNotNull(system) + messagesFromDatabase)
     * ```
     *
     * 非线程安全：不得与活跃流的收集并发调用（见类文档「线程模型与并发契约」）。
     *
     * @param messages 新的完整历史（不含构造期 system prompt）
     */
    public open fun replaceHistory(messages: List<Message>) {
        installHistory(if (messages.isEmpty()) initialHistory() else messages)
    }

    /**
     * 清空对话历史，只保留构造期 system prompt（构造 [Deepseek] 时传入的 `prompt`）。
     *
     * 未设置 `prompt` 时历史变为空列表；等价于 `replaceHistory(emptyList())`。
     * 存在活跃流时先取消该流，其回滚不会撤销本次清空（见类文档「线程模型与并发契约」）。
     */
    public open fun clearHistory() {
        replaceHistory(emptyList())
    }

    /** 安装一份新历史：先取消活跃流（单会话语义），再整体换表 */
    private fun installHistory(messages: List<Message>) {
        core.cancelStream()
        _messages = messages.toMutableList()
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
    public override fun chatStream(userContent: String, hook: SseHook?): Flow<ChatChunk> =
        streamContentFlow(MessageContent.of(userContent), hook)

    /**
     * 发起携带内容块（可含图片）的流式对话补全请求。
     *
     * 与 [chatStream]`(userContent: String)` 的唯一区别是输入形态：内容块数组才能携带图片。
     * 追加进历史的消息内容就是传入的 [content]，因此后续 [continueStream] 与工具调用循环
     * 都会带上它（图片只在 user 消息中有效，见 [MessageContent]）。
     *
     * ```kotlin
     * // base64 内联（本地图片，≤ 32 MiB）
     * ds.chatStream(
     *     MessageContent.of(
     *         MessageContent.textPart("这张图片里有什么？"),
     *         MessageContent.imageDataUrl("image/jpeg", jpegBytes),
     *     ),
     * ).collect { ... }
     *
     * // 复用 Files API 上传的图片（推荐：多请求复用、单图可达 64 MiB）
     * val uploaded = ds.files().upload("cat.jpg", "image/jpeg")
     * ds.chatStream(MessageContent.imageFile(uploaded.id)).collect { ... }
     * ```
     *
     * @param content 本条 user 消息的内容（纯文本或内容块数组）
     * @param hook 可选的实时回调，与 Flow 事件一致，先于 Flow 触发
     * @return 流式响应的 [Flow]，发射 [ChatChunk] 事件
     */
    public override fun chatStream(content: MessageContent, hook: SseHook?): Flow<ChatChunk> =
        streamContentFlow(content, hook)

    /**
     * 发起携带内容块（可含图片）的流式对话补全请求的便捷重载。
     *
     * 等价于 `chatStream(MessageContent.of(parts), hook)`。
     *
     * @param parts 本条 user 消息的内容块（至少一个）
     * @param hook 可选的实时回调，与 Flow 事件一致，先于 Flow 触发
     */
    public fun chatStream(parts: List<ContentPart>, hook: SseHook? = null): Flow<ChatChunk> =
        streamContentFlow(MessageContent.of(parts), hook)

    /** [chatStream] 各内容形态重载的唯一实现（private，不占用公开重载的 JVM 名字） */
    private fun streamContentFlow(content: MessageContent, hook: SseHook?): Flow<ChatChunk> =
        core.streamFlow { session ->
            streamLoop(core, _messages, content, hook, session)
        }

    /**
     * 继续流式对话，与 [chatStream] 相同但不追加 user 消息。
     *
     * 用于“重新生成”场景：历史已截断至目标 user 消息，直接基于现有历史继续补全，
     * 避免重复添加用户输入。行为与取消语义同 [chatStream]。
     *
     * @param hook 可选的实时回调，与 Flow 事件一致，先于 Flow 触发
     * @return 流式响应的 [Flow]，发射 [ChatChunk] 事件
     */
    public open fun continueStream(hook: SseHook? = null): Flow<ChatChunk> =
        core.streamFlow { session ->
            streamLoop(core, _messages, null, hook, session)
        }

    /**
     * 发起流式 FIM（Fill In the Middle）补全请求。
     *
     * **Beta**：请求发送到 `/beta/completions` 端点，标注 [ExperimentalDeepseekApi]，
     * 使用时需 `@OptIn(ExperimentalDeepseekApi::class)`；契约可能在后续版本调整。
     *
     * 请求发送到 `{baseUrl}/beta/completions`（默认官方地址
     * `https://api.deepseek.com`）；模型使用
     * [modelForFim]，其余参数复用 [config] 中的 `maxTokens`、`temperature`、`topP`、
     * `stop`、`includeUsage` 与 `topLogprobs`。
     *
     * ```kotlin
     * ds.fimStream(
     *     prompt = "def add(a, b):",
     *     suffix = "    return a + b",
     * ).collect { chunk ->
     *     when (chunk) {
     *         is FimChunk.TextDelta -> print(chunk.text)
     *         is FimChunk.Done -> println("完成: ${chunk.usage.totalTokens} tokens")
     *     }
     * }
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
    public override suspend fun availableModels(): List<Model> = core.availableModels()

    /**
     * 获取当前 API Key 的账户余额信息。
     *
     * @return 账户余额信息
     *
     * @see UserBalance
     */
    public override suspend fun balance(): UserBalance = core.balance()

    /**
     * Files API：上传图片一次，之后在请求里用 `file_id` 反复引用。
     *
     * ```kotlin
     * val uploaded = ds.files().upload("cat.jpg", "image/jpeg")
     * ds.chatStream(MessageContent.imageFile(uploaded.id)).collect { ... }
     * ds.files().delete(uploaded.id)   // 不再需要时删除
     * ```
     *
     * 与 [DeepseekApi] 的选择无关（Files API 是独立端点，STANDARD / RESPONSES 都可用）：
     * 上传的文件用 [MessageContent.imageFile] 或 [ContentPart.FilePart] 引用即可。
     *
     * @return Files API 客户端（同一个实例上多次调用返回同一个对象）
     *
     * @see DeepseekFiles
     */
    public override fun files(): DeepseekFiles = core.filesApi
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
public sealed class ChatChunk : Chunk() {
    /**
     * 内容增量（流式传输的基本单位）。
     *
     * @property content 模型生成的纯文本增量，可能为空字符串
     * @property reasoningContent 思考（推理）内容增量，非空时表示模型正在思考（Beta）
     */
    public data class ContentDelta(
        val content: String,
        val reasoningContent: String? = null,
    ) : ChatChunk()

    /**
     * 工具调用请求（完整的 tool call，流式累积完毕后一次性发射）。
     *
     * @property call 统一的工具调用领域模型
     */
    public data class ToolCallRequest(
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
    public data class ToolResultData(
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
     * @property promptCacheHitTokens 命中上下文缓存的 prompt token 数
     * @property promptCacheMissTokens 未命中上下文缓存的 prompt token 数
     * @property reasoningTokens 推理模型思维链消耗的 token 数
     */
    public data class Done(
        val promptTokens: Long,
        val completionTokens: Long,
        val totalTokens: Long,
        val finishReason: String? = null,
        val promptCacheHitTokens: Long? = null,
        val promptCacheMissTokens: Long? = null,
        val reasoningTokens: Long? = null,
    ) : ChatChunk()
}

/**
 * SSE 流钩子，提供不依赖 [Flow] 的实时回调。
 *
 * 每个外层 Flow 可见的 [Chunk]（[ChatChunk] 或 [FimChunk]）在进入 [Flow] 的
 * emit 前先回调 [onChunk]（与 Flow 事件完全一致）。
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
public fun interface SseHook {
    /**
     * 处理到达的流式事件。
     *
     * @param chunk 公共流式事件，可为 [ChatChunk] 或 [FimChunk]
     */
    public suspend fun onChunk(chunk: Chunk)
}
