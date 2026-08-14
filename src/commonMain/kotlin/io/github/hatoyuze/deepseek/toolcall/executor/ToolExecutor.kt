package io.github.hatoyuze.deepseek.toolcall.executor

import io.github.hatoyuze.deepseek.toolcall.serializer.ArgumentDeserializationException
import io.github.hatoyuze.deepseek.toolcall.serializer.ArgumentSerializer
import kotlinx.coroutines.CancellationException
import kotlin.reflect.KClass

/**
 * 工具执行器的基接口。
 *
 * 所有执行器必须实现此接口，框架通过此接口调度执行。
 */
public interface ToolExecutor {
    /**
     * 执行工具调用，返回结果。
     *
     * @param call 模型返回的原始 tool_call
     * @param ctx 执行上下文，包含用户/会话等信息
     * @return [ToolResult] 执行结果，将回填给模型
     */
    public suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult
}

/**
 * 类型化工具执行器，期望接收特定类型 [T] 的参数。
 *
 * 实现此接口的执行器可被框架自动反序列化参数，无需手动解析 JSON。
 *
 * ```kotlin
 * class WeatherExecutor : TypedToolExecutor<WeatherArgs>() {
 *     override val parameterType = WeatherArgs::class
 *
 *     override suspend fun executeTyped(params: WeatherArgs, ctx: ToolExecutionContext): ToolResult {
 *         return ToolResult.success(...)
 *     }
 * }
 * ```
 *
 * @param T 参数数据类类型
 */
public abstract class TypedToolExecutor<T : Any> : ToolExecutor {

    /** 参数类型，用于反射或序列化器查找 */
    public abstract val parameterType: KClass<T>

    /**
     * 可选的自定义序列化器。
     *
     * 若返回 `null`，则默认实现会通过 [ToolCallContext] 中预解析的 `typedParams` 获取参数；
     * 否则使用该序列化器对 [ToolCall.arguments] 进行反序列化。
     *
     * 推荐在子类中提供具体实例，如 `ArgumentSerializer.kotlinx()`。
     */
    public open val serializer: ArgumentSerializer<T>? = null

    /**
     * 执行类型化参数的业务逻辑。
     *
     * 在默认的 [execute] 中，参数已准备好（通过反序列化或从上下文获取）。
     */
    public abstract suspend fun executeTyped(params: T, ctx: ToolExecutionContext): ToolResult

    /**
     * [ToolExecutor] 的统一入口，实现反序列化委托。
     *
     * 可重写此方法以自定义反序列化流程（如添加缓存、特殊校验）。
     */
    override suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult {
        return resolveParams(call, ctx).fold(
            onSuccess = { params -> executeTyped(params, ctx) },
            onFailure = { exception ->
                ToolResult.error(call.id, "Parameter error: ${exception.message}")
            },
        )
    }

    /**
     * 参数解析逻辑，独立为 `open` 方法以便重写。
     *
     * 默认策略：优先使用上下文预解析的 [ToolCallContext.typedParams]（若存在且类型匹配），
     * 否则回退到 [serializer] 反序列化。
     */
    protected open suspend fun resolveParams(call: ToolCall, ctx: ToolExecutionContext): Result<T> {
        if (ctx is ToolCallContext) {
            @Suppress("UNCHECKED_CAST")
            val preParsed = ctx.typedParams as? T
            if (preParsed != null) {
                return Result.success(preParsed)
            }
        }

        val activeSerializer = serializer
            ?: return Result.failure(IllegalStateException(
                "No serializer configured for ${parameterType.simpleName}. " +
                    "Provide a serializer in your TypedToolExecutor or rely on pipeline pre-parsing.",
            ))

        return try {
            val params = activeSerializer.deserialize(call.arguments)
            Result.success(params)
        } catch (e: ArgumentDeserializationException) {
            Result.failure(e)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(ArgumentDeserializationException(call.arguments, e.message ?: "", e))
        }
    }
}
