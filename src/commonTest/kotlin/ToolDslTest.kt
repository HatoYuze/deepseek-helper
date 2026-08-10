import com.github.hatoyuze.protocol.api.entity.ResponseFormat
import com.github.hatoyuze.protocol.api.entity.StopToken
import com.github.hatoyuze.protocol.api.entity.ThinkingMode
import com.github.hatoyuze.protocol.api.entity.ToolChoice
import com.github.hatoyuze.protocol.api.deepseek
import com.github.hatoyuze.tool.bridge.ParameterBag
import com.github.hatoyuze.tool.dsl.createFunction
import com.github.hatoyuze.tool.dsl.parametersOf
import com.github.hatoyuze.tool.dsl.toolHost
import com.github.hatoyuze.tool.executor.Formats
import com.github.hatoyuze.tool.executor.ToolCall
import com.github.hatoyuze.tool.executor.ToolExecutionContext
import com.github.hatoyuze.tool.pipeline.ToolCallHost
import com.github.hatoyuze.tool.registry.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolDslTest {

    @Test
    fun `weather function DSL matches API doc format`() {
        val def = createFunction("get_weather") {
            description = "Get weather of a location, the user should supply a location first."
            strict = true
            parameters {
                string("location") {
                    description = "The city and state, e.g. San Francisco, CA"
                    required = true
                }
            }
        }

        assertEquals("get_weather", def.name)
        assertTrue(def.strict)

        // 序列化 parameters 为 JSON 并验证结构
        val paramsJson = def.parameters.jsonObject
        assertEquals("object", paramsJson["type"]!!.jsonPrimitive.content)

        val props = paramsJson["properties"]!!.jsonObject
        val location = props["location"]!!.jsonObject
        assertEquals("string", location["type"]!!.jsonPrimitive.content)
        assertEquals("The city and state, e.g. San Francisco, CA", location["description"]!!.jsonPrimitive.content)

        val required = paramsJson["required"]!!.jsonArray
        assertEquals(1, required.size)
        assertEquals("location", required[0].jsonPrimitive.content)

        // toFunctionElement 生成完整 API 格式
        val fn = def.toFunctionElement().jsonObject
        assertEquals("function", fn["type"]!!.jsonPrimitive.content)
        val innerFn = fn["function"]!!.jsonObject
        assertEquals("get_weather", innerFn["name"]!!.jsonPrimitive.content)
        assertTrue(innerFn["strict"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `complex DSL with all types`() {
        val def = createFunction("test_all") {
            description = "Test all parameter types"
            parameters {
                string("name") { description = "A name"; required = true }
                integer("age") { description = "Age"; minimum = 0; maximum = 150 }
                number("score") { description = "Score"; minimum = 0.0; maximum = 100.0 }
                boolean("active", description = "Is active")
                enum("status") {
                    description = "Status"
                    values = listOf("pending", "done")
                }
                array("tags") {
                    description = "Tags"
                    items {
                        string("tag") { description = "A tag" }
                    }
                }
            }
        }

        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        assertEquals(6, props.size)

        // array items 中应包含 type 和 description
        val tags = props["tags"]!!.jsonObject
        assertEquals("array", tags["type"]!!.jsonPrimitive.content)
        val items = tags["items"]!!.jsonObject
        assertEquals("string", items["type"]!!.jsonPrimitive.content)
        assertEquals("A tag", items["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enum and format serialization`() {
        val def = createFunction("contact") {
            description = "Contact info"
            parameters {
                string("email") {
                    description = "Email address"
                    format = Formats.email
                }
                enum("type") {
                    description = "Contact type"
                    values = listOf("work", "personal", "other")
                }
            }
        }

        val props = def.parameters.jsonObject["properties"]!!.jsonObject
        val email = props["email"]!!.jsonObject
        assertEquals("string", email["type"]!!.jsonPrimitive.content)
        assertEquals("email", email["format"]!!.jsonPrimitive.content)

        val type = props["type"]!!.jsonObject
        assertEquals("string", type["type"]!!.jsonPrimitive.content)
        assertEquals(3, type["enum"]!!.jsonArray.size)
    }

    // ── toolHost { } DSL + @Serializable return ──

    @Serializable
    data class WeatherResult(val city: String, val weather: String, val temp: Int)

    @Test
    fun `toolHost DSL builds executable ToolCallHost`() = runBlocking {
        val host = toolHost {
            tool("get_weather") {
                description = "Get weather by city"
                parameters {
                    string("city") { required = true }
                }
                execute { bag: ParameterBag, _ ->
                    WeatherResult(city = bag["city"] as String, weather = "sunny", temp = 25)
                }
            }
        }

        val defs = host.getDefinitions()
        assertEquals(1, defs.size)
        assertEquals("get_weather", defs[0].name)
        assertEquals("Get weather by city", defs[0].description)

        // 实际执行
        val call = ToolCall("call_1", "get_weather", """{"city":"Hangzhou"}""")
        val result = host.execute(call, ToolExecutionContext("u", "s"))
        assertTrue(result.content.contains("Hangzhou"))
        assertTrue(result.content.contains("sunny"))
        assertTrue(result.content.contains("25"))
    }

    @Test
    fun `toolHost returns Map with explicit types`() = runBlocking {


        val host = toolHost {
            tool("echo") {
                description = "Echo the message"
                parameters {
                    string("msg") { required = true }
                }
                execute { bag, _ ->
                    mapOf("msg" to (bag["msg"] as String), "ok" to "true")
                }
            }
        }

        val call = ToolCall("call_2", "echo", """{"msg":"hello"}""")
        val result = host.execute(call, ToolExecutionContext("u", "s"))
        assertTrue(result.content.contains(""""msg":"hello""""))
        assertTrue(result.content.contains(""""ok":"true""""))
    }

    @Test
    fun `ToolCallHost registerTyped with @Serializable`() = runBlocking {
        val host = ToolCallHost(ToolRegistry())
        host.registerTyped("greet", "Greet someone",
            schema = parametersOf {
                string("name") { required = true }
            }
        ) { bag, _ ->
            WeatherResult(city = bag["name"] as String, weather = "greeting", temp = 0)
        }

        val call = ToolCall("call_3", "greet", """{"name":"World"}""")
        val result = host.execute(call, ToolExecutionContext("u", "s"))
        assertTrue(result.content.contains("World"))
    }

    // ── Deepseek DSL ──

    @Test
    fun `deepseek DSL creates instance with config`() {
        val ds = deepseek("sk-test-key") {
            prompt = "You are a test assistant"
            config {
                maxTokens = 100
                temperature = 0.5
                thinkingMode = ThinkingMode.Disabled
                responseFormat = ResponseFormat.JSON_OBJECT
                stop = StopToken.Single("END")
                toolChoice = ToolChoice.Auto
            }
        }

        assertEquals("sk-test-key", ds.apiKey)
        assertEquals(100, ds.config.maxTokens)
        assertEquals(0.5, ds.config.temperature)
        assertTrue(ds.config.thinkingMode is ThinkingMode.Disabled)
        assertEquals(ResponseFormat.JSON_OBJECT, ds.config.responseFormat)
        assertTrue(ds.config.stop is StopToken.Single)
        assertEquals(ToolChoice.Auto, ds.config.toolChoice)
    }

    @Test
    fun `deepseek DSL model selector`() {
        val dsWithModel = deepseek("sk-test-key") {
            model { flash() }
        }
        assertEquals("deepseek-v4-flash", dsWithModel.resolvedModel.id)

        val dsCustom = deepseek("sk-test-key") {
            model { custom("my-model") }
        }
        assertEquals("my-model", dsCustom.resolvedModel.id)
    }

    @Test
    fun `toolHost with plugins`() = runBlocking {
        val host = toolHost {
            tool("echo") {
                description = "Echo input"
                parameters {
                    string("msg") { required = true }
                }
                execute { bag, _ ->
                    mapOf("echo" to (bag["msg"] as String))
                }
            }
            logging()
        }

        val defs = host.getDefinitions()
        assertEquals(1, defs.size)

        val call = ToolCall("call", "echo", """{"msg":"hi"}""")
        val result = host.execute(call, ToolExecutionContext("u", "s"))
        assertTrue(result.content.contains("hi"))
    }
}
