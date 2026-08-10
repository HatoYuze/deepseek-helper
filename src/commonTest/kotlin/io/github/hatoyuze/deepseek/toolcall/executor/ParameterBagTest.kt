package io.github.hatoyuze.deepseek.toolcall.executor
import io.github.hatoyuze.deepseek.toolcall.executor.ParameterBag
import io.github.hatoyuze.deepseek.toolcall.executor.ParameterBagSerializer
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ParameterBagTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize flat params from JSON`() {
        val schema = parametersOf {
            string("location") { description = "City name" }
            integer("count") { description = "Number of items" }
            number("score") { description = "Rating" }
            boolean("active", description = "Is active")
        }

        val serializer = ParameterBagSerializer(schema)
        val rawJson = """{"location":"Hangzhou","count":5,"score":4.5,"active":true}"""
        val bag = json.decodeFromString(serializer, rawJson)

        assertEquals("Hangzhou", bag.getString("location"))
        assertEquals(5, bag.getInt("count"))
        assertEquals(4.5, bag.getDouble("score"))
        assertTrue(bag.getBoolean("active"))
    }

    @Test
    fun `serialize and round-trip`() {
        val schema = parametersOf {
            string("name") { required = true }
            integer("age") { }
        }

        val serializer = ParameterBagSerializer(schema)
        val bag = ParameterBag.empty()
        bag["name"] = "Alice"
        bag["age"] = 30

        val jsonStr = json.encodeToString(serializer, bag)
        val restored = json.decodeFromString(serializer, jsonStr)

        assertEquals("Alice", restored.getString("name"))
        assertEquals(30, restored.getInt("age"))
    }

    @Test
    fun `deserialize nested object params`() {
        val schema = parametersOf {
            string("title") { required = true }
            obj("author") {
                properties {
                    string("name") { required = true }
                    string("email") { }
                }
            }
        }

        val serializer = ParameterBagSerializer(schema)
        val rawJson = """{"title":"Hello","author":{"name":"Alice","email":"a@b.com"}}"""
        val bag = json.decodeFromString(serializer, rawJson)

        assertEquals("Hello", bag.getString("title"))
        val author = bag.getBag("author")
        assertEquals("Alice", author.getString("name"))
        assertEquals("a@b.com", author.getString("email"))
    }

    @Test
    fun `deserialize array of strings`() {
        val schema = parametersOf {
            array("tags") {
                description = "Tags list"
                items { string("tag") { } }
            }
        }

        val serializer = ParameterBagSerializer(schema)
        val rawJson = """{"tags":["kotlin","serialization"]}"""
        val bag = json.decodeFromString(serializer, rawJson)

        val tags = bag.getList("tags")
        assertEquals(2, tags.size)
        assertEquals("kotlin", tags[0])
    }

    @Test
    fun `ToolCallHost schema-driven registration and execution`() = runTest {
        val schema = parametersOf {
            string("name") { required = true }
            string("greeting") { }
        }
        val host = ToolCallHost(ToolRegistry())
        host.register("greet", "Greet someone", schema = schema) { bag, _ ->
            val name = bag.getString("name")
            val greeting = bag.getStringOrNull("greeting") ?: "Hello"
            "$greeting, $name!"
        }

        val call = ToolCall(
            id = "call_001",
            name = "greet",
            arguments = """{"name":"World"}""",
        )
        val ctx = ToolExecutionContext("user1", "session1")
        val result = host.execute(call, ctx)

        assertEquals("call_001", result.toolCallId)
        assertTrue(result.content.contains("Hello, World!"))
    }

    @Test
    fun `ToolCallHost returns error for unknown function`() = runTest {
        val schema = parametersOf { string("x") { } }
        val host = ToolCallHost(ToolRegistry())
        host.register("test", "desc", schema = schema) { bag, _ -> bag.getString("x") }

        val call = ToolCall("call_002", "unknown", "{}")
        val ctx = ToolExecutionContext("u", "s")
        val result = host.execute(call, ctx)

        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown function"))
    }
}
