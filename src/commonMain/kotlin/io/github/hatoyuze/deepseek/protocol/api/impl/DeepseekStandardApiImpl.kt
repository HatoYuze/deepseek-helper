package io.github.hatoyuze.deepseek.protocol.api.impl

import io.github.hatoyuze.deepseek.protocol.api.ChatChunk
import io.github.hatoyuze.deepseek.protocol.api.entity.ChatCompletionChunk
import io.github.hatoyuze.deepseek.protocol.api.ChatConfig
import io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi
import io.github.hatoyuze.deepseek.protocol.api.entity.FinishReason
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.collections.iterator

internal class DeepseekStandardApiImpl(
    apiKey: String,
    pool: DeepseekHttpClientPool,
) : DeepseekApiBase(
        apiKey = apiKey,
        baseUrl = "https://api.deepseek.com",
        pool = pool,
    ) {

    @OptIn(ExperimentalDeepseekApi::class)
    override suspend fun completions(
        messages: List<Message>,
        model: Model,
        config: ChatConfig,
        tools: List<ToolDefinition>?,
    ): Flow<ChatChunk> {

        @Serializable data class Thinking(
            val type: String,
            @SerialName("reasoning_effort") val reasoningEffort: String? = null,
        )
        @Serializable data class ResponseFmt(val type: String)
        @Serializable data class StreamOpts(@SerialName("include_usage") val includeUsage: Boolean = false)
        @Serializable data class ToolFunc(
            val name: String,
            val description: String,
            val parameters: JsonElement,
            val strict: Boolean = false,
            @SerialName("\$def") val defs: Map<String, JsonElement>? = null,
        )
        @Serializable data class ToolDef(@EncodeDefault(EncodeDefault.Mode.ALWAYS) val type: String = "function", val function: ToolFunc)
        @Serializable
        data class Request(
            val messages: List<Message>,
            val model: String,
            @SerialName("max_tokens") val maxTokens: Int? = null,
            val temperature: Double? = null,
            @SerialName("top_p") val topP: Double? = null,
            val thinking: Thinking? = null,
            @SerialName("response_format") val responseFormat: ResponseFmt? = null,
            val stop: JsonElement? = null,
            @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stream: Boolean = true,
            @SerialName("stream_options") val streamOptions: StreamOpts? = null,
            val tools: List<ToolDef>? = null,
            @SerialName("tool_choice") val toolChoice: JsonElement? = null,
            val logprobs: Boolean? = null,
            @SerialName("top_logprobs") val topLogprobs: Int? = null,
        )

        // thinking 仅在非默认状态时发送
        val thinking = when (val mode = config.thinkingMode) {
            null, is ThinkingMode.Enabled -> null
            is ThinkingMode.Disabled -> Thinking(type = "disabled")
            is ThinkingMode.WithEffort -> Thinking(
                type = "enabled",
                reasoningEffort = mode.effort.name.lowercase(),
            )
        }

        // stream_options 仅在 includeUsage=true（非 API 默认值）时发送
        val streamOpts = if (config.includeUsage) StreamOpts(includeUsage = true) else null

        val request = Request(
            messages = messages.withoutReasoningContent(),
            model = model.id,
            maxTokens = config.maxTokens,
            temperature = config.temperature,
            topP = config.topP,
            thinking = thinking,
            responseFormat = config.responseFormat?.let { ResponseFmt(it.name.lowercase()) },
            stop = config.stop?.toJsonElement(),
            streamOptions = streamOpts,
            tools = tools?.map {
                ToolDef(function = ToolFunc(
                    name = it.name,
                    description = it.description,
                    parameters = it.parameters,
                    strict = it.strict,
                    defs = it.defs,
                ))
            },
            toolChoice = config.toolChoice?.toJsonElement(),
            logprobs = config.logprobs,
            topLogprobs = config.topLogprobs,
        )

        data class ToolCallAccumulator(
            var id: String = "",
            var name: String = "",
            val argsBuilder: StringBuilder = StringBuilder(),
        )
        val pendingToolCalls = mutableMapOf<Int, ToolCallAccumulator>()
        var finishReason: FinishReason? = null

        val requestJson = json.encodeToString(request)
        return net.sseStream<ChatCompletionChunk>("/chat/completions", requestJson, json)
            .transform { chunk ->
                for (choice in chunk.choices) {
                    choice.finishReason?.let { finishReason = it }
                    // 累积流式 tool call 参数分片
                    choice.delta.toolCalls?.forEach { tc ->
                        val acc = pendingToolCalls.getOrPut(tc.index) { ToolCallAccumulator() }
                        if (tc.id != null) acc.id = tc.id
                        if (tc.function?.name != null) acc.name = tc.function.name
                        tc.function?.arguments?.let { acc.argsBuilder.append(it) }
                    }

                    // finish_reason=TOOL_CALLS 时一次性 emit 完整 tool call
                    if (choice.finishReason == FinishReason.TOOL_CALLS) {
                        for ((_, acc) in pendingToolCalls) {
                            val tcChunk = ChatChunk.ToolCallRequest(
                                ToolCall(
                                    id = acc.id,
                                    name = acc.name,
                                    arguments = acc.argsBuilder.toString(),
                                ),
                            )
                            emit(tcChunk)
                        }
                        pendingToolCalls.clear()
                    }

                    val content = ChatChunk.ContentDelta(
                        content = choice.delta.content ?: "",
                        reasoningContent = choice.delta.reasoningContent,
                    )
                    if (content.content.isNotEmpty() || content.reasoningContent != null) {
                        emit(content)
                    }
                }
                // usage MUST emit after tool calls — Deepseek sends both in the final chunk
                chunk.usage?.let {
                    val done = ChatChunk.Done(
                        promptTokens = it.promptTokens,
                        completionTokens = it.completionTokens,
                        totalTokens = it.totalTokens,
                        finishReason = finishReason?.name?.lowercase(),
                        promptCacheHitTokens = it.promptCacheHitTokens,
                        promptCacheMissTokens = it.promptCacheMissTokens,
                        reasoningTokens = it.reasoningTokens,
                    )
                    emit(done)
                }
            }
    }
}

