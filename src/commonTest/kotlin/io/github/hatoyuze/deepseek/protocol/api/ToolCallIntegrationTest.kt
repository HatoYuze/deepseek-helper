package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolCallIntegrationTest {

    @Test
    fun `handleToolCalls executes tool and appends correct messages to history`() = runTest {
        val ds = Deepseek(apiKey = "test-key")
        ds.toolHost = makeWeatherHost()

        val result = ds.handleToolCalls(listOf(
            ChatChunk.ToolCallRequest(ToolCall("call_1", "get_weather", """{"city":"Hangzhou"}""")),
        ))

        assertTrue(result.isNotEmpty(), "Expected handleToolCalls to execute the tool")
        val msgs = ds.messages
        assertEquals(2, msgs.size, "Expected 2 messages: assistant + tool")

        // assistant message with tool_calls
        val assistant = msgs[0]
        assertEquals(Role.Assistance, assistant.role)
        val toolCalls = assistant.toolCalls!!
        assertEquals(1, toolCalls.size)
        assertEquals("call_1", toolCalls[0].id)
        assertEquals("get_weather", toolCalls[0].name)
        assertEquals("""{"city":"Hangzhou"}""", toolCalls[0].arguments)

        // tool result message
        val tool = msgs[1]
        assertEquals(Role.Tool, tool.role)
        assertEquals("call_1", tool.toolCallId)
        assertTrue(tool.content!!.contains("Hangzhou"), "Tool result should contain city name")
    }

    @Test
    fun `handleToolCalls returns false when toolHost is null`() = runTest {
        val ds = Deepseek(apiKey = "test-key")

        val result = ds.handleToolCalls(listOf(
            ChatChunk.ToolCallRequest(ToolCall("call_1", "some_tool", "{}")),
        ))

        assertTrue(result.isEmpty())
        assertTrue(ds.messages.isEmpty(), "No messages should be added when toolHost is null")
    }

    @Test
    fun `handleToolCalls returns false for empty pending calls`() = runTest {
        val ds = Deepseek(apiKey = "test-key")
        ds.toolHost = makeWeatherHost()

        val result = ds.handleToolCalls(emptyList())

        assertTrue(result.isEmpty())
        assertEquals(0, ds.messages.size)
    }

    private fun makeWeatherHost(): ToolCallHost {
        val host = ToolCallHost(ToolRegistry())
        val schema = parametersOf {
            string("city") { required = true }
        }
        host.register("get_weather", "Get weather by city", schema = schema) { bag, _ ->
            val city = bag.getString("city")
            """{"city":"$city","weather":"sunny","temperature":25}"""
        }
        return host
    }
}
