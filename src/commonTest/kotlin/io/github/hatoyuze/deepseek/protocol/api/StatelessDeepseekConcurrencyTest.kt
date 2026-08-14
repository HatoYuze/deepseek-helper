package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.Usage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalDeepseekApi::class)
class StatelessDeepseekConcurrencyTest {

    @Test
    fun `concurrent chatStream calls keep message histories isolated`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val backend = GatedBackend { messages ->
            seen += messages
            flow {
                emit(ChatChunk.ContentDelta("reply-${messages.last().content}"))
                emit(ChatChunk.Done(1, 1, 1))
            }
        }
        val ds = StatelessDeepseek(
            "test-key",
            testCore(singleSession = false, backend = backend, prompt = "sys"),
        )

        val results = (1..20).map { i ->
            async { ds.chatStream("user-$i").toList() }
        }.awaitAll()

        assertEquals(20, seen.size, "每次 chatStream 应触发一次后端调用")
        val byContent = seen.associateBy { it.last().content }
        for (i in 1..20) {
            val messages = byContent["user-$i"]
            assertNotNull(messages, "user-$i 的 history 应被捕获")
            assertEquals(
                listOf(Message(Role.System, "sys"), Message(Role.User, "user-$i")),
                messages,
                "并发流的 history 不应互相污染",
            )
        }
        results.forEachIndexed { i, chunks ->
            assertTrue(
                chunks.any { it is ChatChunk.ContentDelta && it.content == "reply-user-${i + 1}" },
                "每个流应收到属于自己的回复",
            )
            assertTrue(chunks.any { it is ChatChunk.Done }, "每个流应以 Done 收尾")
        }
    }

    @Test
    fun `concurrent chatStream and fimStream complete independently`() = runTest {
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
        val ds = StatelessDeepseek(
            "test-key",
            testCore(singleSession = false, backend = backend, fimApi = fimApi, prompt = "sys"),
        )

        val chat = async { ds.chatStream("hi").toList() }
        val fim = async { ds.fimStream("prompt").toList() }

        val chatChunks = withTimeout(10_000) { chat.await() }
        val fimChunks = withTimeout(10_000) { fim.await() }

        assertTrue(chatChunks.filterIsInstance<ChatChunk.ContentDelta>().any { it.content == "chat" })
        assertTrue(chatChunks.any { it is ChatChunk.Done }, "chat 流应以 Done 收尾")
        assertEquals(
            listOf(FimChunk.TextDelta("fim"), FimChunk.Done(Usage(1, 1, 1))),
            fimChunks,
            "FIM 流应独立完成",
        )
    }

    @Test
    fun `cancelStream during a burst cancels all streams and instance stays reusable`() = runTest {
        val started = Channel<Unit>(Channel.UNLIMITED)
        var calls = 0
        val backend = GatedBackend { _ ->
            calls++
            if (calls <= 32) {
                flow {
                    started.send(Unit)
                    awaitCancellation()
                }
            } else {
                flowOf(ChatChunk.Done(1, 1, 1))
            }
        }
        val ds = StatelessDeepseek(
            "test-key",
            testCore(singleSession = false, backend = backend, prompt = "sys"),
        )

        val jobs = (1..32).map { i ->
            launch { ds.chatStream("u$i").collect { } }
        }
        repeat(32) {
            withTimeout(5_000) { started.receive() }
        }
        ds.cancelStream()

        withTimeout(10_000) { jobs.joinAll() }
        assertTrue(jobs.all { it.isCancelled }, "burst 后 cancelStream 应取消全部流")

        val chunks = ds.chatStream("after").toList()
        assertTrue(chunks.any { it is ChatChunk.Done }, "取消后实例应可继续使用")
    }
}
