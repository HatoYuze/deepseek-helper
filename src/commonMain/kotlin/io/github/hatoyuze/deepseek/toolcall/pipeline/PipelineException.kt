package io.github.hatoyuze.deepseek.toolcall.pipeline

/**
 * 管道执行过程中抛出的业务异常。
 *
 * 业务代码应抛出此异常而非裸 [RuntimeException]，
 * 以便管道的 [RetryPlugin] 等组件根据 [isRetryable] 决定重试策略。
 *
 * @param message 错误描述
 * @param cause 原始异常
 * @param isRetryable 是否可重试，默认 `true`。设为 `false` 则 [RetryPlugin] 不会重试
 */
public class PipelineException(
    override val message: String,
    override val cause: Throwable? = null,
    public val isRetryable: Boolean = true,
) : Exception(message, cause)
