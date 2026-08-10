package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
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
 * @param enableBeta 是否使用 Beta API endpoint
 * @param config 对话补全控制参数
 * @param api 使用的 API wire format
 *
 * @see statelessDeepseek 推荐通过 DSL 方式构造
 */
public class StatelessDeepseek(
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

    override var toolHost: ToolCallHost?
        get() = core.toolHost
        set(value) {
            core.toolHost = value
        }

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

    override val resolvedModel: Model get() = core.resolvedModel

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
    override fun chatStream(userContent: String, hook: SseHook?): Flow<ChatChunk> =
        core.streamFlow {
            val history = mutableListOf<Message>().apply {
                core.systemPromptMessage?.let { add(it) }
            }
            streamLoop(core, history, userContent, hook)
        }

    override fun cancelStream() = core.cancelStream()

    internal suspend fun handleToolCalls(
        pendingToolCalls: List<ChatChunk.ToolCallRequest>,
        history: MutableList<Message>,
    ): List<ToolExecResult> = core.handleToolCalls(pendingToolCalls, history)

    override suspend fun availableModels(): List<Model> = core.availableModels()

    override suspend fun balance(): UserBalance = core.balance()
}
