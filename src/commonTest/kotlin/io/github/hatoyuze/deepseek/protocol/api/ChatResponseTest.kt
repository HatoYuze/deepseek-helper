package io.github.hatoyuze.deepseek.protocol.api
import io.github.hatoyuze.deepseek.protocol.api.entity.ChatCompletionChunk
import io.github.hatoyuze.deepseek.protocol.api.entity.FinishReason
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ChatResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize content delta chunk`() {
        val raw = """
            {"id":"chatcmpl-001","object":"chat.completion.chunk","created":1710000000,
             "model":"deepseek-v4-pro","choices":[{"index":0,
             "delta":{"content":"Hello","role":"assistant"},"finish_reason":null}]}
        """.trimIndent()

        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals("chatcmpl-001", chunk.id)
        assertEquals("chat.completion.chunk", chunk.obj)
        assertEquals("deepseek-v4-pro", chunk.model)
        assertEquals(1, chunk.choices.size)
        assertEquals("Hello", chunk.choices[0].delta.content)
        assertEquals("assistant", chunk.choices[0].delta.role)
        assertNull(chunk.choices[0].finishReason)
        assertNull(chunk.usage)
    }

    @Test
    fun `deserialize reasoning content delta chunk`() {
        val raw = """
            {"id":"chatcmpl-002","object":"chat.completion.chunk","created":1710000001,
             "model":"deepseek-v4-pro","choices":[{"index":0,
             "delta":{"reasoning_content":"Let me think...","role":"assistant"},
             "finish_reason":null}]}
        """.trimIndent()

        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertNull(chunk.choices[0].delta.content)
        assertEquals("Let me think...", chunk.choices[0].delta.reasoningContent)
    }

    @Test
    fun `deserialize chunk with finish_reason stop`() {
        val raw = """
            {"id":"chatcmpl-003","object":"chat.completion.chunk","created":1710000002,
             "model":"deepseek-v4-pro","choices":[{"index":0,
             "delta":{"content":""},"finish_reason":"stop"}]}
        """.trimIndent()

        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals(FinishReason.STOP, chunk.choices[0].finishReason)
    }

    @Test
    fun `deserialize chunk with finish_reason length`() {
        val raw = """
            {"id":"chatcmpl-004","object":"chat.completion.chunk","created":1710000003,
             "model":"deepseek-v4-flash","choices":[{"index":0,
             "delta":{},"finish_reason":"length"}]}
        """.trimIndent()

        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals(FinishReason.LENGTH, chunk.choices[0].finishReason)
    }

    @Test
    fun `deserialize tool call delta chunk`() {
        val raw = """
            {"id":"chatcmpl-005","object":"chat.completion.chunk","created":1710000004,
             "model":"deepseek-v4-pro","choices":[{"index":0,
             "delta":{"tool_calls":[{"index":0,"id":"call_001",
             "function":{"name":"get_weather","arguments":"{\"location\":\"Hangzhou\"}"}}]},
             "finish_reason":"tool_calls"}]}
        """.trimIndent()

        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals(FinishReason.TOOL_CALLS, chunk.choices[0].finishReason)
        val tc = chunk.choices[0].delta.toolCalls!![0]
        assertEquals(0, tc.index)
        assertEquals("call_001", tc.id)
        assertEquals("get_weather", tc.function!!.name)
        assertEquals("{\"location\":\"Hangzhou\"}", tc.function.arguments)
    }

    @Test
    fun `deserialize usage chunk`() {
        val raw = """
            {"id":"chatcmpl-006","object":"chat.completion.chunk","created":1710000005,
             "model":"deepseek-v4-pro","choices":[],
             "usage":{"prompt_tokens":10,"completion_tokens":50,"total_tokens":60}}
        """.trimIndent()

        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals(0, chunk.choices.size)
        assertNotNull(chunk.usage)
        assertEquals(10L, chunk.usage.promptTokens)
        assertEquals(50L, chunk.usage.completionTokens)
        assertEquals(60L, chunk.usage.totalTokens)
    }

    @Test
    fun `deserialize chunk with system_fingerprint`() {
        val raw = """
            {"id":"chatcmpl-007","object":"chat.completion.chunk","created":1710000006,
             "model":"deepseek-v4-pro","system_fingerprint":"fp_abc123",
             "choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}
        """.trimIndent()

        val chunk = json.decodeFromString<ChatCompletionChunk>(raw)
        assertEquals("fp_abc123", chunk.systemFingerprint)
    }
}
