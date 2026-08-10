package com.github.hatoyuze.protocol.api

import com.github.hatoyuze.protocol.api.entity.ReasoningEffort
import com.github.hatoyuze.protocol.api.entity.ResponseFormat
import com.github.hatoyuze.protocol.api.entity.StopToken
import com.github.hatoyuze.protocol.api.entity.ThinkingMode
import com.github.hatoyuze.protocol.api.entity.ToolChoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatParamsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── ReasoningEffort ──

    @Test
    fun `ReasoningEffort serializes to lowercase`() {
        assertEquals("\"max\"", json.encodeToString(ReasoningEffort.serializer(), ReasoningEffort.MAX))
        assertEquals("\"high\"", json.encodeToString(ReasoningEffort.serializer(), ReasoningEffort.HIGH))
    }

    @Test
    fun `ReasoningEffort round-trip`() {
        for (effort in ReasoningEffort.entries) {
            val str = json.encodeToString(ReasoningEffort.serializer(), effort)
            val restored = json.decodeFromString(ReasoningEffort.serializer(), str)
            assertEquals(effort, restored)
        }
    }

    // ── ResponseFormat ──

    @Test
    fun `ResponseFormat serializes to API values`() {
        assertEquals("\"text\"", json.encodeToString(ResponseFormat.serializer(), ResponseFormat.TEXT))
        assertEquals("\"json_object\"", json.encodeToString(ResponseFormat.serializer(), ResponseFormat.JSON_OBJECT))
    }

    @Test
    fun `ResponseFormat round-trip`() {
        for (fmt in ResponseFormat.entries) {
            val str = json.encodeToString(ResponseFormat.serializer(), fmt)
            val restored = json.decodeFromString(ResponseFormat.serializer(), str)
            assertEquals(fmt, restored)
        }
    }

    // ── ThinkingMode ──

    @Test
    fun `ThinkingMode WithEffort has correct properties`() {
        val mode = ThinkingMode.WithEffort(ReasoningEffort.HIGH)
        assertEquals(ReasoningEffort.HIGH, mode.effort)
    }

    @Test
    fun `ThinkingMode Max and High are WithEffort shortcuts`() {
        val max: ThinkingMode = ThinkingMode.Max
        val high: ThinkingMode = ThinkingMode.High
        assertTrue(max is ThinkingMode.WithEffort)
        assertTrue(high is ThinkingMode.WithEffort)
        assertEquals(ThinkingMode.WithEffort(ReasoningEffort.MAX), ThinkingMode.Max)
        assertEquals(ThinkingMode.WithEffort(ReasoningEffort.HIGH), ThinkingMode.High)
        assertEquals(ReasoningEffort.MAX, ThinkingMode.Max.effort)
        assertEquals(ReasoningEffort.HIGH, ThinkingMode.High.effort)
    }

    // ── StopToken ──

    @Test
    fun `StopToken Single toJsonElement`() {
        val token = StopToken.Single("STOP")
        val elem = token.toJsonElement()
        assertEquals("STOP", elem.jsonPrimitive.content)
    }

    @Test
    fun `StopToken Multiple toJsonElement`() {
        val token = StopToken.Multiple(listOf("END", "STOP"))
        val elem = token.toJsonElement()
        val arr = elem.jsonArray
        assertEquals(2, arr.size)
        assertEquals("END", arr[0].jsonPrimitive.content)
        assertEquals("STOP", arr[1].jsonPrimitive.content)
    }

    // ── ToolChoice ──

    @Test
    fun `ToolChoice modes serialize to strings`() {
        assertEquals("none", ToolChoice.None.toJsonElement().jsonPrimitive.content)
        assertEquals("auto", ToolChoice.Auto.toJsonElement().jsonPrimitive.content)
        assertEquals("required", ToolChoice.Required.toJsonElement().jsonPrimitive.content)
    }

    @Test
    fun `ToolChoice Named serializes to function object`() {
        val choice = ToolChoice.Named("my_function")
        val elem = choice.toJsonElement()
        val obj = elem.jsonObject
        assertEquals("function", obj["type"]!!.jsonPrimitive.content)
        assertEquals("my_function", obj["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    // ── ChatConfig defaults ──

    @Test
    fun `ChatConfig defaults are correct`() {
        val config = ChatConfig()
        assertEquals(null, config.thinkingMode)
        assertEquals(null, config.responseFormat)
        assertEquals(null, config.stop)
        assertEquals(null, config.toolChoice)
        assertEquals(true, config.includeUsage)
        assertEquals(15, config.maxToolIterations)
    }

    @Test
    fun `ChatConfig typed properties can be set`() {
        val config = ChatConfig()
        config.thinkingMode = ThinkingMode.Disabled
        config.responseFormat = ResponseFormat.JSON_OBJECT
        config.stop = StopToken.Single("END")
        config.toolChoice = ToolChoice.Auto

        assertTrue(config.thinkingMode is ThinkingMode.Disabled)
        assertEquals(ResponseFormat.JSON_OBJECT, config.responseFormat)
        assertTrue(config.stop is StopToken.Single)
        assertEquals(ToolChoice.Auto, config.toolChoice)
    }
}
