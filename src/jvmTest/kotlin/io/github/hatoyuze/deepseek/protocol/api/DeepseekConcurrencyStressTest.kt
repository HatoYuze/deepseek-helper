package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * JVM 重压测试：真实并行调度下验证 (Stateless)Deepseek 的并发与取消语义。
 */
class DeepseekConcurrencyStressTest {

    @Test
    fun `500 concurrent chatStream calls keep histories isolated`() = runBlocking {
        val seen = ConcurrentLinkedQueue<List<Message>>()
        val backend = GatedBackend { messages ->
            seen.add(messages)
            flow {
                emit(ChatChunk.ContentDelta("reply-${messages.last().content}"))
                emit(ChatChunk.Done(1, 1, 1))
            }
        }
        val ds = StatelessDeepseek(
            "test-key",
            testCore(singleSession = false, backend = backend, prompt = "sys"),
        )

        val results = (1..500).map { i ->
            async(Dispatchers.Default) { ds.chatStream("user-$i").toList() }
        }.awaitAll()

        assertEquals(500, seen.size, "每次 chatStream 应恰好触发一次后端调用")
        val byContent = seen.groupBy { it.last().content }
        for (i in 1..500) {
            val messages = byContent["user-$i"]
            assertNotNull(messages, "user-$i 的 history 应存在")
            assertEquals(1, messages.size, "user-$i 不应被重复或丢失")
            assertEquals(Message(Role.System, "sys"), messages[0][0])
            assertEquals(Message(Role.User, "user-$i"), messages[0][1])
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
    fun `200 stream burst cancels all and instance stays reusable under real parallelism`() =
        runBlocking {
            val startedCount = AtomicInteger()
            val backend = GatedBackend { _ ->
                if (startedCount.incrementAndGet() <= 200) {
                    flow { awaitCancellation() }
                } else {
                    flowOf(ChatChunk.Done(1, 1, 1))
                }
            }
            val ds = StatelessDeepseek(
                "test-key",
                testCore(singleSession = false, backend = backend, prompt = "sys"),
            )

            val jobs = (1..200).map { i ->
                launch(Dispatchers.Default) { ds.chatStream("u$i").collect { } }
            }
            while (startedCount.get() < 200) {
                delay(1)
            }
            ds.cancelStream()

            withTimeout(30_000) { jobs.joinAll() }
            assertTrue(jobs.all { it.isCancelled }, "burst 后全部流应被取消")

            val chunks = ds.chatStream("after").toList()
            assertTrue(chunks.any { it is ChatChunk.Done }, "实例应可复用")
        }

    @Test
    fun `stateful client under 200 rapid replacements keeps exactly one active stream`() =
        runBlocking {
            val backend = GatedBackend { flowOf(ChatChunk.Done(1, 1, 1)) }
            val ds = Deepseek(
                "test-key",
                testCore(singleSession = true, backend = backend),
            )

            val jobs = (1..200).map { i ->
                launch(Dispatchers.Default) { ds.chatStream("u$i").collect { } }
            }
            withTimeout(30_000) { jobs.joinAll() }

            val completed = jobs.count { !it.isCancelled }
            assertTrue(completed >= 1, "至少应有一个流正常完成")
            assertTrue(ds.messages.isNotEmpty(), "至少应保留一个已提交的 user 消息")
            assertTrue(ds.messages.all { it.role == Role.User }, "历史中只应保留 user 消息")
            assertEquals(
                ds.messages.size,
                ds.messages.map { it.content }.toSet().size,
                "已提交的 user 消息不应重复",
            )
            assertTrue(ds.messages.size <= 200, "历史中不应出现本次会话之外的残留消息")
        }
}
