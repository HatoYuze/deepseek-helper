import com.github.hatoyuze.tool.dsl.parametersOf
import com.github.hatoyuze.tool.executor.ToolCall
import com.github.hatoyuze.tool.executor.ToolExecutionContext
import com.github.hatoyuze.tool.pipeline.ToolCallHost
import com.github.hatoyuze.tool.pipeline.ToolCallPhase
import com.github.hatoyuze.tool.pipeline.plugins.RetryPlugin
import com.github.hatoyuze.tool.registry.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineCancellationTest {

    @Test
    fun `CancellationException propagates through pipeline`() = runBlocking {
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
    fun `RetryPlugin does not retry CancellationException`() = runBlocking {
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
    fun `ERROR phase interceptors run on failure`() = runBlocking {
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
