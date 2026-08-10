package io.github.hatoyuze.deepseek.toolcall.pipeline

import io.github.hatoyuze.deepseek.toolcall.DEEPSEEK_WEB_SEARCH_TOOL
import io.github.hatoyuze.deepseek.toolcall.ToolHandler
import io.github.hatoyuze.deepseek.toolcall.executor.ParameterBag
import io.github.hatoyuze.deepseek.toolcall.executor.ParameterBagSerializer
import io.github.hatoyuze.deepseek.toolcall.executor.SchemaDrivenExecutor
import io.github.hatoyuze.deepseek.toolcall.executor.PropertyDef
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.executor.ToolResult
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 工具调用管道入口，持有 [ToolRegistry] 和 [ToolCallPipeline]。
 *
 * 核心执行拦截器由 [ToolCallPipeline] 自行安装，无需外部干预。
 *
 * 推荐通过 [toolHost] DSL 构建：
 *
 * ```kotlin
 * val host = toolHost {
 *     tool("search") { ... }
 *     retry(maxAttempts = 3)
 * }
 * ```
 *
 * 也可通过构造器 + `register` 编程式创建：
 *
 * ```kotlin
 * val host = ToolCallHost(ToolRegistry())
 * host.register("greet", "Greet someone", schema = schema) { bag, _ ->
 *     "Hello, ${bag.getString("name")}!"
 * }
 * ```
 *
 * @see toolHost
 */
public class ToolCallHost(
    private val registry: ToolRegistry,
) {
    public val pipeline = ToolCallPipeline()

    // ── 工具注册 ──

    /**
     * Schema-driven 注册：schema + lambda，自动生成 [ToolDefinition]。
     *
     * @param name 工具名称
     * @param description 工具描述
     * @param strict 是否启用严格模式
     * @param schema 参数的 JSON Schema
     * @param handler 执行逻辑，返回字符串结果
     */
    public fun register(
        name: String,
        description: String,
        strict: Boolean = false,
        schema: PropertyDef.ObjectDef,
        handler: suspend (ParameterBag, ToolExecutionContext) -> String,
    ) {
        val serializer = ParameterBagSerializer(schema)
        val def = ToolDefinition.from(name, description, strict, schema)
        val executor = SchemaDrivenExecutor(schema, serializer, handler)
        registry.register(def, executor)
    }

    /**
     * 类型安全的注册重载：handler 返回 `@Serializable` 类型 `T`，
     * 框架自动序列化为 JSON 字符串（仿 Ktor `call.respond` 模式）。
     */
    public inline fun <reified T> registerTyped(
        name: String,
        description: String,
        strict: Boolean = false,
        schema: PropertyDef.ObjectDef,
        noinline handler: suspend (ParameterBag, ToolExecutionContext) -> T,
    ) {
        val json = Json { ignoreUnknownKeys = true }
        val resultSerializer = serializer<T>()
        register(name, description, strict, schema) { bag, ctx ->
            val result = handler(bag, ctx)
            json.encodeToString(resultSerializer, result)
        }
    }

    /** 直接注册 [ToolHandler] 实例 */
    public fun register(handler: ToolHandler) {
        registry.register(handler.definition, handler)
    }

    /** 是否已注册任何工具 */
    public fun isEmpty(): Boolean = registry.isEmpty()

    // ── 管道操作 ──

    /**
     * 注册自定义拦截器到指定 phase。
     *
     * 插入到列表头部，使后注册的拦截器先执行（洋葱模型）。
     *
     * @param phase 目标生命周期阶段
     * @param handler 拦截器逻辑，调用 [PipelineContext.proceed] 继续执行
     */
    public fun intercept(phase: ToolCallPhase, handler: suspend (PipelineContext) -> Unit) {
        pipeline.intercept(phase, handler)
    }

    /**
     * 执行一次完整的 tool call pipeline。
     *
     * @param call 模型返回的 tool call
     * @param ctx 执行上下文
     * @return 最终 [ToolResult]
     */
    public suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult {
        // 内置服务端 web search：服务端已执行，跳过注册表查找与管道，直接返回成功
        if (call.name == DEEPSEEK_WEB_SEARCH_TOOL) {
            return ToolResult.success(call.id, "{\"status\":\"completed\"}")
        }
        val executor = registry.getExecutor(call.name)
            ?: return ToolResult.error(call.id, "Unknown function: ${call.name}")
        val pctx = PipelineContext(call, executor, ctx, registry)
        return pipeline.execute(pctx)
    }

    /** 获取所有已注册工具的定义，用于序列化为 API 请求的 `tools` 数组 */
    public fun getDefinitions(): List<ToolDefinition> = registry.getDefinitions()
}
