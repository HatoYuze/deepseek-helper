package io.github.hatoyuze.deepseek.toolcall.executor

import io.github.hatoyuze.deepseek.toolcall.serializer.ArgumentDeserializationException
import io.github.hatoyuze.deepseek.toolcall.serializer.ArgumentSerializer
import kotlin.reflect.KClass

/**
 * [TypedToolExecutor] 的轻量实现：通过构造参数指定 `parameterType`、`serializer` 和 `handler`。
 *
 * ```kotlin
 * val executor = SimpleTypedToolExecutor(
 *     parameterType = WeatherArgs::class,
 *     serializer = ArgumentSerializer.kotlinx<WeatherArgs>(),
 * ) { params, ctx ->
 *     ToolResult.success(ctx.call.id, "Weather: ${params.city}")
 * }
 * ```
 */
public class SimpleTypedToolExecutor<T : Any>(
    override val parameterType: KClass<T>,
    override val serializer: ArgumentSerializer<T>,
    private val handler: suspend (params: T, ctx: ToolExecutionContext) -> ToolResult,
) : TypedToolExecutor<T>() {

    override suspend fun executeTyped(params: T, ctx: ToolExecutionContext): ToolResult =
        handler(params, ctx)

    /**
     * 在无管道支持时也能独立工作。
     *
     * 若已通过 [ToolCallContext.typedParams] 传入了预解析参数，则直接使用；
     * 否则使用自身的 [serializer] 反序列化。
     */
    override suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult {
        val params = try {
            @Suppress("UNCHECKED_CAST")
            (ctx as? ToolCallContext)?.typedParams as? T
                ?: serializer.deserialize(call.arguments)
        } catch (e: ArgumentDeserializationException) {
            return ToolResult.error(call.id, "Parameter deserialization failed: ${e.message}")
        }
        return executeTyped(params, ctx)
    }
}
