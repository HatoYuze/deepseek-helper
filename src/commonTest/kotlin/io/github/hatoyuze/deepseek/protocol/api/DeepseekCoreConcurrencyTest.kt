package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.Usage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalDeepseekApi::class)
class DeepseekCoreConcurrencyTest {

    @Test
    fun `stateless core keeps all concurrent streams alive until cancelStream`() = runTest {
        val core = testCore(singleSession = false, backend = GatedBackend())
        val started = (1..64).map { CompletableDeferred<Unit>() }
        val jobs = started.map { gate ->
            launch {
                core.streamFlow<ChatChunk> {
                    gate.complete(Unit)
                    awaitCancellation()
                }.collect { }
            }
        }
        started.forEach { withTimeout(5_000) { it.await() } }
        assertTrue(jobs.none { it.isCancelled }, "取消前所有并发流应存活")

        core.cancelStream()
        withTimeout(10_000) { jobs.joinAll() }
        assertTrue(jobs.all { it.isCancelled }, "cancelStream 应取消全部并发流")

        val followUp = async {
            core.streamFlow<ChatChunk> { emit(ChatChunk.Done(0, 0, 0)) }.toList()
        }
        assertEquals(1, withTimeout(5_000) { followUp.await() }.size, "取消后 core 应可继续使用")
    }

    @Test
    fun `stateful core rapid replacement keeps only the latest stream`() = runTest {
        val core = testCore(singleSession = true, backend = GatedBackend())
        val jobs = mutableListOf<Job>()
        for (i in 1..50) {
            val gate = CompletableDeferred<Unit>()
            jobs += launch {
                core.streamFlow<ChatChunk> {
                    gate.complete(Unit)
                    awaitCancellation()
                }.collect { }
            }
            withTimeout(5_000) { gate.await() }
        }

        assertTrue(jobs.dropLast(1).all { it.isCancelled }, "启动新流应取消全部旧流")
        assertFalse(jobs.last().isCancelled, "最后一个流应存活")

        core.cancelStream()
        withTimeout(10_000) { jobs.joinAll() }
        assertTrue(jobs.last().isCancelled, "cancelStream 应取消最后一个流")
    }

    @Test
    fun `cancelling one stateless stream does not cancel sibling streams`() = runTest {
        val core = testCore(singleSession = false, backend = GatedBackend())
        data class Gates(val started: CompletableDeferred<Unit>, val release: CompletableDeferred<Unit>)
        val gates = (1..3).map {
            Gates(CompletableDeferred(), CompletableDeferred())
        }
        val jobs = gates.map { gate ->
            launch {
                core.streamFlow<ChatChunk> {
                    gate.started.complete(Unit)
                    gate.release.await()
                    emit(ChatChunk.Done(0, 0, 0))
                }.collect { }
            }
        }
        gates.forEach { withTimeout(5_000) { it.started.await() } }

        jobs[0].cancel()
        withTimeout(5_000) { jobs[0].join() }
        assertTrue(jobs[0].isCancelled)
        assertFalse(jobs[1].isCancelled, "取消单个流不应影响兄弟流")
        assertFalse(jobs[2].isCancelled, "取消单个流不应影响兄弟流")

        gates[1].release.complete(Unit)
        gates[2].release.complete(Unit)
        withTimeout(10_000) {
            jobs[1].join()
            jobs[2].join()
        }
        assertFalse(jobs[1].isCancelled, "兄弟流应正常完成")
        assertFalse(jobs[2].isCancelled, "兄弟流应正常完成")
    }

    @Test
    fun `chat and FIM streams run concurrently on stateless core and cancel together`() = runTest {
        val chatStarted = CompletableDeferred<Unit>()
        val fimStarted = CompletableDeferred<Unit>()
        val backend = GatedBackend { flow { chatStarted.complete(Unit); awaitCancellation() } }
        val fimApi = GatedFimApi { _, _, _, _, _ -> flow { fimStarted.complete(Unit); awaitCancellation() } }
        val core = testCore(singleSession = false, backend = backend, fimApi = fimApi)

        val chatJob = launch {
            core.streamFlow<ChatChunk> { session ->
                streamLoop(core, mutableListOf(), "hi", null, session)
            }.collect { }
        }
        val fimJob = launch {
            core.fimFlow("prompt", null, null, null).collect { }
        }

        withTimeout(5_000) { chatStarted.await() }
        withTimeout(5_000) { fimStarted.await() }
        assertFalse(chatJob.isCancelled)
        assertFalse(fimJob.isCancelled)

        core.cancelStream()
        withTimeout(10_000) {
            chatJob.join()
            fimJob.join()
        }
        assertTrue(chatJob.isCancelled, "cancelStream 应取消并发的 chat 流")
        assertTrue(fimJob.isCancelled, "cancelStream 应取消并发的 FIM 流")
    }

    @Test
    fun `chat and FIM streams complete independently on stateless core`() = runTest {
        val backend = GatedBackend {
            flow {
                emit(ChatChunk.ContentDelta("chat"))
                emit(ChatChunk.Done(1, 1, 1))
            }
        }
        val fimApi = GatedFimApi { _, _, _, _, _ ->
            flow {
                emit(FimChunk.TextDelta("fim"))
                emit(FimChunk.Done(Usage(1, 1, 1)))
            }
        }
        val core = testCore(singleSession = false, backend = backend, fimApi = fimApi)

        val chat = async {
            core.streamFlow<ChatChunk> { session ->
                streamLoop(core, mutableListOf(), "hi", null, session)
            }.toList()
        }
        val fim = async { core.fimFlow("prompt", null, null, null).toList() }

        val chatChunks = withTimeout(10_000) { chat.await() }
        val fimChunks = withTimeout(10_000) { fim.await() }

        assertTrue(chatChunks.filterIsInstance<ChatChunk.ContentDelta>().any { it.content == "chat" })
        assertTrue(chatChunks.any { it is ChatChunk.Done }, "chat 流应以 Done 收尾")
        assertEquals(
            listOf(FimChunk.TextDelta("fim"), FimChunk.Done(Usage(1, 1, 1))),
            fimChunks,
            "FIM 流应独立发射 TextDelta 与 Done",
        )
    }

    @Test
    fun `cancelled stream rolls back its user message`() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val backend = GatedBackend { _ ->
            calls++
            if (calls == 1) {
                flow {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } else {
                flowOf(ChatChunk.Done(1, 2, 3))
            }
        }
        val core = testCore(singleSession = true, backend = backend)
        val history = mutableListOf<Message>()

        val job = launch {
            core.streamFlow<ChatChunk> { session ->
                streamLoop(core, history, "temp", null, session)
            }.collect { }
        }
        withTimeout(5_000) { started.await() }
        core.cancelStream()
        withTimeout(5_000) { job.join() }
        assertTrue(history.isEmpty(), "被取消流的 user 消息应回滚")

        val chunks = core.streamFlow<ChatChunk> { session ->
            streamLoop(core, history, "final", null, session)
        }.toList()
        assertTrue(chunks.any { it is ChatChunk.Done }, "取消后新的流应正常完成")
        assertEquals(listOf(Message(Role.User, "final")), history, "成功流应只保留自己的 user 消息")
    }
}
