package io.github.hatoyuze.deepseek.protocol.api

/**
 * DeepSeek API 的接入格式（wire format）。
 *
 * 目前支持两种格式：
 * - [STANDARD]：DeepSeek 原生 Chat Completions 格式（`/chat/completions`），默认值
 * - [RESPONSES]：OpenAI Responses API 兼容格式（`/responses`），
 *   当前仅支持 `deepseek-v4-flash` 模型；服务端联网搜索（[ChatConfig.enableWebSearch]）仅在该格式下生效
 *
 * ```kotlin
 * val ds = Deepseek("sk-xxx", api = DeepseekApi.RESPONSES)
 * // 或通过 DSL：
 * val ds2 = deepseek("sk-xxx") { api = DeepseekApi.RESPONSES }
 * ```
 */
public sealed class DeepseekApi {
    /** DeepSeek 原生 API（`/chat/completions`）。 */
    public companion object STANDARD : DeepseekApi()

    /** OpenAI Responses 兼容格式（`/responses`）。 */
    public data object RESPONSES : DeepseekApi()
}
