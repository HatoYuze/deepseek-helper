package io.github.hatoyuze.deepseek.protocol.api.impl

import io.github.hatoyuze.deepseek.protocol.api.ChatConfig
import io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi
import io.github.hatoyuze.deepseek.protocol.api.FimChunk
import io.github.hatoyuze.deepseek.protocol.api.entity.FinishReason
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.entity.Usage
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.net.Network
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * FIM 补全请求体，字段与官方 `POST /beta/completions` 契约一致。
 *
 * @property model 模型 ID
 * @property prompt 补全提示（前缀）
 * @property suffix 被补全内容的后缀，可为 `null`
 * @property echo 是否在输出中回显 prompt，可为 `null`
 * @property maxTokens 最大生成 token 数
 * @property temperature 采样温度
 * @property topP 核采样参数
 * @property stop 停止词
 * @property logprobs 输出 token 对数概率数量
 * @property stream 是否启用流式输出
 * @property streamOptions 流式输出选项
 */
@Serializable
internal data class FimRequest(
    val model: String,
    val prompt: String,
    val suffix: String? = null,
    val echo: Boolean? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stop: JsonElement? = null,
    val logprobs: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stream: Boolean = true,
    @SerialName("stream_options") val streamOptions: FimStreamOptions? = null,
)

@Serializable
internal data class FimStreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean = false,
)

/**
 * 从 [ChatConfig] 与调用参数装配 FIM 请求。
 *
 * 复用的配置项：`maxTokens`、`temperature`、`topP`、`stop`、`includeUsage`；
 * FIM 的整数 `logprobs` 取 [ChatConfig.topLogprobs]。
 */
internal fun buildFimRequest(
    prompt: String,
    suffix: String?,
    echo: Boolean?,
    model: Model,
    config: ChatConfig,
): FimRequest = FimRequest(
    model = model.id,
    prompt = prompt,
    suffix = suffix,
    echo = echo,
    maxTokens = config.maxTokens,
    temperature = config.temperature,
    topP = config.topP,
    stop = config.stop?.toJsonElement(),
    logprobs = config.topLogprobs,
    streamOptions = if (config.includeUsage) FimStreamOptions(includeUsage = true) else null,
)

/**
 * FIM 流式响应块，遵循 OpenAI legacy completions 流式形状。
 */
@Serializable
internal data class FimCompletionChunk(
    val id: String = "",
    @SerialName("object") val obj: String = "",
    val created: Long = 0,
    val model: String = "",
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
    val choices: List<FimCompletionChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
internal data class FimCompletionChoice(
    val index: Int = 0,
    val text: String? = null,
    @SerialName("finish_reason") val finishReason: FinishReason? = null,
)

/**
 * FIM 流转换的跨块状态。
 *
 * @property lastFinishReason 最近一次出现的结束原因
 * @property doneEmitted 是否已发射过 [FimChunk.Done]
 */
internal class FimTransformState {
    var lastFinishReason: FinishReason? = null
    var doneEmitted: Boolean = false
}

/**
 * 把单个 FIM 响应块转换为 [FimChunk] 事件。
 *
 * @param includeUsage 请求是否启用了 usage 统计
 * @param state 跨块状态，保证整个流只发射一次 [FimChunk.Done]
 * @param emit 事件发射回调
 */
@OptIn(ExperimentalDeepseekApi::class)
internal suspend fun FimCompletionChunk.emitFimChunks(
    includeUsage: Boolean,
    state: FimTransformState = FimTransformState(),
    emit: suspend (FimChunk) -> Unit,
) {
    for (choice in choices) {
        if (!choice.text.isNullOrEmpty()) {
            emit(FimChunk.TextDelta(choice.text))
        }
        choice.finishReason?.let { state.lastFinishReason = it }
    }

    usage?.let { usage ->
        if (!state.doneEmitted) {
            emit(FimChunk.Done(usage, state.lastFinishReason?.name?.lowercase()))
            state.doneEmitted = true
        }
    }

    // includeUsage=false 时服务端不发送 usage 块，用结束块兜底发射 Done。
    if (!state.doneEmitted && !includeUsage && choices.any { it.finishReason != null }) {
        emit(FimChunk.Done(Usage(0, 0, 0), state.lastFinishReason?.name?.lowercase()))
        state.doneEmitted = true
    }
}

/**
 * FIM 补全 API 的网络后端，请求发送到 `{baseUrl}/beta/completions`，
 * 默认使用官方地址 [DEFAULT_BASE_URL]。
 */
@OptIn(ExperimentalDeepseekApi::class)
internal interface DeepseekFimApi {
    /**
     * 发起流式 FIM 补全请求。
     *
     * @param prompt 补全提示（前缀）
     * @param suffix 被补全内容的后缀
     * @param echo 是否在输出中回显 prompt
     * @param model 使用的模型
     * @param config 复用的 [ChatConfig]
     * @return [FimChunk] 流
     */
    fun fim(
        prompt: String,
        suffix: String?,
        echo: Boolean?,
        model: Model,
        config: ChatConfig,
    ): Flow<FimChunk>
}

/**
 * 基于 [Network] 的 FIM 补全实现。
 */
@OptIn(ExperimentalDeepseekApi::class)
internal class DeepseekFimApiImpl(
    apiKey: String,
    pool: DeepseekHttpClientPool,
    baseUrl: String = DEFAULT_BASE_URL,
) : DeepseekFimApi {
    private val net = Network(baseUrl, apiKey, pool)

    override fun fim(
        prompt: String,
        suffix: String?,
        echo: Boolean?,
        model: Model,
        config: ChatConfig,
    ): Flow<FimChunk> {
        val state = FimTransformState()

        return net.sseStream<FimCompletionChunk>(
            action = "/beta/completions",
            bodyJson = json.encodeToString(buildFimRequest(prompt, suffix, echo, model, config)),
            json = json,
        ).transform { chunk ->
            chunk.emitFimChunks(config.includeUsage, state) { emit(it) }
        }
    }
}
