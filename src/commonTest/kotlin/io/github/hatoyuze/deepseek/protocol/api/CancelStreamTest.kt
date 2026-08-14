package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekFimApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalDeepseekApi::class)
class CancelStreamTest {

    /** 暴露核心的 streamFlow 骨架，便于在无网络环境下验证取消语义 */
    private class TestDeepseek(apiKey: String) : Deepseek(apiKey) {
        fun exposeStream(body: suspend FlowCollector<ChatChunk>.(StreamSession) -> Unit) =
            core.streamFlow(body)
    }

    @Test
    fun `stateful Deepseek replaces the previous active stream`() = runTest {
        val ds = TestDeepseek("test-key")
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val firstJob = launch {
            ds.exposeStream {
                firstStarted.complete(Unit)
                awaitCancellation()
            }.collect { }
        }
        firstStarted.await()

        val secondJob = launch {
            ds.exposeStream {
                secondStarted.complete(Unit)
                awaitCancellation()
            }.collect { }
        }
        secondStarted.await()

        withTimeout(5_000) { firstJob.join() }
        assertTrue(firstJob.isCancelled, "启动新流后旧流应被取消")
        assertFalse(secondJob.isCancelled)

        ds.cancelStream()
        withTimeout(5_000) { secondJob.join() }
        assertTrue(secondJob.isCancelled)
    }

    @Test
    fun `stateless Deepseek keeps concurrent streams and cancelStream cancels all`() = runTest {
        val ds = StatelessDeepseek("test-key")
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val firstJob = launch {
            ds.core.streamFlow<ChatChunk> {
                firstStarted.complete(Unit)
                awaitCancellation()
            }.collect { }
        }
        val secondJob = launch {
            ds.core.streamFlow<ChatChunk> {
                secondStarted.complete(Unit)
                awaitCancellation()
            }.collect { }
        }

        firstStarted.await()
        secondStarted.await()
        assertFalse(firstJob.isCancelled)
        assertFalse(secondJob.isCancelled)

        ds.cancelStream()

        withTimeout(5_000) { firstJob.join() }
        withTimeout(5_000) { secondJob.join() }
        assertTrue(firstJob.isCancelled)
        assertTrue(secondJob.isCancelled)
    }

    @Test
    fun `cancelStream cancels an active FIM stream`() = runTest {
        val fakeFimApi = FakeFimApi()
        val core = DeepseekCore(
            apiKey = "test-key",
            model = null,
            prompt = null,
            config = ChatConfig(),
            api = DeepseekApi.STANDARD,
            singleSession = true,
            fimApi = fakeFimApi,
        )

        val job = launch {
            core.fimFlow("prompt", null, null, null).collect { }
        }

        fakeFimApi.started.await()
        core.cancelStream()

        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled, "cancelStream 应取消 FIM 流")
    }

    private class FakeFimApi : DeepseekFimApi {
        val started = CompletableDeferred<Unit>()

        override fun fim(
            prompt: String,
            suffix: String?,
            echo: Boolean?,
            model: Model,
            config: ChatConfig,
        ): Flow<FimChunk> = flow {
            started.complete(Unit)
            awaitCancellation()
        }
    }
}
