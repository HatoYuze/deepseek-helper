package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent

/**
 * JVM 重压测试：真实并行调度下验证历史替换/清空的并发契约（D1）。
 *
 * 覆盖范围（不要过度解读）：
 * - 替换/清空与活跃流的竞态：替换会先取消活跃流，且被取消流的回滚只删掉自己写入的消息；
 * - 替换风暴与并发读取的重叠：换表是对表引用的单次原子写，读者只会看到完整的某一份历史；
 * - 串行化使用下（契约要求）连续历史操作的状态确定性。
 *
 * 不覆盖：与活跃流的**追加**并发读取（契约明确不支持，见 `Deepseek` KDoc「线程模型与并发契约」）。
 */
class DeepseekHistoryStressTest {

    private val system = Message(Role.System, MessageContent.of("sys"))

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

            val installed = (1..(round % 5 + 1)).map { Message(Role.User, MessageContent.of("kept-$round-$it")) }
            ds.replaceHistory(installed)
            withTimeout(10_000) { job.join() }

            assertTrue(job.isCancelled, "第 $round 轮：替换历史应取消活跃流")
            assertEquals(installed, ds.messages, "第 $round 轮：替换结果被被取消流的回滚覆盖了")
            assertEquals(installed.size, ds.getMessageCount())
        }

        assertEquals(200, probe.callCount, "每轮应恰好发起一次后端调用")

        // 风暴之后实例仍可用：安装的历史作为下一轮请求的上下文，并正常提交 assistant 回复
        probe.stopBlocking()
        ds.replaceHistory(listOf(Message(Role.User, MessageContent.of("restored"))))
        ds.chatStream("final").collect { }

        assertEquals(
            listOf(Message(Role.User, MessageContent.of("restored")), Message(Role.User, MessageContent.of("final")), Message(Role.Assistant, MessageContent.of("ok"))),
            ds.messages,
            "替换风暴后实例应继续正常工作",
        )
    }

    @Test
    fun `concurrent readers only ever observe whole installed histories`() = runBlocking {
        val ds = statefulDeepseek(GatedBackend(), prompt = "sys")
        val listA = (1..5).map { Message(Role.User, MessageContent.of("A$it")) }
        val listB = (1..9).map { Message(Role.User, MessageContent.of("B$it")) }
        // 精确替换：安装的就是传入列表本身；clearHistory 回到初始历史 [system]
        val accepted = setOf(listA, listB, listOf(system))

        val torn = ConcurrentLinkedQueue<String>()
        val readsDuringStorm = AtomicInteger()
        val readersReady = AtomicInteger()
        val stormRunning = AtomicBoolean(false)
        val stormDone = AtomicBoolean(false)
        val readers = (1..8).map { id ->
            launch(Dispatchers.Default) {
                readersReady.incrementAndGet()
                // 自旋等待风暴结束：读者必然与替换风暴重叠（有上限，避免失败时挂死）
                var iterations = 0
                while (!stormDone.get() && iterations < 5_000_000) {
                    val snapshot = ds.messages
                    if (stormRunning.get()) readsDuringStorm.incrementAndGet()
                    if (snapshot !in accepted) torn += "reader-$id: ${snapshot.map { it.content }}"
                    iterations++
                }
            }
        }
        withTimeout(10_000) { while (readersReady.get() < 8) delay(1) }

        stormRunning.set(true)
        repeat(3_000) { i ->
            when (i % 3) {
                0 -> ds.replaceHistory(listA)
                1 -> ds.replaceHistory(listB)
                else -> ds.clearHistory()
            }
        }
        val expected = ds.messages
        stormDone.set(true)
        withTimeout(60_000) { readers.joinAll() }

        assertTrue(expected in accepted, "最终历史应为某次安装的完整历史，实际: ${expected.map { it.content }}")
        assertTrue(readsDuringStorm.get() > 0, "读者必须与替换风暴真正重叠，实际重叠读取次数=${readsDuringStorm.get()}")
        assertTrue(torn.isEmpty(), "并发读取不得看到撕裂/混合的历史，实际: ${torn.take(3)}")
        assertEquals(expected, ds.messages, "风暴结束后历史应保持稳定")
    }

    @Test
    fun `serialized history operations keep a single instance predictable`() = runBlocking {
        val probe = BlockingProbe()
        val ds = statefulDeepseek(probe.backend, prompt = "sys")

        // 契约（D1）：历史操作由调用方串行化 —— 连续替换/清空/追加后状态必须精确可预测
        repeat(1_000) { i ->
            when (i % 4) {
                0 -> ds.replaceHistory(listOf(Message(Role.User, MessageContent.of("u$i"))))
                1 -> ds.addMessage(Message(Role.Assistant, MessageContent.of("a$i")))
                2 -> ds.clearHistory()
                else -> ds.replaceHistory(emptyList())
            }
        }
        ds.replaceHistory(listOf(Message(Role.User, MessageContent.of("final"))))

        assertEquals(listOf(Message(Role.User, MessageContent.of("final"))), ds.messages)
        assertEquals(1, ds.getMessageCount())
        assertEquals(0, probe.callCount, "纯历史操作不应触发任何后端调用")
    }
}
