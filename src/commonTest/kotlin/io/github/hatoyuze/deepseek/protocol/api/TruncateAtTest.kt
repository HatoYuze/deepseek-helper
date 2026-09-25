package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Deepseek.truncateAt] 语义固化测试（弃用前的行为契约 + fail-fast）。
 *
 * - 正常下标：保留 `[0, index]`，与旧版契约一致；
 * - 越界下标：抛 [IndexOutOfBoundsException]（旧版静默无操作，已废弃）。
 */
@Suppress("DEPRECATION")
class TruncateAtTest {

    private val system = Message(Role.System, "sys")
    private val user = Message(Role.User, "u")
    private val assistant = Message(Role.Assistance, "a")

    private fun deepseekWithHistory(): Deepseek =
        statefulDeepseek(GatedBackend(), prompt = "sys").apply {
            replaceHistory(listOf(system, user, assistant))
        }

    @Test
    fun `truncateAt keeps the prefix up to the given index`() {
        val ds = deepseekWithHistory()

        ds.truncateAt(1)

        assertEquals(listOf(system, user), ds.messages)
        assertEquals(2, ds.getMessageCount())
    }

    @Test
    fun `truncateAt keeps only the first message for index zero`() {
        val ds = deepseekWithHistory()

        ds.truncateAt(0)

        assertEquals(listOf(system), ds.messages)
        assertEquals(1, ds.getMessageCount())
    }

    @Test
    fun `truncateAt accepts the last index without changing the history`() {
        val ds = deepseekWithHistory()

        ds.truncateAt(ds.getMessageCount() - 1)

        assertEquals(listOf(system, user, assistant), ds.messages)
    }

    @Test
    fun `truncateAt fails fast for out-of-range indexes`() {
        val ds = deepseekWithHistory()

        val past = assertFailsWith<IndexOutOfBoundsException> { ds.truncateAt(3) }
        assertTrue(past.message.orEmpty().contains("size=3"), "异常应说明当前 size，实际: ${past.message}")
        assertFailsWith<IndexOutOfBoundsException> { ds.truncateAt(-1) }
        assertEquals(listOf(system, user, assistant), ds.messages, "越界调用不得改动历史")
    }

    @Test
    fun `truncateAt on an empty history always fails and clearHistory is the way out`() {
        val ds = statefulDeepseek(GatedBackend())

        assertTrue(ds.messages.isEmpty(), "无 prompt 时初始历史为空")
        assertFailsWith<IndexOutOfBoundsException> { ds.truncateAt(0) }
        assertFailsWith<IndexOutOfBoundsException> { ds.truncateAt(-1) }

        ds.clearHistory()

        assertTrue(ds.messages.isEmpty())
        assertEquals(0, ds.getMessageCount())
    }

    @Test
    fun `truncateAt during an active stream cancels it and survives its rollback`() = runTest {
        val started = CompletableDeferred<Unit>()
        val backend = GatedBackend {
            flow {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val ds = statefulDeepseek(backend, prompt = "sys")
        ds.addMessage(user)
        ds.addMessage(assistant)
        val job = launch { ds.chatStream("hello").collect { } }
        withTimeout(5_000) { started.await() }

        ds.truncateAt(0)
        withTimeout(5_000) { job.join() }

        assertTrue(job.isCancelled, "截断历史应取消活跃流")
        assertEquals(listOf(system), ds.messages, "被取消流的回滚不得撤销本次截断")
    }
}
