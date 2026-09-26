package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent

/**
 * 历史替换/清空与活跃流并发时的契约测试（D1/D2）。
 *
 * 契约：[Deepseek] 单会话语义 —— 替换/清空历史会先取消活跃流；由于历史替换是「整体换表」，
 * 被取消流在 `finally` 中的回滚只作用于替换前的旧表，**不可能撤销替换结果**。
 */
class DeepseekHistoryConcurrencyTest {

    private val system = Message(Role.System, MessageContent.of("sys"))

    private fun neverEndingBackend(started: CompletableDeferred<Unit>) = GatedBackend {
        flow {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    @Test
    fun `replaceHistory during an active stream cancels it and survives its rollback`() = runTest {
        val started = CompletableDeferred<Unit>()
        val ds = statefulDeepseek(neverEndingBackend(started), prompt = "sys")
        val job = launch { ds.chatStream("hello").collect { } }
        withTimeout(5_000) { started.await() }

        // 替换后的历史比流开始时的 historyStart(=1) 长：若回滚作用于新表，这里会被截回 1 条
        val replacement = (1..5).map { Message(Role.User, MessageContent.of("u$it")) }
        ds.replaceHistory(replacement)
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled, "替换历史应取消活跃流")
        assertEquals(replacement, ds.messages, "被取消流的回滚不得撤销历史替换")
        assertEquals(5, ds.getMessageCount())
    }

    @Test
    fun `clearHistory during an active stream yields exactly the initial history`() = runTest {
        val started = CompletableDeferred<Unit>()
        val ds = statefulDeepseek(neverEndingBackend(started), prompt = "sys")
        val job = launch { ds.chatStream("hello").collect { } }
        withTimeout(5_000) { started.await() }

        ds.clearHistory()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled, "清空历史应取消活跃流")
        assertEquals(listOf(system), ds.messages, "清空结果不得被被取消流的回滚覆盖")
    }

    @Test
    fun `cancelled stream rolls back its own messages and leaves no assistant message`() = runTest {
        val started = CompletableDeferred<Unit>()
        val backend = GatedBackend {
            flow {
                emit(ChatChunk.ContentDelta("partial"))
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val ds = statefulDeepseek(backend, prompt = "sys")
        val job = launch { ds.chatStream("hello").collect { } }
        withTimeout(5_000) { started.await() }

        ds.cancelStream()
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled)
        assertEquals(listOf(system), ds.messages, "取消后历史应回滚到本轮开始，且不残留半截回复")
    }

    @Test
    fun `messages snapshot stays stable while a stream appends`() = runTest {
        val backend = GatedBackend {
            flowOf(ChatChunk.ContentDelta("hi"), ChatChunk.Done(1, 1, 2))
        }
        val ds = statefulDeepseek(backend)
        ds.addMessage(Message(Role.User, MessageContent.of("first")))

        val snapshot = ds.messages
        ds.chatStream("second").collect { }
        ds.addMessage(Message(Role.User, MessageContent.of("third")))

        assertEquals(listOf(Message(Role.User, MessageContent.of("first"))), snapshot, "快照不应随实例变化")
        assertEquals(
            listOf(
                Message(Role.User, MessageContent.of("first")),
                Message(Role.User, MessageContent.of("second")),
                Message(Role.Assistant, MessageContent.of("hi")),
                Message(Role.User, MessageContent.of("third")),
            ),
            ds.messages,
        )
    }

    @Test
    fun `a cancelled round only rolls back its own messages`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var calls = 0
        val backend = GatedBackend {
            calls++
            val started = if (calls == 1) firstStarted else secondStarted
            flow {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val ds = statefulDeepseek(backend, prompt = "sys")

        val first = launch { ds.chatStream("A").collect { } }
        withTimeout(5_000) { firstStarted.await() }

        // 单会话语义：启动新流会取消旧流；旧流的回滚只该删掉它自己写入的消息
        val second = launch { ds.chatStream("B").collect { } }
        withTimeout(5_000) { secondStarted.await() }
        withTimeout(5_000) { first.join() }

        assertEquals(
            listOf(system, Message(Role.User, MessageContent.of("B"))),
            ds.messages,
            "第一轮的回滚不得删除第二轮写入的 user 消息",
        )

        ds.cancelStream()
        withTimeout(5_000) { second.join() }

        assertEquals(listOf(system), ds.messages, "第二轮取消后应只回滚自己的 user 消息")
    }

    @Test
    fun `replaced history is the context of the next stream`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val backend = GatedBackend { messages ->
            seen += messages
            flowOf(ChatChunk.Done(1, 1, 1))
        }
        val ds = statefulDeepseek(backend)

        ds.replaceHistory(listOf(Message(Role.User, MessageContent.of("restored-1")), Message(Role.Assistant, MessageContent.of("restored-2"))))
        ds.chatStream("next").collect { }

        assertEquals(
            listOf(
                Message(Role.User, MessageContent.of("restored-1")),
                Message(Role.Assistant, MessageContent.of("restored-2")),
                Message(Role.User, MessageContent.of("next")),
            ),
            seen.single(),
            "替换后的历史应作为下一次请求的上下文",
        )
    }
}
