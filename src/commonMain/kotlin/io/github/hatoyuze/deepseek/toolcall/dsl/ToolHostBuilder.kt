package io.github.hatoyuze.deepseek.toolcall.dsl

import io.github.hatoyuze.deepseek.toolcall.ToolHandler
import io.github.hatoyuze.deepseek.toolcall.executor.ParameterBag
import io.github.hatoyuze.deepseek.toolcall.executor.ParameterBagSerializer
import io.github.hatoyuze.deepseek.toolcall.executor.SchemaDrivenExecutor
import io.github.hatoyuze.deepseek.toolcall.executor.PropertyDef
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutor
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.pipeline.plugins.LoggingPlugin
import io.github.hatoyuze.deepseek.toolcall.pipeline.plugins.RetryPlugin
import io.github.hatoyuze.deepseek.toolcall.pipeline.plugins.TimeoutPlugin
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 声明式构建一个 [ToolCallHost]。
 *
 * ```kotlin
 * val host = toolHost {
 *     tool("get_weather") {
 *         description = "获取指定城市的天气"
 *         parameters {
 *             string("city") { required = true }
 *         }
 *         execute { bag, _ ->
 *             WeatherResult(city = bag["city"] as String, weather = "晴", temp = 25)
 *         }
 *     }
 *     retry(maxAttempts = 3)
 *     timeout(5000)
 * }
 * ```
 *
 * @see ToolHostBuilder
 */
public fun toolHost(block: ToolHostBuilder.() -> Unit): ToolCallHost =
    ToolHostBuilder().apply(block).build()

/**
 * 工具宿主构建器，支持注册工具、安装管道插件。
 *
 * ### 注册工具
 *
 * ```kotlin
 * tool("名称") {
 *     description = "工具描述"
 *     parameters { ... }
 *     execute { bag, ctx -> ... }
 * }
 * ```
 *
 * ### 管道插件
 *
 * 可通过 `retry`、`timeout`、`logging` 等方法安装管道插件。
 * 插件按声明顺序应用到最终的 [ToolCallHost]。
 *
 * @see toolHost
 * @see DeepseekBuilder.tools
 */
public class ToolHostBuilder {
    private val registry = ToolRegistry()
    private val plugins = mutableListOf<(ToolCallHost) -> Unit>()

    /**
     * 注册一个工具：schema + executor 一体定义。
     *
     * @param name 工具名称，需与模型识别名称一致
     * @param block [ToolBuilder] 配置 lambda
     */
    public fun tool(name: String, block: ToolBuilder.() -> Unit) {
        val builder = ToolBuilder(name).apply(block)
        registry.register(builder.definition, builder.executor)
    }

    /** 直接注册 [ToolHandler] 子类实例 */
    public fun register(handler: ToolHandler) {
        registry.register(handler.definition, handler)
    }

    /**
     * 安装重试插件：EXECUTE 阶段失败时自动重试。
     *
     * @param maxAttempts 最大尝试次数（含首次），默认 `3`
     * @param baseDelayMs 首次重试前等待毫秒数，默认 `500`
     * @param backoffMultiplier 退避倍数，默认 `2`（指数退避）
     */
    public fun retry(maxAttempts: Int = 3, baseDelayMs: Long = 500L, backoffMultiplier: Long = 2L) {
        plugins.add { host -> RetryPlugin(maxAttempts, baseDelayMs, backoffMultiplier).install(host) }
    }

    /**
     * 安装超时插件：EXECUTE 阶段超时则返回错误。
     *
     * @param timeoutMs 超时毫秒数
     */
    public fun timeout(timeoutMs: Long) {
        plugins.add { host -> TimeoutPlugin(timeoutMs).install(host) }
    }

    /** 安装日志插件：记录各 phase 的执行耗时 */
    public fun logging() {
        plugins.add { host -> LoggingPlugin.install(host) }
    }

    @PublishedApi
    internal fun build(): ToolCallHost {
        val host = ToolCallHost(registry)
        plugins.forEach { it(host) }
        return host
    }
}

/**
 * 单个工具的 DSL 构建器。
 *
 * ```kotlin
 * ToolBuilder("search").apply {
 *     description = "搜索互联网"
 *     parameters {
 *         string("q") { required = true }
 *     }
 *     execute { bag, _ ->
 *         searchResults(bag["q"] as String)
 *     }
 * }
 * ```
 */
public class ToolBuilder(private val name: String) {
    /** 工具描述，会传递给模型 */
    public var description: String = ""

    /** 是否启用严格模式（schema 校验） */
    public var strict: Boolean = false

    @PublishedApi internal var paramsBlock: (ParametersBuilder.() -> Unit)? = null
    @PublishedApi internal var handlerBlock: (suspend (ParameterBag, ToolExecutionContext) -> String)? = null

    /**
     * 定义工具的参数 schema。
     *
     * ```kotlin
     * parameters {
     *     string("city") { description = "城市名"; required = true }
     *     integer("limit") { description = "返回数量上限"; minimum = 1 }
     * }
     * ```
     *
     * @see ParametersBuilder
     */
    public fun parameters(block: ParametersBuilder.() -> Unit) {
        paramsBlock = block
    }

    /**
     * 定义工具的执行逻辑，返回 `@Serializable` 类型 `T`。
     *
     * 框架自动通过 [kotlinx.serialization.serializer] 将返回值序列化为 JSON 字符串。
     * 设计思路仿照 Ktor 的 `call.respond(body)`。
     *
     * ```kotlin
     * @Serializable
     * data class WeatherResult(val city: String, val weather: String)
     *
     * execute { bag, _ ->
     *     WeatherResult(city = bag["city"] as String, weather = "晴")
     * }
     * ```
     *
     * @param block 执行体，接收 [ParameterBag] 和 [ToolExecutionContext]，返回任意 `@Serializable` 类型
     */
    public inline fun <reified T> execute(
        noinline block: suspend (ParameterBag, ToolExecutionContext) -> T,
    ) {
        val json = Json { ignoreUnknownKeys = true }
        val resultSerializer = serializer<T>()
        handlerBlock = { bag, ctx ->
            val result = block(bag, ctx)
            json.encodeToString(resultSerializer, result)
        }
    }

    internal val definition: ToolDefinition
        get() {
            val schema = paramsBlock?.let { ParametersBuilder().apply(it).build() }
                ?: PropertyDef.ObjectDef(emptyMap())
            return ToolDefinition.from(name, description, strict, schema)
        }

    internal val executor: ToolExecutor
        get() {
            val schema = paramsBlock?.let { ParametersBuilder().apply(it).build() }
                ?: PropertyDef.ObjectDef(emptyMap())
            val handler = handlerBlock
                ?: throw IllegalStateException("execute { } block is required for tool '$name'")
            return SchemaDrivenExecutor(schema, ParameterBagSerializer(schema), handler)
        }
}
