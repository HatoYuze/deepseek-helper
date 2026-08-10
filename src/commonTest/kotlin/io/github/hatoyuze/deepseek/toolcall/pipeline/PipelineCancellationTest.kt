package io.github.hatoyuze.deepseek.toolcall.pipeline
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallPhase
import io.github.hatoyuze.deepseek.toolcall.pipeline.plugins.RetryPlugin
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineCancellationTest {

    @Test
    fun `CancellationException propagates through pipeline`() = runTest {
        val host = ToolCallHost(ToolRegistry())
        host.register("boom", "boom", schema = parametersOf { }) { _, _ ->
            throw CancellationException("cancelled")
        }

        val e = assertFailsWith<CancellationException> {
            host.execute(ToolCall("c1", "boom", "{}"), ToolExecutionContext("u", "s"))
        }
        assertEquals("cancelled", e.message)
    }

    @Test
    fun `RetryPlugin does not retry CancellationException`() = runTest {
        var attempts = 0
        val host = ToolCallHost(ToolRegistry())
        host.register("boom", "boom", schema = parametersOf { }) { _, _ ->
            attempts++
            throw CancellationException("cancelled")
        }
        RetryPlugin(maxAttempts = 3, baseDelayMs = 10).install(host)

        assertFailsWith<CancellationException> {
            host.execute(ToolCall("c1", "boom", "{}"), ToolExecutionContext("u", "s"))
        }
        assertEquals(1, attempts, "取消不应触发重试")
    }

    @Test
    fun `ERROR phase interceptors run on failure`() = runTest {
        val host = ToolCallHost(ToolRegistry())
        host.register("fail", "fail", schema = parametersOf { }) { _, _ ->
            throw IllegalStateException("boom")
        }
        var errorSeen: Throwable? = null
        host.intercept(ToolCallPhase.ERROR) { ctx ->
            errorSeen = ctx.error
            ctx.proceed()
        }

        val result = host.execute(ToolCall("c1", "fail", "{}"), ToolExecutionContext("u", "s"))

        assertTrue(result.isError)
        assertNotNull(errorSeen, "ERROR 阶段应能拿到捕获的异常")
        assertTrue(errorSeen!!.message!!.contains("boom"))
    }
}