/**
 * 返回剥离思考内容的消息副本。
 *
 * DeepSeek 官方不建议把 `reasoning_content` 回传给下一次请求；
 * 历史中的思考内容仍保留供展示，仅在请求负载中剥离。
 */
@OptIn(ExperimentalDeepseekApi::class)
internal fun List<Message>.withoutReasoningContent(): List<Message> =
    if (none { it.reasoningContent != null }) {
        this
    } else {
        map { it.copy(reasoningContent = null) }
    }

internal suspend fun checkHttpStatus(response: HttpResponse) {
    when (response.status) {
        HttpStatusCode.Unauthorized -> throw IllegalStateException("API key 错误，认证失败\n请检查您的 API key 是否正确，如没有 API key，请先 创建 API key")
        HttpStatusCode.BadRequest -> throw IllegalArgumentException("""
            Error 400 bad request
            Header: ${response.request.headers}
            Tip: ${response.bodyAsText()}
        """.trimIndent())
        HttpStatusCode.PaymentRequired -> throw IllegalStateException("账号余额不足\n请确认账户余额，并前往 充值 页面进行充值")
        HttpStatusCode.UnprocessableEntity -> throw IllegalArgumentException("请求体参数错误\n${response.bodyAsText()}")
        HttpStatusCode.TooManyRequests -> throw IllegalStateException("请求速率达到上限\t原因：请求速率（TPM 或 RPM）达到上限")
        HttpStatusCode.InternalServerError -> throw IllegalStateException("服务器内部故障")
        HttpStatusCode.ServiceUnavailable -> throw IllegalStateException("服务器负载过高")
    }

    if (response.status != HttpStatusCode.OK) {
        throw IllegalStateException("An error happened because of wrong response status: ${response.status}\n With headers: ${response.request.headers}")
    }
}
