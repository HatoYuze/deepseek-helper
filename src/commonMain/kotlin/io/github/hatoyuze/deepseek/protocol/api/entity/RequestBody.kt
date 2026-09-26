package io.github.hatoyuze.deepseek.protocol.api.entity

import io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 消息角色，对应 DeepSeek API 中消息对象的 `role` 字段。
 *
 * @see Message
 */
@Serializable
public enum class Role {
    @SerialName("system") System,
    @SerialName("user") User,
    @SerialName("assistant") Assistant,
    @SerialName("tool") Tool,
}

/**
 * 对话历史中的一条消息。
 *
 * 根据 [role] 的不同，各字段的有效性如下：
 *
 * | role       | content | toolCallId | toolCalls | reasoningContent |
 * |------------|---------|------------|-----------|------------------|
 * | System     | ✅ 纯文本 | —          | —         | —                |
 * | User       | ✅ 纯文本或内容块（可携带图片） | — | — | —                |
 * | Assistant | null (有 tool_calls 时) / ✅ 纯文本 (纯文本时) | — | ✅ | ✅ (Beta) |
 * | Tool       | ✅ 纯文本 | ✅         | —         | —                |
 *
 * - [content] 为 `null` 表示没有内容（assistant 携带 `tool_calls` 时即如此）；
 *   读取纯文本请用 [MessageContent.asText]，不要强制转换
 * - 图片（[ContentPart.ImagePart] / [ContentPart.FilePart]）**只能出现在 user 消息中**：
 *   官方规定 system / assistant 消息携带图片会被服务端以 `400` 拒绝，库在请求装配前
 *   会先抛出 [IllegalArgumentException]（fail-fast）
 * - [toolCallId] 仅在 role 为 [Role.Tool] 时有效
 * - [toolCalls] 仅在 role 为 [Role.Assistant] 且模型请求工具调用时有效
 * - [reasoningContent] 为 Beta 特性，需要启用 [io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi]。
 *   官方规则（Thinking Mode 文档）：请求**带 `tools`** 时，历史里**所有轮次**的 `reasoning_content`
 *   都必须完整回传（含没有发生工具调用的轮次），缺任意一轮 API 直接返回 `400`
 *   （"The `reasoning_content` in the thinking mode must be passed back to the API"）；
 *   请求不带 `tools` 时服务端忽略该字段。因此历史由调用方自行构造（`replaceHistory`、无状态调用、
 *   持久化日志回放）时，只要配了工具，就必须保留模型产出的 `reasoningContent`；模型未思考的轮次
 *   保持 `null`，不要写空串。注意 `buildDeepseekMessages` 的 `says` 不携带该字段，重建带工具的
 *   assistant 消息请改用 `add(Message(..., reasoningContent = …))`
 *
 * ```kotlin
 * // 纯文本：String 会隐式转换为 MessageContent.Text
 * val plain = Message(Role.User, MessageContent.of("你好"))
 *
 * // 带图片：内容块数组
 * val withImage = Message(
 *     role = Role.User,
 *     content = MessageContent.of(
 *         MessageContent.textPart("这张图片里有什么？"),
 *         MessageContent.imageDataUrl("image/jpeg", jpegBytes),
 *     ),
 * )
 * ```
 *
 * @property content 消息内容（纯文本或可携带图片的内容块数组），`null` 表示无内容
 *
 * @see Role
 * @see MessageContent
 * @see ToolCall
 */
@Serializable
public data class Message(
    val role: Role,
    val content: MessageContent?,
    val name: String? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @property:ExperimentalDeepseekApi
    val prefix: Boolean? = null,
    @property:ExperimentalDeepseekApi
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
)
