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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `timeout declared before retry still applies on each attempt`() = runTest {
        var attempts = 0
        val host = ToolCallHost(ToolRegistry())
        host.register("slow", "Slow tool", schema = parametersOf { string("x") { } }) { _, _ ->
            attempts++
            delay(2_000) // 远超 100ms 超时
            "done"
        }
        // 声明顺序：先 timeout 后 retry（与项目示例一致）
        TimeoutPlugin(100).install(host)
        RetryPlugin(maxAttempts = 2, baseDelayMs = 10).install(host)

        val call = ToolCall("call_005", "slow", """{"x":"y"}""")
        val ctx = ToolExecutionContext("u", "s")
        val result = host.execute(call, ctx)

        assertTrue(result.isError, "超时应生效，结果: ${result.content}")
        assertTrue(result.content.contains("timed out"), "timeout 不应被 retry 跳过")
        assertEquals(2, attempts, "每次尝试都应在超时窗口内被终止并重试")
    }

    @Test
    fun `retry backoff follows exponential schedule`() = runTest {
        var attempts = 0
        val host = ToolCallHost(ToolRegistry())
        host.register("flaky", "Flaky tool", schema = parametersOf { string("x") { } }) { _, _ ->
            attempts++
            throw PipelineException("flaky tool attempt $attempts failed")
        }
        val start = currentTime
        RetryPlugin(maxAttempts = 3, baseDelayMs = 100, backoffMultiplier = 2).install(host)

        val call = ToolCall("call_006", "flaky", """{"x":"y"}""")
        val ctx = ToolExecutionContext("u", "s")
        val result = host.execute(call, ctx)

        assertEquals(3, attempts)
        assertTrue(result.isError)
        assertEquals(300, currentTime - start, "退避应为 100 + 200 = 300ms（指数），而非线性 200 + 400 = 600ms")
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
