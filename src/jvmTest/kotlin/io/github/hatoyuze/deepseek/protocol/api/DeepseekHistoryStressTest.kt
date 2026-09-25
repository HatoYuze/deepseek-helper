package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM 重压测试：真实并行调度下验证历史替换/清空的并发契约（D1）。
 *
 * 契约：替换/清空会先取消活跃流；替换是整体换表，被取消流的回滚不可能撤销替换结果；
 * 换表通过 `@Volatile` 引用发布，因此在**没有活跃流写入**时并发读取只会看到完整的某一份历史。
 */
class DeepseekHistoryStressTest {

    private val system = Message(Role.System, "sys")

    /** 可切换行为的后端：挂起模式下等待取消，放行模式下立即完整返回一轮回复。 */
    private class BlockingProbe {
        val started = Channel<Unit>(Channel.UNLIMITED)
        private val blocking = AtomicBoolean(true)
        private val calls = AtomicInteger()

        val backend = GatedBackend { _ ->
            calls.incrementAndGet()
            if (blocking.get()) {
                flow {
                    started.send(Unit)
                    awaitCancellation()
                }
            } else {
                flow {
                    emit(ChatChunk.ContentDelta("ok"))
                    emit(ChatChunk.Done(1, 1, 2))
                }
            }
        }

        fun stopBlocking() {
            blocking.set(false)
        }

        val callCount: Int get() = calls.get()
    }

    @Test
    fun `200 rounds of stream then replace keep exactly the installed history`() = runBlocking {
        val probe = BlockingProbe()
        val ds = statefulDeepseek(probe.backend, prompt = "sys")

        repeat(200) { round ->
            val job = launch(Dispatchers.Default) { ds.chatStream("blocked-$round").collect { } }
            withTimeout(10_000) { probe.started.receive() }

            val installed = (1..(round % 5 + 1)).map { Message(Role.User, "kept-$round-$it") }
            ds.replaceHistory(installed)
            withTimeout(10_000) { job.join() }

            assertTrue(job.isCancelled, "第 $round 轮：替换历史应取消活跃流")
            assertEquals(installed, ds.messages, "第 $round 轮：替换结果被被取消流的回滚覆盖了")
            assertEquals(installed.size, ds.getMessageCount())
        }

        assertEquals(200, probe.callCount, "每轮应恰好发起一次后端调用")

        // 风暴之后实例仍可用：安装的历史作为下一轮请求的上下文，并正常提交 assistant 回复
        probe.stopBlocking()
        ds.replaceHistory(listOf(Message(Role.User, "restored")))
        ds.chatStream("final").collect { }

        assertEquals(
            listOf(Message(Role.User, "restored"), Message(Role.User, "final"), Message(Role.Assistance, "ok")),
            ds.messages,
            "替换风暴后实例应继续正常工作",
        )
    }

    @Test
    fun `concurrent readers only ever observe whole installed histories`() = runBlocking {
        val ds = statefulDeepseek(GatedBackend(), prompt = "sys")
        val listA = (1..5).map { Message(Role.User, "A$it") }
        val listB = (1..9).map { Message(Role.User, "B$it") }
        // 精确替换：安装的就是传入列表本身；clearHistory 回到初始历史 [system]
        val accepted = setOf(listA, listB, listOf(system))

        val torn = ConcurrentLinkedQueue<String>()
        val afterStorm = ConcurrentLinkedQueue<List<Message>>()
        val barrier = CompletableDeferred<Unit>()
        val readers = (1..8).map { id ->
            launch(Dispatchers.Default) {
                repeat(2_000) {
                    val snapshot = ds.messages
                    if (snapshot !in accepted) torn += "reader-$id: ${snapshot.map { it.content }}"
                }
                barrier.await()
                afterStorm += ds.messages
            }
        }

        val writer = launch(Dispatchers.Default) {
            repeat(3_000) { i ->
                when (i % 3) {
                    0 -> ds.replaceHistory(listA)
                    1 -> ds.replaceHistory(listB)
                    else -> ds.clearHistory()
                }
            }
        }
        withTimeout(60_000) { writer.join() }
        val expected = ds.messages
        assertTrue(expected in accepted, "最终历史应为某次安装的完整历史，实际: ${expected.map { it.content }}")

        barrier.complete(Unit)
        withTimeout(60_000) { readers.joinAll() }

        assertTrue(torn.isEmpty(), "并发读取不得看到撕裂/混合的历史，实际: ${torn.take(3)}")
        assertEquals(8, afterStorm.size, "每个 reader 都应在风暴后读取一次")
        assertTrue(afterStorm.all { it == expected }, "风暴后读取应与最终安装的历史一致")
    }

    @Test
    fun `serialized history operations keep a single instance predictable`() = runBlocking {
        val probe = BlockingProbe()
        val ds = statefulDeepseek(probe.backend, prompt = "sys")

        // 契约（D1）：历史操作由调用方串行化 —— 连续替换/清空/追加后状态必须精确可预测
        repeat(1_000) { i ->
            when (i % 4) {
                0 -> ds.replaceHistory(listOf(Message(Role.User, "u$i")))
                1 -> ds.addMessage(Message(Role.Assistance, "a$i"))
                2 -> ds.clearHistory()
                else -> ds.replaceHistory(emptyList())
            }
        }
        ds.replaceHistory(listOf(Message(Role.User, "final")))

        assertEquals(listOf(Message(Role.User, "final")), ds.messages)
        assertEquals(1, ds.getMessageCount())
        assertEquals(0, probe.callCount, "纯历史操作不应触发任何后端调用")
    }
}
