package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `system message serializes correctly`() {
        val msg = Message(role = Role.System, content = "You are helpful")
        val str = json.encodeToString(serializer<Message>(), msg)
        val obj = json.parseToJsonElement(str).jsonObject
        assertEquals("system", obj["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user message serializes correctly`() {
        val msg = Message(role = Role.User, content = "Hello")
        val str = json.encodeToString(serializer<Message>(), msg)
        val obj = json.parseToJsonElement(str).jsonObject
        assertEquals("user", obj["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant message with tool calls`() {
        val msg = Message(
            role = Role.Assistance,
            content = null,
            toolCalls = listOf(
                ToolCall("call_001", "get_weather", """{"location":"Hangzhou"}"""),
            ),
        )
        val str = json.encodeToString(serializer<Message>(), msg)
        val obj = json.parseToJsonElement(str).jsonObject
        assertEquals("assistant", obj["role"]!!.jsonPrimitive.content)
        val tcs = obj["tool_calls"]!!.jsonArray
        assertEquals(1, tcs.size)
        assertEquals("call_001", tcs[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool message serializes correctly`() {
        val msg = Message(role = Role.Tool, content = """{"temperature": 25}""", toolCallId = "call_001")
        val str = json.encodeToString(serializer<Message>(), msg)
        val obj = json.parseToJsonElement(str).jsonObject
        assertEquals("tool", obj["role"]!!.jsonPrimitive.content)
        assertEquals("call_001", obj["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `message list round-trip`() {
        val messages = listOf(
            Message(role = Role.System, content = "sys"),
            Message(role = Role.User, content = "hi"),
            Message(role = Role.Assistance, content = "hello"),
        )
        val listSerializer = ListSerializer(serializer<Message>())
        val str = json.encodeToString(listSerializer, messages)
        val restored = json.decodeFromString(listSerializer, str)
        assertEquals(3, restored.size)
        assertEquals(Role.System, restored[0].role)
        assertEquals(Role.User, restored[1].role)
        assertEquals(Role.Assistance, restored[2].role)
    }

    @Test
    fun `assistant with tool calls round-trip`() {
        val msg = Message(
            role = Role.Assistance,
            content = null,
            toolCalls = listOf(
                ToolCall("call_001", "get_weather", """{"location":"HZ"}"""),
            ),
        )
        val str = json.encodeToString(serializer<Message>(), msg)
        val restored = json.decodeFromString(serializer<Message>(), str)
        assertEquals(1, restored.toolCalls!!.size)
        assertEquals("call_001", restored.toolCalls[0].id)
        assertEquals("get_weather", restored.toolCalls[0].name)
        assertEquals("""{"location":"HZ"}""", restored.toolCalls[0].arguments)
    }
}
