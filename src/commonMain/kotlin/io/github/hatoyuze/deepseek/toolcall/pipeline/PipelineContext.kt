package io.github.hatoyuze.deepseek.toolcall.pipeline

import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutor
import io.github.hatoyuze.deepseek.toolcall.executor.ToolResult
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry

/**
 * 单次 tool call 的管道上下文，贯穿所有 [ToolCallPhase]。
 *
 * @param call 当前 tool call
 * @param executor 匹配到的执行器
 * @param executionContext 用户/会话等元信息
 * @param registry 工具注册中心
 */
public class PipelineContext(
    public val call: ToolCall,
    public val executor: ToolExecutor,
    public val executionContext: ToolExecutionContext,
    public val registry: ToolRegistry,
) {
    /** 最终执行结果，在管道执行完毕后设置 */
    public var result: ToolResult? = null

    /** 管道捕获的异常，进入 [ToolCallPhase.ERROR] 阶段前设置 */
    public var error: Throwable? = null

    /** 经反序列化的类型化参数，在 [ToolCallPhase.TRANSFORM] 阶段后可用 */
    public var typedParams: Any? = null

    /** 当前所处 phase */
    public var currentPhase: ToolCallPhase = ToolCallPhase.VALIDATE

    @PublishedApi
    internal var phaseIndex: Int = 0

    @PublishedApi
    internal var interceptorIndex: Int = 0

    @PublishedApi
    internal lateinit var phaseInterceptors: List<suspend (PipelineContext) -> Unit>

    public companion object {
        internal val ORDERED_PHASES: List<ToolCallPhase> = ToolCallPhase.entries.toList()
    }

    /**
     * 推进到当前 phase 的下一个拦截器。
     *
     * 若当前 phase 内所有拦截器已执行完毕，则自动进入下一个 phase。
     */
    public suspend fun proceed() {
        val interceptors = phaseInterceptors
        if (interceptorIndex < interceptors.size) {
            val idx = interceptorIndex
            interceptorIndex++
            interceptors[idx](this)
        }
    }
}
