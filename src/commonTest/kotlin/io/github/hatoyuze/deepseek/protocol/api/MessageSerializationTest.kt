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
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent

class MessageSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `system message serializes correctly`() {
        val msg = Message(role = Role.System, content =MessageContent.of("You are helpful"))
        val str = json.encodeToString(serializer<Message>(), msg)
        val obj = json.parseToJsonElement(str).jsonObject
        assertEquals("system", obj["role"]!!.jsonPrimitive.content)
        assertEquals("You are helpful", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `user message serializes correctly`() {
        val msg = Message(role = Role.User, content =MessageContent.of("Hello"))
        val str = json.encodeToString(serializer<Message>(), msg)
        val obj = json.parseToJsonElement(str).jsonObject
        assertEquals("user", obj["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", obj["content"]!!.jsonPrimitive.content)
    }

    /**
     * `Role.Assistant` 的 wire 值必须仍然恰好是 `"assistant"`。
     *
     * 这条断言把「枚举常量改名」与「协议值」解耦：常量曾误拼为 `Assistance`，
     * 改名只应影响 Kotlin API，绝不能改动发给服务端的 role 字符串。
     */
    @Test
    fun `assistant role keeps its wire value after the rename`() {
        val objective = json.encodeToString(serializer<Role>(), Role.Assistant)
        assertEquals("\"assistant\"", objective)

        val message = Message(role = Role.Assistant, content = MessageContent.of("hi"))
        val obj = json.parseToJsonElement(json.encodeToString(serializer<Message>(), message)).jsonObject
        assertEquals("assistant", obj["role"]!!.jsonPrimitive.content)

        // 反序列化同样按 "assistant" 回落到 Role.Assistant
        val restored = json.decodeFromString(serializer<Message>(), """{"role":"assistant","content":"hi"}""")
        assertEquals(Role.Assistant, restored.role)

        // 其余三个角色一并锚定，防止将来再引入同类拼写漂移
        assertEquals("\"system\"", json.encodeToString(serializer<Role>(), Role.System))
        assertEquals("\"user\"", json.encodeToString(serializer<Role>(), Role.User))
        assertEquals("\"tool\"", json.encodeToString(serializer<Role>(), Role.Tool))
    }

    @Test
    fun `assistant message with tool calls`() {
        val msg = Message(
            role = Role.Assistant,
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
        val msg = Message(role = Role.Tool, content =MessageContent.of("""{"temperature": 25}"""), toolCallId = "call_001")
        val str = json.encodeToString(serializer<Message>(), msg)
        val obj = json.parseToJsonElement(str).jsonObject
        assertEquals("tool", obj["role"]!!.jsonPrimitive.content)
        assertEquals("call_001", obj["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `message list round-trip`() {
        val messages = listOf(
            Message(role = Role.System, content =MessageContent.of("sys")),
            Message(role = Role.User, content =MessageContent.of("hi")),
            Message(role = Role.Assistant, content =MessageContent.of("hello")),
        )
        val listSerializer = ListSerializer(serializer<Message>())
        val str = json.encodeToString(listSerializer, messages)
        val restored = json.decodeFromString(listSerializer, str)
        assertEquals(3, restored.size)
        assertEquals(Role.System, restored[0].role)
        assertEquals(Role.User, restored[1].role)
        assertEquals(Role.Assistant, restored[2].role)
    }

    @Test
    fun `assistant with tool calls round-trip`() {
        val msg = Message(
            role = Role.Assistant,
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
