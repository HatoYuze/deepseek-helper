package io.github.hatoyuze.deepseek.protocol.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancelStreamTest {

    /** 暴露核心的 streamFlow 骨架，便于在无网络环境下验证取消语义 */
    private class TestDeepseek(apiKey: String) : Deepseek(apiKey) {
        fun exposeStream(body: suspend FlowCollector<ChatChunk>.() -> Unit) = core.streamFlow(body)
    }

    @Test
    fun `cancelStream aborts the active stream collection`() = runTest {
        val ds = TestDeepseek("test-key")
        val started = CompletableDeferred<Unit>()

        val job = launch {
            ds.exposeStream {
                started.complete(Unit)
                awaitCancellation()
            }.collect { }
        }

        // 等待流真正开始收集，确保 cancelStream 能拿到活动协程
        started.await()
        ds.cancelStream()

        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled, "cancelStream 应取消当前流的收集协程")
        assertTrue(ds.isCancelled, "cancelStream 应置位取消标志")
    }

    @Test
    fun `new stream resets the cancellation flag`() = runTest {
        val ds = TestDeepseek("test-key")
        ds.cancelStream()
        assertTrue(ds.isCancelled)

        ds.exposeStream { emit(ChatChunk.Done(0, 0, 0)) }.collect { }

        assertFalse(ds.isCancelled, "新的流式调用应复位取消标志")
    }
}
