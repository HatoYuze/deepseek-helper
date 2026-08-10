package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.toolcall.DEEPSEEK_WEB_SEARCH_TOOL
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCallType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolCallSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ToolCall serializes to nested wire shape`() {
        val call = ToolCall("call_1", "get_weather", """{"city":"HZ"}""")
        val obj = json.parseToJsonElement(json.encodeToString(serializer<ToolCall>(), call)).jsonObject

        assertEquals("call_1", obj["id"]!!.jsonPrimitive.content)
        assertEquals("function", obj["type"]!!.jsonPrimitive.content)
        val function = obj["function"]!!.jsonObject
        assertEquals("get_weather", function["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"HZ"}""", function["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `web search call serializes type`() {
        val call = ToolCall("ws_1", DEEPSEEK_WEB_SEARCH_TOOL, "{}", ToolCallType.WEB_SEARCH_CALL)
        val obj = json.parseToJsonElement(json.encodeToString(serializer<ToolCall>(), call)).jsonObject
        assertEquals("web_search_call", obj["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ToolCall round-trips through wire shape`() {
        val call = ToolCall("call_1", "get_weather", """{"city":"HZ"}""", ToolCallType.WEB_SEARCH_CALL)
        val str = json.encodeToString(serializer<ToolCall>(), call)
        val restored = json.decodeFromString(serializer<ToolCall>(), str)
        assertEquals(call, restored)
    }
}
