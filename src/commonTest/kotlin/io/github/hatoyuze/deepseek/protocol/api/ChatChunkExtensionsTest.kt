package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatChunkExtensionsTest {

    @Test
    fun `collectResponse accumulates content and thinking`() = runTest {
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "", reasoningContent = "Let me think..."),
            ChatChunk.ContentDelta(content = "Hello", reasoningContent = null),
            ChatChunk.ContentDelta(content = " World", reasoningContent = null),
            ChatChunk.Done(promptTokens = 10, completionTokens = 5, totalTokens = 15),
        )

        val response = chunks.collectResponse()

        assertEquals("Let me think...", response.thinkingContent)
        assertEquals("Hello World", response.content)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals(10, response.usage.promptTokens)
        assertEquals(5, response.usage.completionTokens)
        assertEquals(15, response.usage.totalTokens)
    }

    @Test
    fun `collectResponse captures tool calls`() = runTest {
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "", reasoningContent = null),
            ChatChunk.ToolCallRequest(ToolCall("call_1", "get_weather", """{"city":"HZ"}""")),
            ChatChunk.ToolCallRequest(ToolCall("call_2", "get_time", "{}")),
            ChatChunk.Done(promptTokens = 20, completionTokens = 10, totalTokens = 30),
        )

        val response = chunks.collectResponse()

        assertEquals(2, response.toolCalls.size)
        assertEquals("call_1", response.toolCalls[0].id)
        assertEquals("get_weather", response.toolCalls[0].name)
        assertEquals("""{"city":"HZ"}""", response.toolCalls[0].arguments)
    }

    @Test
    fun `collectResponse returns null thinkingContent when no reasoning`() = runTest {
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "Just content", reasoningContent = null),
            ChatChunk.Done(promptTokens = 1, completionTokens = 1, totalTokens = 2),
        )

        val response = chunks.collectResponse()

        assertEquals(null, response.thinkingContent)
        assertEquals("Just content", response.content)
    }

    @Test
    fun `collectResponse defaults usage to zero when no Done chunk`() = runTest {
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "Hi", reasoningContent = null),
        )

        val response = chunks.collectResponse()

        assertEquals(0, response.usage.totalTokens)
    }

    @Test
    fun `onThinking triggers for reasoning content`() = runTest {
        val thinkingParts = mutableListOf<String>()
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "", reasoningContent = "Part 1"),
            ChatChunk.ContentDelta(content = "", reasoningContent = "Part 2"),
            ChatChunk.ContentDelta(content = "Result", reasoningContent = null),
            ChatChunk.Done(promptTokens = 1, completionTokens = 1, totalTokens = 2),
        )

        chunks.onThinking { thinkingParts.add(it) }.collectResponse()

        assertEquals(listOf("Part 1", "Part 2"), thinkingParts)
    }

    @Test
    fun `onContent triggers for regular content`() = runTest {
        val contentParts = mutableListOf<String>()
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "Hello", reasoningContent = null),
            ChatChunk.ContentDelta(content = " World", reasoningContent = null),
            ChatChunk.Done(promptTokens = 1, completionTokens = 1, totalTokens = 2),
        )

        chunks.onContent { contentParts.add(it) }.collectResponse()

        assertEquals(listOf("Hello", " World"), contentParts)
    }

    @Test
    fun `onContent skips empty strings`() = runTest {
        val contentParts = mutableListOf<String>()
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "", reasoningContent = null),
            ChatChunk.ContentDelta(content = "Valid", reasoningContent = null),
            ChatChunk.Done(promptTokens = 1, completionTokens = 1, totalTokens = 2),
        )

        chunks.onContent { contentParts.add(it) }.collectResponse()

        assertEquals(listOf("Valid"), contentParts)
    }

    @Test
    fun `onToolCall triggers for tool requests`() = runTest {
        val toolCalls = mutableListOf<String>()
        val chunks: Flow<ChatChunk> = flowOf(
            ChatChunk.ContentDelta(content = "Query", reasoningContent = null),
            ChatChunk.ToolCallRequest(ToolCall("call_1", "search", """{"q":"test"}""")),
            ChatChunk.Done(promptTokens = 1, completionTokens = 1, totalTokens = 2),
        )

        chunks.onToolCall { toolCalls.add(it.call.name) }.collectResponse()

        assertEquals(listOf("search"), toolCalls)
    }
}
