package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.net.config
import io.github.hatoyuze.deepseek.toolcall.dsl.ToolHostBuilder
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext

/**
 * 声明式创建并配置 [Deepseek] 实例。
 *
 * 推荐使用此 DSL 替代直接调用 [Deepseek] 构造器，
 * 将模型选择、系统提示词、对话参数、工具注册集中在一处。
 *
 * ```kotlin
 * val ds = deepseek("sk-xxx") {
 *     model { flash() }
 *     prompt = "You are a helpful assistant."
 *
 *     config {
 *         maxTokens = 2048
 *         temperature = 0.7
 *         thinkingMode = ThinkingMode.Disabled
 *         responseFormat = ResponseFormat.JSON_OBJECT
 *     }
 *
 *     tools {
 *         tool("get_weather") {
 *             description = "获取指定城市的天气"
 *             parameters {
 *                 string("city") { required = true }
 *             }
 *             execute { bag, _ ->
 *                 WeatherResult(city = bag.getString("city"), weather = "晴", temp = 25)
 *             }
 *         }
 *         retry(maxAttempts = 3)
 *         logging()
 *     }
 * }
 * ```
 *
 * @param apiKey DeepSeek API 密钥
 * @param block 配置 lambda，接收 [DeepseekBuilder] 作为 receiver
 * @return 配置完成的 [Deepseek] 实例
 *
 * @see DeepseekBuilder
 */
public fun deepseek(apiKey: String, block: DeepseekBuilder.() -> Unit): Deepseek =
    DeepseekBuilder().apply(block).build(apiKey)

/**
 * 使用共享 [ChatConfig] 创建 [Deepseek] 实例的 [deepseek] 变体。
 *
 * 传入的 [sharedConfig] 会直接作为 [Deepseek.config] 实例，
 * 对它的修改对所有共享该实例的 [Deepseek] 可见；其余行为与 [deepseek] 一致。
 *
 * @param apiKey DeepSeek API 密钥
 * @param sharedConfig 共享的对话补全配置实例
 * @param block 配置 lambda，接收 [DeepseekBuilder] 作为 receiver
 * @return 配置完成的 [Deepseek] 实例
 */
public fun deepseek(apiKey: String, sharedConfig: ChatConfig, block: DeepseekBuilder.() -> Unit): Deepseek =
    DeepseekBuilder().apply(block).build(apiKey, sharedConfig)

/**
 * 创建不保存聊天历史的 [StatelessDeepseek] 实例。
 *
 * 配置方式与 [deepseek] 完全一致；区别仅在于每次 [StatelessDeepseek.chatStream]
 * 都是独立的一次性对话，实例上不会累积消息历史。
 *
 * ```kotlin
 * val ds = statelessDeepseek("sk-xxx") {
 *     prompt = "You are a helpful assistant."
 *     config { thinkingMode = ThinkingMode.Max }
 * }
 * ```
 *
 * @param apiKey DeepSeek API 密钥
 * @param block 配置 lambda，接收 [DeepseekBuilder] 作为 receiver
 * @return 配置完成的 [StatelessDeepseek] 实例
 *
 * @see StatelessDeepseek
 */
public fun statelessDeepseek(apiKey: String, block: DeepseekBuilder.() -> Unit): StatelessDeepseek =
    DeepseekBuilder().apply(block).buildStateless(apiKey)

/**
 * 使用共享 [ChatConfig] 创建 [StatelessDeepseek] 实例的 [statelessDeepseek] 变体。
 *
 * 传入的 [sharedConfig] 会直接作为 [StatelessDeepseek.config] 实例，
 * 对它的修改对所有共享该实例的客户端可见；其余行为与 [statelessDeepseek] 一致。
 *
 * @param apiKey DeepSeek API 密钥
 * @param sharedConfig 共享的对话补全配置实例
 * @param block 配置 lambda，接收 [DeepseekBuilder] 作为 receiver
 * @return 配置完成的 [StatelessDeepseek] 实例
 */
public fun statelessDeepseek(apiKey: String, sharedConfig: ChatConfig, block: DeepseekBuilder.() -> Unit): StatelessDeepseek =
    DeepseekBuilder().apply(block).buildStateless(apiKey, sharedConfig)

/**
 * [deepseek] / [statelessDeepseek] DSL 的配置构建器。
 *
 * 配置在顶层工厂函数调用时一次性应用到 [Deepseek] / [StatelessDeepseek] 实例，提供以下配置区域：
 * - **model** — 模型选择（[ModelSelector]）
 * - **prompt** — 系统提示词
 * - **config** — [ChatConfig] 对话参数
 * - **tools** — 工具注册与管道插件（[ToolHostBuilder]）
 */
public class DeepseekBuilder {
    /** 系统 prompt，对应 system role 的初始消息 */
    public var prompt: String? = null

    /** 使用的 API wire format，默认 [DeepseekApi.STANDARD] */
    public var api: DeepseekApi = DeepseekApi.STANDARD

