package com.github.hatoyuze.tool.pipeline.plugins

import com.github.hatoyuze.tool.pipeline.PipelineException
import com.github.hatoyuze.tool.pipeline.ToolCallPhase
import com.github.hatoyuze.tool.pipeline.ToolCallHost
import kotlinx.coroutines.withTimeout

/**
 * 超时插件：EXECUTE 阶段超时则抛出 [PipelineException]。
 *
 * ```kotlin
 * val host = toolHost {
 *     tool("slow") { ... }
 *     timeout(5000)  // 5 秒超时
 * }
 * ```
 *
 * @param timeoutMs 超时毫秒数
 */
public class TimeoutPlugin(
    private val timeoutMs: Long,
) {
    /** 安装到指定的 [ToolCallHost] */
    public fun install(host: ToolCallHost) {
        host.intercept(ToolCallPhase.EXECUTE) { ctx ->
            try {
                withTimeout(timeoutMs) {
                    ctx.proceed()
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw PipelineException(
                    "Tool '${ctx.call.name}' execution timed out after ${timeoutMs}ms",
                    e,
                )
            }
        }
    }
}
