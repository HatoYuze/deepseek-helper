package io.github.hatoyuze.deepseek.protocol.api

/**
 * 标记尚处于 Beta 阶段的 DeepSeek API 功能。
 *
 * 使用被标记的 API 时，编译器会要求显式添加 `@OptIn(ExperimentalDeepseekApi::class)`：
 *
 * ```kotlin
 * @OptIn(ExperimentalDeepseekApi::class)
 * val msg = Message(
 *     role = Role.Assistance,
 *     content = null,
 *     reasoningContent = "...", // Beta 字段
 * )
 * ```
 *
 * @see io.github.hatoyuze.deepseek.protocol.api.entity.Message.reasoningContent
 * @see io.github.hatoyuze.deepseek.protocol.api.entity.Message.prefix
 */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
public annotation class ExperimentalDeepseekApi
