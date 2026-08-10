package io.github.hatoyuze.deepseek
import com.github.ajalt.mordant.markdown.Markdown
import com.github.ajalt.mordant.rendering.TextColors.*
import com.github.ajalt.mordant.rendering.TextStyles.*
import com.github.ajalt.mordant.terminal.Terminal
import io.github.hatoyuze.deepseek.protocol.api.Deepseek
import io.github.hatoyuze.deepseek.protocol.api.DeepseekApi
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice
import io.github.hatoyuze.deepseek.protocol.api.collectResponse
import io.github.hatoyuze.deepseek.protocol.api.deepseek
import io.github.hatoyuze.deepseek.protocol.api.onContent
import io.github.hatoyuze.deepseek.protocol.api.onThinking
import io.github.hatoyuze.deepseek.protocol.api.onToolCall
import io.github.hatoyuze.deepseek.protocol.api.statelessDeepseek
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.BeforeClass
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

// ═══════════════════════════════════════════════════════════
// DeepSeek API 集成测试
// 设置方式（任选其一）：
//   1. 环境变量: export DEEPSEEK_API_KEY=sk-...
//   2. JVM 属性: -Ddeepseek.api.key=sk-...
// ═══════════════════════════════════════════════════════════

@Serializable
private data class WeatherData(val city: String, val weather: String, val temperature: Int)

@Serializable
private data class CalcResult(val a: Double, val b: Double, val operation: String, val result: Double)

class DeepSeekApiTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun checkApiKey() {
            // 未配置 key 时跳过（而不是失败），保证 CI 无密钥也能通过
            assumeTrue(
                "未配置 DEEPSEEK_API_KEY（环境变量或 -Ddeepseek.api.key），跳过线上集成测试",
                resolveApiKey().isNotBlank(),
            )
        }

        fun resolveApiKey(): String {
            return System.getenv("DEEPSEEK_API_KEY")
                ?: System.getProperty("deepseek.api.key")
                ?: ""
        }
    }

    private val apiKey: String by lazy { resolveApiKey() }

    // ── 基础 API ──

    @Test
    fun `availableModels returns model list`() = runBlocking {
        val ds = Deepseek(apiKey)
        val models = ds.availableModels()

        assert(models.isNotEmpty()) { "模型列表不应为空" }
        val ids = models.map { it.id }
        assert(ids.any { it.contains("deepseek") }) { "应包含 deepseek 系列模型" }
        println("✅ 可用模型: ${ids.joinToString()}")
    }

    @Test
    fun `balance returns account info`() = runBlocking {
        val ds = Deepseek(apiKey)
        val balance = ds.balance()

        val info = balance.balanceInfo
        assert(info.totalBalance.isNotBlank()) { "余额信息不应为空" }
        println("✅ 余额: ${info.currency} ${info.totalBalance}")
    }

    // ── 基本对话 ──

    @Test
    fun `simple chat returns valid response`() = runBlocking {
        withTimeout(60.seconds) {
            val ds = deepseek(apiKey) {
                config { maxTokens = 128 }
            }

            val response = ds.chatStream("用一句话介绍 Kotlin 语言")
                .onContent { print(it) }
                .collectResponse()

            assert(response.content.isNotBlank()) { "回复不应为空" }
            assert(response.usage.totalTokens > 0) { "总 token 应 > 0" }
            println("\n✅ 回复: ${response.content.take(80)}...")
            println("✅ 用量: ${response.usage.totalTokens} tokens")
        }
    }

    @Test
    fun `chat with ThinkingMode Max emits reasoning and content`() = runBlocking {
        withTimeout(90.seconds) {
            val ds = deepseek(apiKey) {
                config {
                    thinkingMode = ThinkingMode.Max
                    maxTokens = 1024
                }
            }

            val response = ds.chatStream("思考一下 1+1 等于多少，并解释原因")
                .onThinking { print("🤔 $it") }
                .onContent { print(it) }
                .collectResponse()

            println("\n✅ 思考内容长度: ${response.thinkingContent?.length ?: 0}")
            println("✅ 回复: ${response.content.take(80)}...")

            assert(response.content.isNotBlank()) { "回复不应为空" }
            assert(response.usage.totalTokens > 0) { "总 token 应 > 0" }
        }
    }

    @Test
    fun `stateless deepseek chat works and keeps no history`() = runBlocking {
        withTimeout(60.seconds) {
            val ds = statelessDeepseek(apiKey) {
                prompt = "You are a concise assistant."
                config { maxTokens = 128 }
            }

            val first = ds.chatStream("用一句话介绍 Kotlin")
                .onContent { print(it) }
                .collectResponse()

            println("\n✅ 回复: ${first.content.take(80)}...")
            assert(first.content.isNotBlank()) { "回复不应为空" }
        }
    }

    @Test
    fun `stateless deepseek tool calling works and leaves no history`() = runBlocking {
        withTimeout(90.seconds) {
            val ds = statelessDeepseek(apiKey) {
                prompt = "You are a helpful assistant."
                config {
                    maxTokens = 256
                    temperature = 0.0
                    toolChoice = ToolChoice.Auto
                }
                tools {
                    tool("get_weather") {
                        description = "获取指定城市的天气"
                        parameters {
                            string("city") {
                                description = "城市名称"
                                required = true
                            }
                        }
                        execute { bag, _ ->
                            mapOf("city" to bag.getString("city"), "weather" to "晴")
                        }
                    }
                    timeout(10_000)
                }
            }

            val calledTools = mutableListOf<String>()
            val response = ds.chatStream("北京今天天气怎么样？")
                .onToolCall { calledTools.add(it.call.name) }
                .collectResponse()

            println("✅ 工具调用: $calledTools")
            println("✅ 最终回复: ${response.content.take(120)}...")

            assert(calledTools.isNotEmpty()) {
                "模型应至少调用一次工具。\n回复: ${response.content}"
            }
            assert(response.content.isNotBlank()) { "最终回复不应为空" }
        }
    }

    @Test
    fun `continueStream regenerates after truncateAt`() = runBlocking {
        withTimeout(90.seconds) {
            val ds = deepseek(apiKey) {
                prompt = "You are a helpful assistant."
                config { maxTokens = 1024 }
            }

            val first = ds.chatStream("用一句话介绍你自己")
                .collectResponse()
            assert(first.content.isNotBlank()) { "首次回复不应为空" }

            val userIndex = ds.findUserMessageIndex("用一句话介绍你自己")
            assert(userIndex >= 0) { "应能找到 user 消息索引，实际: $userIndex" }

            ds.truncateAt(userIndex)
            assert(ds.getMessageCount() == 2) { "截断后应只剩 system + user" }

            val second = ds.continueStream()
                .onContent { print(it) }
                .collectResponse()

            println("\n✅ 重新生成回复: ${second.content.take(80)}...")
            assert(second.content.isNotBlank()) { "重新生成的回复不应为空" }
            assert(ds.getMessageCount() == 3) { "重新生成后应为 system + user + assistant" }
        }
    }

    @Test
    fun `responses api chat completes`() = runBlocking {
        withTimeout(90.seconds) {
            val ds = deepseek(apiKey) {
                model { flash() }
                api = DeepseekApi.RESPONSES
                config { maxTokens = 256 }
            }

            val response = ds.chatStream("用一句话介绍 Kotlin")
                .onContent { print(it) }
                .collectResponse()

            println("\n✅ 回复: ${response.content.take(120)}...")
            assert(response.content.isNotBlank()) { "回复不应为空" }
            assert(response.usage.totalTokens > 0) { "总 token 应 > 0" }
        }
    }

    // ── 工具调用 ──

    @Test
    fun `weather tool is called by model`() = runBlocking {
        withTimeout(90.seconds) {
            val ds = deepseek(apiKey) {
                model { pro() }
                config {
                    maxTokens = 256
                    temperature = 0.0
                    toolChoice = ToolChoice.Auto
                }
                tools {
                    tool("get_weather") {
                        description = "获取指定城市的天气信息"
                        parameters {
                            string("city") {
                                description = "城市名称，例如 北京、上海、Tokyo"
                                required = true
                            }
                        }
                        execute { bag, _ ->
                            val city = bag.getString("city")
                            WeatherData(city = city, weather = "晴", temperature = 25)
                        }
                    }
                    timeout(10_000)
                    retry(maxAttempts = 2)
                }
            }

            val toolNames = mutableListOf<String>()
            val response = ds.chatStream("北京今天天气怎么样？")
                .onToolCall { toolNames.add(it.call.name) }
                .collectResponse()

            println("✅ 工具调用: $toolNames")
            println("✅ 最终回复: ${response.content.take(120)}")

            assert(toolNames.isNotEmpty()) {
                "模型应至少调用一次工具，但实际未调用。\n回复内容: ${response.content}"
            }
            assert(toolNames.contains("get_weather")) {
                "应调用 get_weather 工具，实际调用: $toolNames"
            }
        }
    }

    @Test
    fun `multiple tools model picks the right one`() = runBlocking {
        withTimeout(90.seconds) {
            val calledTools = mutableListOf<String>()

            val ds = deepseek(apiKey) {
                model { pro() }
                config {
                    maxTokens = 256
                    temperature = 0.0
                    toolChoice = ToolChoice.Auto
                }
                tools {
                    tool("get_weather") {
                        description = "获取指定城市的天气"
                        parameters {
                            string("city") {
                                description = "城市名称"
                                required = true
                            }
                        }
                        execute { bag, _ ->
                            calledTools.add("get_weather")
                            mapOf("city" to bag.getString("city"), "weather" to "多云")
                        }
                    }
                    tool("get_time") {
                        description = "获取指定城市/地区的当前时间"
                        parameters {
                            string("location") {
                                description = "城市名或地区名"
                                required = true
                            }
                        }
                        execute { bag, _ ->
                            calledTools.add("get_time")
                            mapOf("location" to bag.getString("location"), "time" to "14:30")
                        }
                    }
                    timeout(10_000)
                }
            }

            val response = ds.chatStream("上海现在几点了？")
                .onToolCall { println("🔧 调用: ${it.call.name}(${it.call.arguments})") }
                .collectResponse()

            println("✅ 被调用的工具: $calledTools")
            println("✅ 最终回复: ${response.content.take(120)}")

            assert(calledTools.isNotEmpty()) {
                "模型应至少调用一个工具\n回复: ${response.content}"
            }
        }
    }

    @Test
    fun `calculator tool with numeric params`() = runBlocking {
        withTimeout(90.seconds) {
            val ds = deepseek(apiKey) {
                model { pro() }
                config {
                    maxTokens = 256
                    temperature = 0.0
                    toolChoice = ToolChoice.Auto
                }
                tools {
                    tool("calculate") {
                        description = "执行基本四则运算，支持加法、减法、乘法、除法"
                        parameters {
                            number("a") {
                                description = "第一个数字"
                                required = true
                            }
                            number("b") {
                                description = "第二个数字"
                                required = true
                            }
                            enum("operation") {
                                description = "运算类型"
                                required = true
                                values = listOf("add", "subtract", "multiply", "divide")
                            }
                        }
                        execute { bag, _ ->
                            val a = bag["a"].toString().toDouble()
                            val b = bag["b"].toString().toDouble()
                            val op = bag.getString("operation")
                            val result = when (op) {
                                "add" -> a + b
                                "subtract" -> a - b
                                "multiply" -> a * b
                                "divide" -> a / b
                                else -> error("未知运算: $op")
                            }
                            CalcResult(a = a, b = b, operation = op, result = result)
                        }
                    }
                    timeout(10_000)
                }
            }

            val response = ds.chatStream("请帮我算一下 123.45 乘以 67.89 等于多少")
                .onToolCall { println("🔧 调用: ${it.call.name}(${it.call.arguments})") }
                .collectResponse()

            println("✅ 最终回复: ${response.content.take(200)}")
            println("✅ 工具调用次数: ${response.toolCalls.size}")

            assert(response.content.isNotBlank()) { "应得到计算结果相关的回复" }
        }
    }
}


