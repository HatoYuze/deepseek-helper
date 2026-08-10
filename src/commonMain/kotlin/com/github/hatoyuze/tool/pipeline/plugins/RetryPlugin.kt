package com.github.hatoyuze.tool.pipeline.plugins

import com.github.hatoyuze.tool.pipeline.PipelineException
import com.github.hatoyuze.tool.pipeline.ToolCallPhase
import com.github.hatoyuze.tool.pipeline.ToolCallHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * 重试插件：EXECUTE 阶段失败时按退避策略自动重试。
 *
 * 仅当 [PipelineException.isRetryable] 为 `true` 时重试。
 * 非 [PipelineException] 的异常会自动包装为可重试的 [PipelineException]；
 * [kotlinx.coroutines.CancellationException] 会原样向上传播，不会被包装或重试。
 *
 * ```kotlin
 * val host = toolHost {
 *     tool("flaky") { ... }
 *     retry(maxAttempts = 3, baseDelayMs = 500L)
 * }
 * ```
 *
 * @param maxAttempts 最大尝试次数（含首次），默认 `3`
 * @param baseDelayMs 首次重试前等待毫秒数，默认 `500`
 * @param backoffMultiplier 每次退避倍数，默认 `2`（指数退避）
 */
public class RetryPlugin(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 500L,
    private val backoffMultiplier: Long = 2L,
) {
    /** 安装到指定的 [ToolCallHost] */
    public fun install(host: ToolCallHost) {
        host.intercept(ToolCallPhase.EXECUTE) { ctx ->
            val coreIndex = ctx.phaseInterceptors.lastIndex
            var lastError: PipelineException? = null
            for (attempt in 1..maxAttempts) {
                try {
                    ctx.interceptorIndex = coreIndex
                    ctx.proceed()
                    return@intercept
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PipelineException) {
                    if (!e.isRetryable) throw e
                    lastError = e
                    if (attempt < maxAttempts) {
                        delay((baseDelayMs * attempt * backoffMultiplier).milliseconds)
                    }
                } catch (e: Exception) {
                    if (e is IllegalArgumentException) throw PipelineException(
                        e.message ?: "<Illegal argument exception>", e, false,
                    )
                    lastError = PipelineException(
                        "Tool '${ctx.call.name}' failed on attempt $attempt/$maxAttempts: ${e.message}",
                        e,
                        isRetryable = true,
                    )
                    if (attempt < maxAttempts) {
                        delay((baseDelayMs * attempt * backoffMultiplier).milliseconds)
                    }
                }
            }
            throw lastError!!
        }
    }
}
