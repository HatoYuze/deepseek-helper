package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * [Deepseek] 历史整体替换/清空语义的固化测试（无网络：Fake 后端注入）。
 *
 * 契约要点：
 * - [Deepseek.replaceHistory] 是**精确替换**，不自动前置构造期 system prompt；
 * - 空列表等价于 [Deepseek.clearHistory]（重置为初始状态）；
 * - 传入列表被防御性拷贝，[Deepseek.messages] 返回调用时快照。
 */
class DeepseekHistoryTest {

    private val user1 = Message(Role.User, "u1")
    private val assistant1 = Message(Role.Assistance, "a1")
    private val user2 = Message(Role.User, "u2")
    private val system = Message(Role.System, "sys")

    @Test
    fun `replaceHistory installs the given list exactly when no prompt`() {
        val ds = statefulDeepseek(GatedBackend())

        ds.replaceHistory(listOf(user1, assistant1, user2))

        assertEquals(listOf(user1, assistant1, user2), ds.messages)
        assertEquals(3, ds.getMessageCount())
    }

    @Test
    fun `replaceHistory does not inject the construction prompt but empty input resets to it`() {
        val ds = statefulDeepseek(GatedBackend(), prompt = "sys")
        assertEquals(listOf(system), ds.messages, "构造期 prompt 应作为初始历史")

        ds.replaceHistory(listOf(user1, assistant1))

        assertEquals(listOf(user1, assistant1), ds.messages, "精确替换不应前置构造期 system prompt")

        ds.replaceHistory(emptyList())

        assertEquals(listOf(system), ds.messages, "空列表应重置为初始状态")
        assertEquals(1, ds.getMessageCount())
    }

    @Test
    fun `clearHistory resets to the construction prompt or to an empty list`() {
        val withPrompt = statefulDeepseek(GatedBackend(), prompt = "sys")
        withPrompt.replaceHistory(listOf(user1, assistant1))

        withPrompt.clearHistory()

        assertEquals(listOf(system), withPrompt.messages)
        assertEquals(1, withPrompt.getMessageCount())

        val withoutPrompt = statefulDeepseek(GatedBackend())
        withoutPrompt.replaceHistory(listOf(user1, assistant1))

        withoutPrompt.clearHistory()

        assertTrue(withoutPrompt.messages.isEmpty(), "无 prompt 时应清空为空历史")
        assertEquals(0, withoutPrompt.getMessageCount())
    }

    @Test
    fun `replaceHistory keeps count in sync when the list shrinks or grows`() {
        val ds = statefulDeepseek(GatedBackend())

        ds.replaceHistory(listOf(user1, assistant1, user2))
        assertEquals(3, ds.getMessageCount())

        ds.replaceHistory(listOf(user1))
        assertEquals(1, ds.getMessageCount())
        assertEquals(listOf(user1), ds.messages)

        val grown = (1..5).map { Message(Role.User, "u$it") }
        ds.replaceHistory(grown)
        assertEquals(5, ds.getMessageCount())
        assertEquals(ds.getMessageCount(), ds.messages.size)
        assertEquals(grown, ds.messages)
    }

    @Test
    fun `replaceHistory equals clearHistory plus step-by-step addMessage`() {
        val target = listOf(user1, assistant1, user2)

        val replaced = statefulDeepseek(GatedBackend())
        replaced.replaceHistory(target)
        val stepwise = statefulDeepseek(GatedBackend())
        stepwise.clearHistory()
        target.forEach { stepwise.addMessage(it) }
        assertEquals(stepwise.messages, replaced.messages, "无 prompt 时整体替换应等价于逐条追加")
        assertEquals(stepwise.getMessageCount(), replaced.getMessageCount())

        val replacedWithPrompt = statefulDeepseek(GatedBackend(), prompt = "sys")
        replacedWithPrompt.replaceHistory(listOf(system) + target)
        val stepwiseWithPrompt = statefulDeepseek(GatedBackend(), prompt = "sys")
        target.forEach { stepwiseWithPrompt.addMessage(it) }
        assertEquals(
            stepwiseWithPrompt.messages,
            replacedWithPrompt.messages,
            "显式传入 system 消息时应等价于「保留 prompt + 逐条追加」",
        )
    }

    @Test
    fun `replaceHistory copies the source list defensively and messages returns a snapshot`() {
        val ds = statefulDeepseek(GatedBackend())
        val source = mutableListOf(user1, assistant1)

        ds.replaceHistory(source)
        source.add(user2)

        assertEquals(listOf(user1, assistant1), ds.messages, "替换后修改源列表不应影响实例")

        val snapshot = ds.messages
        ds.addMessage(Message(Role.User, "later"))

        assertEquals(listOf(user1, assistant1), snapshot, "messages 返回快照，不应随实例变化")
        assertEquals(listOf(user1, assistant1, Message(Role.User, "later")), ds.messages)
        assertNotSame(snapshot, ds.messages, "每次读取 messages 都应返回独立快照")
    }

    @Test
    fun `public constructor seeds history with the system prompt`() {
        val ds = Deepseek("test-key", prompt = "sys")

        assertEquals(listOf(system), ds.messages)

        ds.replaceHistory(emptyList())

        assertEquals(listOf(system), ds.messages, "空列表替换应回到构造期初始历史")
    }

    @Test
    fun `history follows the prompt of the injected test core`() {
        // 锁住 internal 测试构造器：历史必须按注入的 core 重建，而不是默认 core
        val ds = statefulDeepseek(GatedBackend(), prompt = "injected")

        assertEquals(listOf(Message(Role.System, "injected")), ds.messages)

        ds.clearHistory()

        assertEquals(listOf(Message(Role.System, "injected")), ds.messages)
    }
}
