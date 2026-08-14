package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.protocol.api.entity.Usage
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekFimApi
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientConfig
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientFactory
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.concurrent.Volatile

/**
 * 并发测试用的 chat 后端：把 [completions] 转发给可编程的 [onCompletions]，
 * 便于在 flow 内用 `CompletableDeferred` 门控或记录每次收到的 messages。
 */
internal class GatedBackend(
    private val onCompletions: suspend (messages: List<Message>) -> Flow<ChatChunk> = {
        flowOf(ChatChunk.Done(0, 0, 0))
    },
) : DeepseekApiBackend {
    override suspend fun models(): List<Model> = emptyList()

    override suspend fun userBalance(): UserBalance = error("GatedBackend does not implement balance")

    override suspend fun completions(
        messages: List<Message>,
        model: Model,
        config: ChatConfig,
        tools: List<ToolDefinition>?,
    ): Flow<ChatChunk> = onCompletions(messages)
}

/** 并发测试用的 FIM 后端，转发给可编程的 [onFim]。 */
@OptIn(ExperimentalDeepseekApi::class)
internal class GatedFimApi(
    private val onFim: (
        prompt: String,
        suffix: String?,
        echo: Boolean?,
        model: Model,
        config: ChatConfig,
    ) -> Flow<FimChunk> = { _, _, _, _, _ ->
        flowOf(FimChunk.Done(Usage(0, 0, 0)))
    },
) : DeepseekFimApi {
    override fun fim(
        prompt: String,
        suffix: String?,
        echo: Boolean?,
        model: Model,
        config: ChatConfig,
    ): Flow<FimChunk> = onFim(prompt, suffix, echo, model, config)
}

/**
 * 并发测试用的 HttpClient 工厂：统计创建次数，可选先连续失败 [failFirst] 次。
 *
 * 计数仅在协程串行执行的测试调度器下保证精确（runTest / 单线程平台）；
 * JVM 重压测试请使用各自文件中的原子计数工厂。
 */
internal class CountingHttpClientFactory(
    private val failFirst: Int = 0,
    private val clientFactory: (DeepseekHttpClientConfig) -> HttpClient = {
        HttpClient(MockEngine { respondOk() })
    },
) : DeepseekHttpClientFactory {
    @Volatile
    var created: Int = 0
        private set

    @Volatile
    private var failuresRemaining: Int = failFirst

    override fun create(config: DeepseekHttpClientConfig): HttpClient {
        if (failuresRemaining > 0) {
            failuresRemaining--
            throw IllegalStateException("simulated factory failure")
        }
        created++
        return clientFactory(config)
    }
}

/** 构造一个注入 Fake 后端的核心，供并发测试复用。 */
internal fun testCore(
    singleSession: Boolean,
    backend: GatedBackend,
    fimApi: GatedFimApi? = null,
    prompt: String? = null,
): DeepseekCore = DeepseekCore(
    apiKey = "test-key",
    model = null,
    prompt = prompt,
    config = ChatConfig(),
    api = DeepseekApi.STANDARD,
    singleSession = singleSession,
    backend = backend,
    fimApi = fimApi,
)
