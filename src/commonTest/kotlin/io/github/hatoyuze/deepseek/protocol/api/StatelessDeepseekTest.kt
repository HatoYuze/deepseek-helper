package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.ReasoningEffort
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatelessDeepseekTest {

    @Test
    fun `statelessDeepseek DSL assembles model prompt config and tools`() {
        val ds = statelessDeepseek("sk-test-key") {
            prompt = "You are a stateless assistant"
            model { flash() }
            config {
                maxTokens = 100
                temperature = 0.5
                thinkingMode = ThinkingMode.Max
            }
            tools {
                tool("ping") {
                    description = "Ping the assistant"
                    parameters { }
                    execute { _, _ -> "pong" }
                }
            }
        }

        assertEquals("sk-test-key", ds.apiKey)
        assertEquals("deepseek-v4-flash", ds.resolvedModel.id)
        assertEquals("You are a stateless assistant", ds.systemPromptMessage?.content)
        assertEquals(100, ds.config.maxTokens)
        assertEquals(0.5, ds.config.temperature)
        assertEquals(ReasoningEffort.MAX, (ds.config.thinkingMode as ThinkingMode.WithEffort).effort)
        assertEquals(1, ds.toolHost?.getDefinitions()?.size)
    }

    @Test
    fun `statelessDeepseek sharedConfig variant reuses config instance`() {
        val shared = ChatConfig().apply { maxTokens = 512 }

        val ds = statelessDeepseek("sk-test-key", shared) { }

        assertTrue(ds.config === shared)
        assertEquals(512, ds.config.maxTokens)
    }

    @Test
    fun `handleToolCalls writes to provided history not instance history`() = runTest {
        val ds = statelessDeepseek("sk-test-key") { prompt = "sys" }
        ds.toolHost = makePingHost()
        val history = mutableListOf<Message>()

        val result = ds.handleToolCalls(
            listOf(ChatChunk.ToolCallRequest(ToolCall("call_1", "ping", "{}"))),
            history,
        )

        assertTrue(result.isNotEmpty(), "Expected handleToolCalls to execute the tool")
        assertEquals(2, history.size, "Expected assistant + tool message in provided history")
        assertEquals(Role.Assistance, history[0].role)
        assertEquals(Role.Tool, history[1].role)
        assertEquals("call_1", history[1].toolCallId)
    }

    private fun makePingHost(): ToolCallHost {
        val host = ToolCallHost(ToolRegistry())
        host.register("ping", "Ping the assistant", schema = parametersOf { }) { _, _ ->
            """{"reply":"pong"}"""
        }
        return host
    }
}