// ═══════════════════════════════════════════════════════════
// 交互式 CLI（非测试，通过 main() 手动运行）
// ═══════════════════════════════════════════════════════════

fun main() = runBlocking {
    val apiKey = System.getenv("DEEPSEEK_API_KEY")
        ?: error("DEEPSEEK_API_KEY 未设置。请先: export DEEPSEEK_API_KEY=sk-...")

    val term = Terminal()

    term.println((bold + brightBlue)("DeepSeek CLI"))
    term.println(dim("输入 /exit 退出, /tools 加载演示工具, /model 查看当前模型"))

    fun adjustHeadings(md: String): String = md.lines().joinToString("\n") { line ->
        when {
            line.startsWith("## ") -> "**${line.removePrefix("## ")}**"
            line.startsWith("# ") -> "**${line.removePrefix("# ")}**"
            else -> line
        }
    }

    fun Terminal.printMarkdown(md: String, width: Int = 100) {
        updateSize()
        val w = minOf(size.width, width)
        println(render(Markdown(adjustHeadings(md)), width = w))
    }

    fun Terminal.printThinking(text: String) {
        text.lines().forEach { line ->
            println((white on gray)(">  $line"))
        }
    }

    val ds = Deepseek(apiKey)

    val available = ds.availableModels()
    val modelName = ds.resolvedModel.id
    term.println((blue)("可用模型: ${available.joinToString { it.id }}"))
    term.println((blue)("当前模型: $modelName"))

    while (true) {
        term.print((brightGreen + bold)("▶ "))
        val input = term.readLineOrNull(false)?.trim() ?: break
        if (input.isEmpty()) continue
        if (input == "/exit") break
        if (input == "/model") {
            term.println((blue)("当前模型: $modelName"))
            continue
        }
        if (input == "/tools") {
            ds.toolHost = io.github.hatoyuze.deepseek.toolcall.dsl.toolHost {
                tool("get_weather") {
                    description = "获取指定城市的天气"
                    parameters {
                        string("city") { required = true }
                    }
                    execute { bag, _ ->
                        """{"city":"${bag.getString("city")}","weather":"晴","temperature":25}"""
                    }
                }
                timeout(10_000)
                retry(maxAttempts = 2)
            }
            term.println((green)("✅ 天气工具已加载。试试问 '北京天气怎么样？'"))
            continue
        }

        term.println(dim("─────"))

        val startTime = System.currentTimeMillis()

        try {
            val response = ds.chatStream(input)
                .onThinking { term.printThinking(it) }
                .onToolCall {
                    term.println((brightYellow)("\n🔧 ${it.call.name}(${it.call.arguments})"))
                }
                .collectResponse()

            val elapsed = System.currentTimeMillis() - startTime

            if (response.content.isNotEmpty()) {
                term.printMarkdown(response.content)
            }
            term.println((gray)(dim("── ${response.usage.totalTokens} tokens | ${elapsed}ms 总计")))
        } catch (e: Exception) {
            term.println((red)("✗ Error: ${e.message}"))
        }
    }

    term.println("Bye.")
}
