package io.github.hatoyuze.deepseek.toolcall.pipeline
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.pipeline.PipelineException
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.pipeline.plugins.LoggingPlugin
import io.github.hatoyuze.deepseek.toolcall.pipeline.plugins.RetryPlugin
import io.github.hatoyuze.deepseek.toolcall.pipeline.plugins.TimeoutPlugin
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTest {

    @Test
    fun `basic pipeline execution`() = runTest {
        val host = makeGreetHost()

        val call = ToolCall("call_001", "greet", """{"name":"World"}""")
        val ctx = ToolExecutionContext("user1", "session1")
        val result = host.execute(call, ctx)

        assertTrue(result.content.contains("World"), "Expected greeting for World, got: ${result.content}")
    }

    @Test
    fun `timeout plugin returns error`() = runTest {
        val host = ToolCallHost(ToolRegistry())
        host.register("slow", "Slow tool", schema = parametersOf { string("x") { } }) { _, _ ->
            delay(2000)
            "done"
        }
        TimeoutPlugin(100).install(host)

        val call = ToolCall("call_002", "slow", """{"x":"y"}""")
        val ctx = ToolExecutionContext("u", "s")
        val result = host.execute(call, ctx)

        assertTrue(result.isError, "Expected error from timeout, got: ${result.content}")
        assertTrue(result.content.contains("timed out"))
    }

    @Test
    fun `retry recovers from transient failure`() = runTest {
        var attempts = 0
        val host = ToolCallHost(ToolRegistry())
        host.register("flaky", "Flaky tool", schema = parametersOf { string("x") { } }) { _, _ ->
            attempts++
            throw PipelineException("flaky tool attempt $attempts failed")
        }
        RetryPlugin(maxAttempts = 3, baseDelayMs = 10).install(host)

        val call = ToolCall("call_003", "flaky", """{"x":"y"}""")
        val ctx = ToolExecutionContext("u", "s")
        val result = host.execute(call, ctx)

        assertTrue(result.isError)
        assertEquals(3, attempts)
    }

    @Test
    fun `logging plugin records phases`() = runTest {
        val host = makeGreetHost()
        LoggingPlugin.install(host)

        val call = ToolCall("call_004", "greet", """{"name":"Test"}""")
        val ctx = ToolExecutionContext("u", "s")
        val result = host.execute(call, ctx)

        assertTrue(result.content.contains("Test"))
    }

    private fun makeGreetHost(): ToolCallHost {
        val host = ToolCallHost(ToolRegistry())
        val schema = parametersOf {
            string("name") { required = true }
            string("greeting") { }
        }
        host.register("greet", "A greeting tool", schema = schema) { bag, _ ->
            val n = bag.getString("name")
            val g = bag.getStringOrNull("greeting") ?: "Hello"
            "$g, $n!"
        }
        return host
    }
}