    /** 客户端共享的 HttpClient 池，默认使用 [DeepseekHttpClientPool.Global] */
    public var sharingPool: DeepseekHttpClientPool = DeepseekHttpClientPool.Global

    /** 工具执行上下文，包含用户/会话等元信息 */
    public var executionContext: ToolExecutionContext = ToolExecutionContext("", "")

    private var model: Model? = null
    private var configBlock: (ChatConfig.() -> Unit)? = null
    private var toolHostBuilder: ToolHostBuilder? = null

    /**
     * 指定使用的模型。
     *
     * ```kotlin
     * model { flash() }            // deepseek-v4-flash
     * model { pro() }              // deepseek-v4-pro
     * model { custom("my-model") } // 自定义 model id
     * ```
     *
     * 不调用此方法时，[Deepseek] 使用库内硬编码的 [Model.Flash]（deepseek-v4-flash），不会发起网络请求。
     *
     * @param block 模型选择 DSL，receiver 为 [ModelSelector]
     */
    public fun model(block: ModelSelector.() -> Unit) {
        model = ModelSelector().apply(block).model
    }

    /**
     * 配置对话补全参数。
     *
     * `block` 的 receiver 是 [Deepseek.config] 实例，可直接修改其属性：
     *
     * ```kotlin
     * config {
     *     maxTokens = 4096
     *     temperature = 0.7
     *     thinkingMode = ThinkingMode.Disabled
     * }
     * ```
     *
     * @param block 配置 DSL，receiver 为 [ChatConfig]
     */
    public fun config(block: ChatConfig.() -> Unit) {
        configBlock = block
    }

    /**
     * 配置当前客户端使用的 [DeepseekHttpClientPool]。
     *
     * **IMPORTANT** 当 [sharingPool] 为 [DeepseekHttpClientPool.Global] 时，
     * 本方法会先复制出实例级池再应用 [block]，避免修改全局共享配置影响其他客户端。
     *
     * Example:
     * ```kotlin
     * deepseek("sk-xxx") {
     *     pool {
     *         config {
     *             maxRetries = 2
     *             socketTimeoutMillis = null
     *         }
     *     }
     * }
     * ```
     *
     * @param block 池配置 lambda，receiver 为 [DeepseekHttpClientPool]
     */
    public fun pool(block: DeepseekHttpClientPool.() -> Unit) {
        if (sharingPool === DeepseekHttpClientPool.Global) {
            sharingPool = DeepseekHttpClientPool(sharingPool.config, sharingPool.factory)
        }
        sharingPool.apply(block)
    }

    /**
     * 注册工具并配置管道插件。
     *
     * ```kotlin
     * tools {
     *     tool("search") {
     *         description = "搜索互联网"
     *         parameters { string("q") { required = true } }
     *         execute { bag, _ -> search(bag.getString("q")) }
     *     }
     *     retry(maxAttempts = 3)
     *     timeout(5000)
     *     logging()
     * }
     * ```
     *
     * @param block 工具宿主 DSL，receiver 为 [ToolHostBuilder]
     *
     * @see ToolHostBuilder
     */
    public fun tools(block: ToolHostBuilder.() -> Unit) {
        toolHostBuilder = ToolHostBuilder().apply(block)
    }

    internal fun build(apiKey: String, config: ChatConfig = ChatConfig()): Deepseek {
        return Deepseek(
            apiKey,
            model = model,
            prompt = prompt,
            config = config,
            api = api,
            sharingPool = sharingPool,
        ).applyBuilderState()
    }

    internal fun buildStateless(apiKey: String, config: ChatConfig = ChatConfig()): StatelessDeepseek {
        return StatelessDeepseek(
            apiKey,
            model = model,
            prompt = prompt,
            config = config,
            api = api,
            sharingPool = sharingPool,
        ).applyBuilderState()
    }

    /** 应用 executionContext / config / tools 配置，供 [build] 与 [buildStateless] 共用 */
    private fun <T : ChatClient> T.applyBuilderState(): T {
        executionContext = this@DeepseekBuilder.executionContext
        configBlock?.invoke(config)
        toolHostBuilder?.let { toolHost = it.build() }
        return this
    }
}

/**
 * 在 [DeepseekBuilder.model] DSL 中选择模型。
 *
 * ```kotlin
 * model { flash() }            // deepseek-v4-flash
 * model { pro() }              // deepseek-v4-pro
 * model { custom("my-model") } // 自定义
 * ```
 */
public class ModelSelector {
    internal var model: Model? = null

    /** 使用 deepseek-v4-flash（默认模型，库内硬编码） */
    public fun flash() { model = Model.Flash }

    /** 使用 deepseek-v4-pro（库内硬编码） */
    public fun pro() { model = Model.Pro }

    /**
     * 使用自定义模型 ID。
     *
     * @param id 模型 ID（需为 API 支持的模型）
     */
    public fun custom(id: String) { model = Model("model", "deepseek", id) }
}
