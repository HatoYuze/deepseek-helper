package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Usage
import kotlinx.coroutines.flow.Flow

/**
 * FIM 补全请求的流式事件。
 *
 * 一次 [Deepseek.fimStream] 调用会依次发射 [TextDelta] 文本增量，并以 [Done] 收尾。
 *
 * **Beta**：FIM 走 `/beta/completions` 端点，标注 [ExperimentalDeepseekApi]，
 * 使用时需 `@OptIn(ExperimentalDeepseekApi::class)`；契约可能在后续版本调整。
 *
 * @see Deepseek.fimStream
 * @see StatelessDeepseek.fimStream
 */
@ExperimentalDeepseekApi
public sealed class FimChunk : Chunk() {
    /**
     * 文本增量，对应 FIM API 流式响应中 `choices[0].text` 的分片。
     *
     * @property text 模型生成的文本增量
     */
    public data class TextDelta(
        val text: String,
    ) : FimChunk()

    /**
     * 流结束事件，携带本次请求的 token 用量。
     *
     * @property usage token 用量统计
     * @property finishReason 结束原因（如 `stop`、`length`），可能为 `null`
     */
    public data class Done(
        val usage: Usage,
        val finishReason: String? = null,
    ) : FimChunk()
}

/**
 * FIM 流的聚合结果。
 *
 * 由 [collectFimResponse] 返回，包含累积文本与最终用量。
 * **Beta**：见 [FimChunk]。
 *
 * @property text 累积的补全文本
 * @property usage token 用量统计
 * @property finishReason 结束原因，可能为 `null`
 */
@ExperimentalDeepseekApi
public data class FimResponse(
    val text: String,
    val usage: Usage,
    val finishReason: String? = null,
)

/**
 * 收集整个 [Flow]<[FimChunk]> 直到 [FimChunk.Done]，聚合为 [FimResponse]。
 *
 * 内部实现：累积所有 [FimChunk.TextDelta] 的文本，并取最后一个 [FimChunk.Done]
 * 的用量与结束原因；若流未发射 [FimChunk.Done]，[FimResponse.usage] 回退为全 0。
 *
 * ```kotlin
 * val response = ds.fimStream("def add(a, b):").collectFimResponse()
 * println(response.text)
 * println(response.usage.totalTokens)
 * ```
 *
 * @return 聚合后的完整响应
 *
 * @see FimChunk
 */
@ExperimentalDeepseekApi
public suspend fun Flow<FimChunk>.collectFimResponse(): FimResponse {
    val textBuilder = StringBuilder()
    var usage = Usage(0, 0, 0)
    var finishReason: String? = null

    collect { chunk ->
        when (chunk) {
            is FimChunk.TextDelta -> textBuilder.append(chunk.text)
            is FimChunk.Done -> {
                usage = chunk.usage
                finishReason = chunk.finishReason
            }
        }
    }

    return FimResponse(
        text = textBuilder.toString(),
        usage = usage,
        finishReason = finishReason,
    )
}
