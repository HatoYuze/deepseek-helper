package com.github.hatoyuze.protocol.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 推理强度，控制模型在响应前进行推理的程度。
 *
 * 设置为 [HIGH] 时模型可能花费更多 token 和时间进行深度推理。
 *
 * @see ThinkingMode.WithEffort
 */
@Serializable
public enum class ReasoningEffort {
    @SerialName("max") MAX,
    @SerialName("high") HIGH
}

/**
 * 响应格式。
 *
 * ```kotlin
 * val config = ChatConfig()
 * config.responseFormat = ResponseFormat.JSON_OBJECT // 强制输出合法 JSON
 * ```
 */
@Serializable
public enum class ResponseFormat {
    @SerialName("text") TEXT,
    @SerialName("json_object") JSON_OBJECT
}

/**
 * 思考模式，控制模型是否生成推理（思考）内容。
 *
 * ```kotlin
 * val config = ChatConfig()
 * // 关闭思考
 * config.thinkingMode = ThinkingMode.Disabled()
 * // 指定推理强度（或使用 ThinkingMode.Max / ThinkingMode.High 快捷方式）
 * config.thinkingMode = ThinkingMode.High
 * ```
 *
 * 默认 `null` 等同于 [Enabled]，API 不发送 `thinking` 字段。
 */
public sealed class ThinkingMode {
    /** 默认开启思考（API 不发送 `thinking` 字段） */
    public data object Enabled : ThinkingMode()

    /** 关闭思考，API 不返回 `reasoning_content` */
    public data object Disabled : ThinkingMode()

    /** 开启思考并指定推理强度。
     *
     * 可直接使用 [Max]/[High] 快捷方式：
     *
     * ```kotlin
     * config.thinkingMode = ThinkingMode.Max
     * config.thinkingMode = ThinkingMode.High
     * ```
     */
    public data class WithEffort(
        val effort: ReasoningEffort,
    ) : ThinkingMode()

    public companion object {
        /** 最大推理强度，等价于 [WithEffort]`(ReasoningEffort.MAX)` */
        @JvmField
        public val Max: WithEffort = WithEffort(ReasoningEffort.MAX)

        /** 较高推理强度，等价于 [WithEffort]`(ReasoningEffort.HIGH)` */
        @JvmField
        public val High: WithEffort = WithEffort(ReasoningEffort.HIGH)
    }
}

/**
 * 停止词，模型生成到指定词时停止。
 *
 * ```kotlin
 * config.stop = StopToken.Single("END")              // 单个停止词
 * config.stop = StopToken.Multiple(listOf("END", "STOP")) // 多个停止词
 * ```
 */
public sealed interface StopToken {
    /** 单个停止词，序列化为 JSON 字符串 */
    public data class Single(val word: String) : StopToken

    /** 多个停止词，序列化为 JSON 字符串数组 */
    public data class Multiple(val words: List<String>) : StopToken

    public fun toJsonElement(): JsonElement = when (this) {
        is Single -> JsonPrimitive(word)
        is Multiple -> buildJsonArray { words.forEach { add(JsonPrimitive(it)) } }
    }
}

/**
 * 工具调用策略，控制模型是否以及如何调用注册的工具。
 *
 * ```kotlin
 * config.toolChoice = ToolChoice.Auto                            // 模型自行决定（默认）
 * config.toolChoice = ToolChoice.None                            // 不调用工具
 * config.toolChoice = ToolChoice.Required                        // 必须调用工具
 * config.toolChoice = ToolChoice.Named("get_weather")            // 强制调用指定工具
 * ```
 *
 * [None]/[Auto]/[Required] 序列化为对应名称的 JSON 字符串，
 * [Named] 序列化为 `{"type": "function", "function": {"name": "..."}}`。
 */
public sealed interface ToolChoice {
    /** 不调用任何 tool，仅生成消息（无 tool 时的默认值） */
    public data object None : ToolChoice

    /** 模型可选择生成消息或调用 tool（有 tool 时的默认值） */
    public data object Auto : ToolChoice

    /** 模型必须调用一个或多个 tool */
    public data object Required : ToolChoice

    /** 强制模型调用指定名称的 tool */
    public data class Named(val name: String) : ToolChoice

    public fun toJsonElement(): JsonElement = when (this) {
        None -> JsonPrimitive("none")
        Auto -> JsonPrimitive("auto")
        Required -> JsonPrimitive("required")
        is Named -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
            })
        }
    }
}
