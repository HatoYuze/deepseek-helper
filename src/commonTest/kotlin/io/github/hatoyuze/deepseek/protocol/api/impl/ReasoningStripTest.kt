package io.github.hatoyuze.deepseek.protocol.api.impl

import io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalDeepseekApi::class)
class ReasoningStripTest {

    @Test
    fun `withoutReasoningContent strips reasoning but keeps content`() {
        val original = Message(Role.Assistance, "hi", reasoningContent = "thinking...")

        val stripped = listOf(original).withoutReasoningContent()

        assertEquals("hi", stripped[0].content)
        assertNull(stripped[0].reasoningContent)
        assertEquals("thinking...", original.reasoningContent, "原历史中的思考内容应保留")
    }

    @Test
    fun `withoutReasoningContent keeps other tool call fields`() {
        val original = Message(Role.Assistance, null, toolCalls = emptyList())
        val stripped = listOf(original).withoutReasoningContent()
        assertEquals(emptyList(), stripped[0].toolCalls)
    }

    @Test
    fun `withoutReasoningContent returns the same list when nothing to strip`() {
        val messages = listOf(
            Message(Role.User, "hello"),
            Message(Role.Assistance, "hi"),
        )

        val stripped = messages.withoutReasoningContent()

        assertEquals(messages, stripped)
        assertSame(messages, stripped, "无 reasoningContent 时不应复制消息列表")
    }
}
