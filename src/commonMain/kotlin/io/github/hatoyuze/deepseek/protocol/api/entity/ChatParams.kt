package io.github.hatoyuze.deepseek.protocol.api.entity

import io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi
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
 * config.thinkingMode = ThinkingMode.Disabled
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
        public val Max: WithEffort = WithEffort(ReasoningEffort.MAX)

        /** 较高推理强度，等价于 [WithEffort]`(ReasoningEffort.HIGH)` */
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
 * 上游把**内部工具调用语法**（信封）当正文下发时的处理策略，配置入口是
 * [io.github.hatoyuze.deepseek.protocol.api.ChatConfig.inlineToolCallPolicy]。
 *
 * 上游偶发把模型的原生工具调用语法当正文下发（`finish_reason=stop`、`tool_calls=null`，
 * 而官方文档从未定义该语法）。默认策略 [RECOVER] 把它恢复成真正的工具调用并走既有工具管道执行，
 * 其余情况一律剔除——机制与取舍见 `README.md` 的「上游工具调用语法泄漏（内部信封）」。
 *
 * ## 这个开关是逃生舱，这一层也是**待退役**的
 *
 * 默认值针对的是上游已知缺陷
 * [deepseek-ai/DeepSeek-V3#1678](https://github.com/deepseek-ai/DeepSeek-V3/issues/1678)（确定性）
 * 与 [#1244](https://github.com/deepseek-ai/DeepSeek-V3/issues/1244)（间歇性）。降级判据是
 * **可证伪的**，不是"以后再说"：
 *
 * 1. 上游 issue 关闭，**且**一个发布周期内 `InlineToolCallRecovery` 的 ERROR 日志零命中
 *    ⇒ 默认值降级为 [STRIP]（只剔除、不执行）；
 * 2. 再一个周期仍零命中 ⇒ 删除恢复执行的代码路径，只保留 [STRIP] 行为——清洗本身留着，
 *    因为它同时覆盖聚合网关的转码形态与历史里已经存在的污染。
 *
 * @see io.github.hatoyuze.deepseek.protocol.api.ChatConfig.inlineToolCallPolicy
 */
@ExperimentalDeepseekApi
public enum class InlineToolCallPolicy {
    /**
     * 默认。正文里能完整解析、且工具名同时通过注册表与 `toolChoice` 白名单的信封 → 恢复成工具调用
     * 并走既有管道执行；其余情况（未注册、未闭合、参数非法、策略不允许）一律剔除并记 `ERROR`。
     */
    RECOVER,

    /**
     * 只剔除、从不执行。
     *
     * 模型这一轮的调用意图会丢失（表现为正文被削掉，极端情况下整轮为空）；适合"宁可少一次工具调用，
     * 也绝不让文本里的调用产生副作用"的场景。
     */
    STRIP,

    /**
     * 完全不干预：信封原样进正文、原样进历史，回放前也不清洗。
     *
     * 只给自行后处理正文的上层使用（例如应用侧已有自己的解析器，或需要逐字保真地展示模型输出）。
     * 选它就意味着**放弃了"内部语法不出现在用户可见内容里"这条保证**。
     */
    PASSTHROUGH,
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
