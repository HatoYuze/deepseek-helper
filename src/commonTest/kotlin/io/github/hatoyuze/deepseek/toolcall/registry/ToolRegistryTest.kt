package io.github.hatoyuze.deepseek.toolcall.registry

import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutor
import io.github.hatoyuze.deepseek.toolcall.executor.ToolResult
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ToolRegistryTest {

    private val json = Json

    @Test
    fun `getDefinitions caches the list and invalidates on register`() {
        val registry = ToolRegistry()
        val noop = object : ToolExecutor {
            override suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult =
                ToolResult.success(call.id, "{}")
        }
        val firstDef = ToolDefinition("a", "desc", parameters = json.parseToJsonElement("{}"))
        registry.register(firstDef, noop)

        val first = registry.getDefinitions()
        val second = registry.getDefinitions()
        assertSame(first, second, "未注册新工具时应复用缓存列表")

        val secondDef = ToolDefinition("b", "desc", parameters = json.parseToJsonElement("{}"))
        registry.register(secondDef, noop)

        val third = registry.getDefinitions()
        assertNotSame(first, third, "注册新工具后应刷新缓存")
        assertEquals(2, third.size)
    }
}
