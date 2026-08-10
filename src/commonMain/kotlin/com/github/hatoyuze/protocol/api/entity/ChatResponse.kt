package com.github.hatoyuze.protocol.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val obj: String,
    val created: Long,
    val model: String,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
    val choices: List<ChoiceDelta> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
internal data class ChoiceDelta(
    val index: Int,
    val delta: DeltaContent,
    @SerialName("finish_reason") val finishReason: FinishReason? = null,
)

@Serializable
internal data class DeltaContent(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val role: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

@Serializable
internal data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: ToolFunctionCall? = null,
)

@Serializable
internal data class ToolFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)

/** token 用量统计 */
@Serializable
public data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Long,
    @SerialName("completion_tokens") val completionTokens: Long,
    @SerialName("total_tokens") val totalTokens: Long,
)

@Serializable
internal enum class FinishReason {
    @SerialName("stop") STOP,
    @SerialName("length") LENGTH,
    @SerialName("content_filter") CONTENT_FILTER,
    @SerialName("tool_calls") TOOL_CALLS,
    @SerialName("insufficient_system_resource") INSUFFICIENT_SYSTEM_RESOURCE,
}
