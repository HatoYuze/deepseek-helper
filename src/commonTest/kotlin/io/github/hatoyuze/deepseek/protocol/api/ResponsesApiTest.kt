package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekResponsesApiImpl
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekStandardApiImpl
import io.github.hatoyuze.deepseek.protocol.api.impl.extractResponsesInstructions
import io.github.hatoyuze.deepseek.protocol.api.impl.toResponsesInputItems
import io.github.hatoyuze.deepseek.protocol.api.impl.toResponsesToolChoice
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice
import io.github.hatoyuze.deepseek.toolcall.DEEPSEEK_WEB_SEARCH_TOOL
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCallType
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalDeepseekApi::class)
class ResponsesApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 输入 items 转换 ──

    @Test
    fun `toResponsesInputItems maps user and assistant text plus reasoning`() {
        val messages = listOf(
            Message(Role.User, "hello"),
            Message(Role.Assistance, "hi there", reasoningContent = "thinking..."),
        )

        val items = messages.toResponsesInputItems().jsonArray
        assertEquals(3, items.size)

        val user = items[0].jsonObject
        assertEquals("message", user["type"]!!.jsonPrimitive.content)
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val userContent = user["content"]!!.jsonArray[0].jsonObject
        assertEquals("input_text", userContent["type"]!!.jsonPrimitive.content)
        assertEquals("hello", userContent["text"]!!.jsonPrimitive.content)

        val reasoning = items[1].jsonObject
        assertEquals("reasoning", reasoning["type"]!!.jsonPrimitive.content)
        assertEquals("rs_1", reasoning["id"]!!.jsonPrimitive.content)
        val reasoningText = reasoning["content"]!!.jsonArray[0].jsonObject
        assertEquals("reasoning_text", reasoningText["type"]!!.jsonPrimitive.content)
        assertEquals("thinking...", reasoningText["text"]!!.jsonPrimitive.content)

        val assistant = items[2].jsonObject
        assertEquals("message", assistant["type"]!!.jsonPrimitive.content)
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val assistantContent = assistant["content"]!!.jsonArray[0].jsonObject
        assertEquals("output_text", assistantContent["type"]!!.jsonPrimitive.content)
        assertEquals("hi there", assistantContent["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toResponsesInputItems maps function and web search tool calls`() {
        val messages = listOf(
            Message(
                role = Role.Assistance,
                content = null,
                toolCalls = listOf(
                    ToolCall("call_1", "get_weather", """{"city":"Hangzhou"}"""),
                    ToolCall(
                        "ws_1",
                        DEEPSEEK_WEB_SEARCH_TOOL,
                        """{"type":"search","query":"DeepSeek"}""",
                        ToolCallType.WEB_SEARCH_CALL,
                    ),
                ),
            ),
        )

        val items = messages.toResponsesInputItems().jsonArray
        assertEquals(2, items.size)

        val functionCall = items[0].jsonObject
        assertEquals("function_call", functionCall["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", functionCall["call_id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", functionCall["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"Hangzhou"}""", functionCall["arguments"]!!.jsonPrimitive.content)

        val webSearch = items[1].jsonObject
        assertEquals("web_search_call", webSearch["type"]!!.jsonPrimitive.content)
        assertEquals("ws_1", webSearch["id"]!!.jsonPrimitive.content)
        val action = webSearch["action"]!!.jsonObject
        assertEquals("search", action["type"]!!.jsonPrimitive.content)
        assertEquals("DeepSeek", action["query"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toResponsesInputItems maps tool results and skips magic web search tool`() {
        val messages = listOf(
            Message(Role.Tool, "42", toolCallId = "call_1"),
            Message(Role.Tool, """{"status":"completed"}""", name = DEEPSEEK_WEB_SEARCH_TOOL),
        )

        val items = messages.toResponsesInputItems().jsonArray
        assertEquals(1, items.size)

        val output = items[0].jsonObject
        assertEquals("function_call_output", output["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", output["call_id"]!!.jsonPrimitive.content)
        assertEquals("42", output["output"]!!.jsonPrimitive.content)
    }

    // ── instructions 提取 ──

    @Test
    fun `extractResponsesInstructions takes first non-empty system message`() {
        val messages = listOf(
            Message(Role.System, ""),
            Message(Role.System, "You are helpful"),
            Message(Role.User, "hi"),
        )

        val (instructions, rest) = extractResponsesInstructions(messages)
        assertEquals("You are helpful", instructions)
        assertEquals(2, rest.size)
        assertEquals(Role.System, rest[0].role)
        assertEquals(Role.User, rest[1].role)
    }

    @Test
    fun `extractResponsesInstructions returns null when no system message`() {
        val messages = listOf(Message(Role.User, "hi"))
        val (instructions, rest) = extractResponsesInstructions(messages)
        assertNull(instructions)
        assertEquals(messages, rest)
    }

    // ── tool_choice 映射 ──

    @Test
    fun `toResponsesToolChoice maps modes and named tools`() {
        assertEquals(json.parseToJsonElement("\"none\""), ToolChoice.None.toResponsesToolChoice())
        assertEquals(json.parseToJsonElement("\"auto\""), ToolChoice.Auto.toResponsesToolChoice())
        assertEquals(json.parseToJsonElement("\"required\""), ToolChoice.Required.toResponsesToolChoice())
        assertEquals(
            json.parseToJsonElement("""{"type":"function","name":"get_weather"}"""),
            ToolChoice.Named("get_weather").toResponsesToolChoice(),
        )
        assertEquals(
            json.parseToJsonElement("""{"type":"web_search"}"""),
            ToolChoice.Named(DEEPSEEK_WEB_SEARCH_TOOL).toResponsesToolChoice(),
        )
        assertNull((null as ToolChoice?).toResponsesToolChoice())
    }

    // ── 工具管道魔法名 ──

    @Test
    fun `ToolCallHost execute returns success for magic web search name`() = runTest {
        val host = ToolCallHost(ToolRegistry())
        val result = host.execute(
            ToolCall("call_1", DEEPSEEK_WEB_SEARCH_TOOL, """{"type":"search"}""", ToolCallType.WEB_SEARCH_CALL),
            ToolExecutionContext("", ""),
        )
        assertFalse(result.isError)
        assertEquals("""{"status":"completed"}""", result.content)
    }

    // ── 选择器与常量 ──

    @Test
    fun `Deepseek selects backend by api selector`() {
        assertTrue(Deepseek("sk-test", api = DeepseekApi.RESPONSES).backend is DeepseekResponsesApiImpl)
        assertTrue(Deepseek("sk-test").backend is DeepseekStandardApiImpl)
    }

    @Test
    fun `deepseek DSL passes api selector through`() {
        val ds = deepseek("sk-test") { api = DeepseekApi.RESPONSES }
        assertTrue(ds.backend is DeepseekResponsesApiImpl)
    }

    @Test
    fun `magic web search tool constant matches spec`() {
        assertEquals("_deepseek__web_search", DEEPSEEK_WEB_SEARCH_TOOL)
    }
}
