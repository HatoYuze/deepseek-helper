package io.github.hatoyuze.deepseek.toolcall.pipeline.plugins

import io.github.hatoyuze.deepseek.toolcall.pipeline.PipelineException
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallPhase
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
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
 * 退避延迟按指数增长：第 n 次重试前等待 `baseDelayMs × backoffMultiplier^(n-1)` 毫秒。
 * 洋葱模型下，`retry` 重试的是其后整条 EXECUTE 链：声明在 `retry` 之前的插件
 * （如 `timeout`）对每次尝试各自生效，声明在其之后的插件则包裹整轮重试。
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
            val nextIndex = ctx.interceptorIndex
            var backoff = baseDelayMs
            var lastError: PipelineException? = null
            for (attempt in 1..maxAttempts) {
                try {
                    ctx.interceptorIndex = nextIndex
                    ctx.proceed()
                    return@intercept
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PipelineException) {
                    if (!e.isRetryable) throw e
                    lastError = e
                    if (attempt < maxAttempts) {
                        delay(backoff.milliseconds)
                        backoff *= backoffMultiplier
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
                        delay(backoff.milliseconds)
                        backoff *= backoffMultiplier
                    }
                }
            }
            throw lastError!!
        }
    }
}
