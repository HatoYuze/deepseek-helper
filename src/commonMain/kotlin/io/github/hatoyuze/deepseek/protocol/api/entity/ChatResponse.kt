package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

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

/**
 * token 用量统计。
 *
 * [promptCacheHitTokens]、[promptCacheMissTokens] 与 [reasoningTokens] 为官方扩展字段；
 * 自定义序列化器负责在 JSON 的 `completion_tokens_details.reasoning_tokens` 与扁平属性
 * [reasoningTokens] 之间转换，不对外暴露嵌套 DTO。
 *
 * @property promptTokens 提示词消耗的 token 数
 * @property completionTokens 补全消耗的 token 数
 * @property totalTokens 总消耗 token 数
 * @property promptCacheHitTokens 命中上下文缓存的 prompt token 数
 * @property promptCacheMissTokens 未命中上下文缓存的 prompt token 数
 * @property reasoningTokens 推理模型思维链消耗的 token 数
 */
@Serializable(with = UsageSerializer::class)
public data class Usage(
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val promptCacheHitTokens: Long? = null,
    val promptCacheMissTokens: Long? = null,
    val reasoningTokens: Long? = null,
)

/** [Usage] 的 JSON 序列化器：扁平读写官方扩展字段。 */
internal object UsageSerializer : KSerializer<Usage> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("Usage") {
        element<Long>("promptTokens")
        element<Long>("completionTokens")
        element<Long>("totalTokens")
        element<Long>("promptCacheHitTokens", isOptional = true)
        element<Long>("promptCacheMissTokens", isOptional = true)
        element<Long>("reasoningTokens", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): Usage {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("Usage 仅支持 JSON 反序列化")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        return Usage(
            promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            completionTokens = obj["completion_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            totalTokens = obj["total_tokens"]?.jsonPrimitive?.longOrNull ?: 0L,
            promptCacheHitTokens = obj["prompt_cache_hit_tokens"]?.jsonPrimitive?.longOrNull,
            promptCacheMissTokens = obj["prompt_cache_miss_tokens"]?.jsonPrimitive?.longOrNull,
            reasoningTokens = obj["completion_tokens_details"]
                ?.jsonObject
                ?.get("reasoning_tokens")
                ?.jsonPrimitive
                ?.longOrNull,
        )
    }

    override fun serialize(encoder: Encoder, value: Usage) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("Usage 仅支持 JSON 序列化")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("prompt_tokens", JsonPrimitive(value.promptTokens))
                put("completion_tokens", JsonPrimitive(value.completionTokens))
                put("total_tokens", JsonPrimitive(value.totalTokens))
                value.promptCacheHitTokens?.let {
                    put("prompt_cache_hit_tokens", JsonPrimitive(it))
                }
                value.promptCacheMissTokens?.let {
                    put("prompt_cache_miss_tokens", JsonPrimitive(it))
                }
                value.reasoningTokens?.let {
                    put(
                        "completion_tokens_details",
                        buildJsonObject {
                            put("reasoning_tokens", JsonPrimitive(it))
                        },
                    )
                }
            },
        )
    }
}

@Serializable
internal enum class FinishReason {
    @SerialName("stop") STOP,
    @SerialName("length") LENGTH,
    @SerialName("content_filter") CONTENT_FILTER,
    @SerialName("tool_calls") TOOL_CALLS,
    @SerialName("insufficient_system_resource") INSUFFICIENT_SYSTEM_RESOURCE,
}
