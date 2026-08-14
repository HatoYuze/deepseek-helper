package io.github.hatoyuze.deepseek.toolcall.executor

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 工具调用类型，对应 API 中 `tool_calls[].type` 字段。
 */
@Serializable
public enum class ToolCallType {
    /** 普通函数调用 */
    @SerialName("function")
    FUNCTION,

    /** 服务端联网搜索调用 */
    @SerialName("web_search_call")
    WEB_SEARCH_CALL,
}

/**
 * 模型返回的单个工具调用，是整个库中工具调用的统一领域模型。
 *
 * 序列化时由 [ToolCallSerializer] 输出 API 要求的 `{id, type, function:{name, arguments}}` 嵌套结构。
 *
 * @property id 工具调用 ID
 * @property name 函数名称，用于路由到对应的 [ToolExecutor]
 * @property arguments 原始 JSON 参数字符串，如 `{"location": "Hangzhou"}`
 * @property type 工具调用类型，默认 [ToolCallType.FUNCTION]
 */
@Serializable(with = ToolCallSerializer::class)
public data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val type: ToolCallType = ToolCallType.FUNCTION,
)

/**
 * [ToolCall] 的 JSON 序列化器，输出 DeepSeek API 要求的嵌套结构。
 *
 * ```json
 * {"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\"city\":\"HZ\"}"}}
 * ```
 */
public object ToolCallSerializer : KSerializer<ToolCall> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ToolCall")

    override fun serialize(encoder: Encoder, value: ToolCall) {
        val element = buildJsonObject {
            put("id", value.id)
            put("type", typeName(value.type))
            put("function", buildJsonObject {
                put("name", value.name)
                put("arguments", value.arguments)
            })
        }
        encoder.encodeSerializableValue(JsonElement.serializer(), element)
    }

    override fun deserialize(decoder: Decoder): ToolCall {
        val element = decoder.decodeSerializableValue(JsonElement.serializer())
        val obj = element.jsonObject
        val function = obj["function"] as? JsonObject
        return ToolCall(
            id = obj["id"]?.jsonPrimitive?.content ?: "",
            name = function?.get("name")?.jsonPrimitive?.content ?: "",
            arguments = function?.get("arguments")?.jsonPrimitive?.content ?: "",
            type = when (obj["type"]?.jsonPrimitive?.content) {
                "web_search_call" -> ToolCallType.WEB_SEARCH_CALL
                else -> ToolCallType.FUNCTION
            },
        )
    }

    private fun typeName(type: ToolCallType): String = when (type) {
        ToolCallType.FUNCTION -> "function"
        ToolCallType.WEB_SEARCH_CALL -> "web_search_call"
    }
}

/**
 * 工具执行上下文，携带单次工具调用的请求级元信息。
 *
 * 可用于日志、审计或自定义插件扩展。
 *
 * @param userId 用户标识
 * @param sessionId 会话标识
 * @param permissions 权限集合
 */
@Serializable
public open class ToolExecutionContext(
    public val userId: String,
    public val sessionId: String,
    public val permissions: Set<String> = emptySet(),
)

/**
 * 扩展的执行上下文，由管道在调用前填充预解析的类型化参数。
 *
 * @param typedParams 经反序列化的强类型参数对象，执行器可直接使用
 */
public class ToolCallContext(
    userId: String,
    sessionId: String,
    permissions: Set<String> = emptySet(),
    public val typedParams: Any? = null,
) : ToolExecutionContext(userId, sessionId, permissions) {
    public companion object {
        /** 将基础上下文包装为携带预解析参数的类型化上下文 */
        public fun from(ctx: ToolExecutionContext, params: Any?): ToolCallContext =
            ToolCallContext(ctx.userId, ctx.sessionId, ctx.permissions, params)
    }
}

/**
 * 工具执行结果，对应发回模型的 `role: "tool"` 消息。
 *
 * @param toolCallId 关联的 tool call ID
 * @param content 结果文本，可以是纯文本或 JSON 字符串
 * @param isError 是否为错误结果，模型可根据此标志调整回复语气
 */
@Serializable
public data class ToolResult(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
) {
    public companion object {
        /** 创建成功结果 */
        public fun success(toolCallId: String, content: String): ToolResult =
            ToolResult(toolCallId, content)

        /** 创建错误结果 */
        public fun error(toolCallId: String, errorMessage: String): ToolResult =
            ToolResult(toolCallId, errorMessage, isError = true)
    }
}
