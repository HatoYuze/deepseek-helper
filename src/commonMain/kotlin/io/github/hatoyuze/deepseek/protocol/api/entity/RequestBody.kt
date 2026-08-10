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
    @SerialName("assistant") Assistance,
    @SerialName("tool") Tool,
}

/**
 * 对话历史中的一条消息。
 *
 * 根据 [role] 的不同，各字段的有效性如下：
 *
 * | role       | content | toolCallId | toolCalls | reasoningContent |
 * |------------|---------|------------|-----------|------------------|
 * | System     | ✅      | —          | —         | —                |
 * | User       | ✅      | —          | —         | —                |
 * | Assistance | null (有 tool_calls 时) / ✅ (纯文本时) | — | ✅ | ✅ (Beta) |
 * | Tool       | ✅      | ✅         | —         | —                |
 *
 * - [content] 在 assistant 携带 `tool_calls` 时为 `null`
 * - [toolCallId] 仅在 role 为 [Role.Tool] 时有效
 * - [toolCalls] 仅在 role 为 [Role.Assistance] 且模型请求工具调用时有效
 * - [reasoningContent] 为 Beta 特性，需要启用 [io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi]
 *
 * @see Role
 * @see ToolCall
 */
@Serializable
public data class Message(
    val role: Role,
    val content: String?,
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
