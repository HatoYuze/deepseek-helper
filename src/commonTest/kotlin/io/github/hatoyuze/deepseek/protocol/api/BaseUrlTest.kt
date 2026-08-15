package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientFactory
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * baseUrl 参数的路由与校验测试。
 *
 * 通过 MockEngine 记录每个请求的完整 URL 与请求体，验证客户端把 chat / models /
 * FIM 请求发送到用户指定的服务地址（含路径前缀、尾部斜杠归一化与默认值守护）。
 *
 * 注：Ktor SSE 插件在 MockEngine 上不投递解析后的事件（本仓库无先例依赖该组合），
 * 因此 chat/FIM 用例断言请求 URL 与请求体内容，SSE 事件解析路径不在本特性改动范围内。
 */
@OptIn(ExperimentalDeepseekApi::class)
class BaseUrlTest {

    /** 一条被 MockEngine 记录的请求 */
    private class RecordedRequest(val url: String, val body: String)

    /** 创建一个记录请求 URL/请求体并按路径返回预编程响应的连接池 */
    private fun recordingPool(
        requests: MutableList<RecordedRequest>,
        bodyFor: (path: String) -> String,
    ): DeepseekHttpClientPool = DeepseekHttpClientPool(
        factory = DeepseekHttpClientFactory { _ ->
            HttpClient(MockEngine { request ->
                requests.add(
                    RecordedRequest(
                        url = request.url.toString(),
                        body = request.body.toByteArray().decodeToString(),
                    )
                )
                val path = request.url.encodedPath
                val isSse = path == "/chat/completions" || path == "/beta/completions"
                respond(
                    content = bodyFor(path),
                    status = HttpStatusCode.OK,
                    headers = headersOf(
                        HttpHeaders.ContentType,
                        if (isSse) "text/event-stream" else "application/json",
                    ),
                )
            }) {
                install(SSE) {
                    maxReconnectionAttempts = 0
                }
            }
        },
    )

    /** 组装最小合法 SSE 响应体，每个事件以空行结尾 */
    private fun sse(vararg events: String): String =
        events.joinToString("") { "data: $it\n\n" }

    private fun chatDeltaEvent(content: String): String =
        """{"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"$content"}}]}"""

    private fun chatSseBody(): String = sse(chatDeltaEvent("hi"), "[DONE]")

    @Test
    fun `statelessDeepseek DSL routes chat request to custom baseUrl`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { path ->
            if (path == "/chat/completions") chatSseBody() else error("unexpected path: $path")
        }

        val ds = statelessDeepseek("sk-test") {
            baseUrl = "https://example.com"
            sharingPool = pool
        }

        ds.chatStream("hello").collect { }

        assertEquals(1, requests.size, "chat 请求应恰好发起一次")
        assertEquals("https://example.com/chat/completions", requests[0].url)
        assertTrue(requests[0].body.contains("\"model\""), "请求体应包含 model: ${requests[0].body}")
        assertTrue(requests[0].body.contains("\"messages\""), "请求体应包含 messages: ${requests[0].body}")
        assertEquals("https://example.com", ds.baseUrl)
    }

    @Test
    fun `Deepseek constructor baseUrl routes models request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { path ->
            if (path == "/models") """{"data":[]}""" else error("unexpected path: $path")
        }

        val ds = Deepseek("sk-test", baseUrl = "https://my-provider.example.com", sharingPool = pool)

        val models = ds.availableModels()

        assertEquals(0, models.size)
        assertEquals(listOf("https://my-provider.example.com/models"), requests.map { it.url })
        assertEquals("https://my-provider.example.com", ds.baseUrl)
    }

    @Test
    fun `deepseek DSL baseUrl property routes models request`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { path ->
            if (path == "/models") """{"data":[]}""" else error("unexpected path: $path")
        }

        val ds = deepseek("sk-test") {
            baseUrl = "https://dsl-provider.example.com"
            sharingPool = pool
        }

        ds.availableModels()

        assertEquals(listOf("https://dsl-provider.example.com/models"), requests.map { it.url })
        assertEquals("https://dsl-provider.example.com", ds.baseUrl)
    }

    @Test
    fun `baseUrl with path prefix is preserved in request URL`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { path ->
            if (path == "/v1/chat/completions") chatSseBody() else error("unexpected path: $path")
        }

        val ds = statelessDeepseek("sk-test") {
            baseUrl = "https://gateway.example.com/v1"
            sharingPool = pool
        }

        ds.chatStream("hello").collect { }

        assertEquals(listOf("https://gateway.example.com/v1/chat/completions"), requests.map { it.url })
        assertEquals("https://gateway.example.com/v1", ds.baseUrl)
    }

    @Test
    fun `trailing slash in baseUrl is normalized`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { path ->
            if (path == "/chat/completions") chatSseBody() else error("unexpected path: $path")
        }

        val ds = statelessDeepseek("sk-test") {
            baseUrl = "HTTPS://EXAMPLE.com/"
            sharingPool = pool
        }

        ds.chatStream("hello").collect { }

        assertEquals(listOf("https://example.com/chat/completions"), requests.map { it.url })
        assertEquals("https://example.com", ds.baseUrl, "scheme 大小写与尾部斜杠都应归一化")
    }

    @Test
    fun `default baseUrl targets official DeepSeek API`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { path ->
            if (path == "/models") """{"data":[]}""" else error("unexpected path: $path")
        }

        val ds = StatelessDeepseek("sk-test", sharingPool = pool)

        ds.availableModels()

        assertEquals(listOf("https://api.deepseek.com/models"), requests.map { it.url })
        assertEquals("https://api.deepseek.com", ds.baseUrl)
    }

    @Test
    fun `invalid baseUrl rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            StatelessDeepseek("sk-test", baseUrl = "   ")
        }
        assertFailsWith<IllegalArgumentException> {
            StatelessDeepseek("sk-test", baseUrl = "example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            statelessDeepseek("sk-test") { baseUrl = "ftp://example.com" }
        }
        // userinfo 可伪造目标主机（https://official@evil.com 实际请求 evil.com）
        assertFailsWith<IllegalArgumentException> {
            StatelessDeepseek("sk-test", baseUrl = "https://api.deepseek.com@evil.com")
        }
        // query / fragment 会让固定端点路径拼接错位
        assertFailsWith<IllegalArgumentException> {
            StatelessDeepseek("sk-test", baseUrl = "https://example.com?token=abc")
        }
        assertFailsWith<IllegalArgumentException> {
            StatelessDeepseek("sk-test", baseUrl = "https://example.com#frag")
        }
        // 仅 scheme 无主机
        assertFailsWith<IllegalArgumentException> {
            StatelessDeepseek("sk-test", baseUrl = "https://")
        }
    }

    @Test
    fun `error message redacts the Authorization header`() = runTest {
        val pool = DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory { _ ->
                HttpClient(MockEngine {
                    respond(
                        content = "oops",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                })
            },
        )

        val ds = StatelessDeepseek("sk-super-secret", sharingPool = pool)

        val e = assertFailsWith<IllegalArgumentException> { ds.availableModels() }
        assertTrue(e.message!!.contains("400"), "异常消息应包含状态码: ${e.message}")
        assertTrue(
            !e.message!!.contains("sk-super-secret"),
            "异常消息不得包含 API Key（Authorization 头应被脱敏）: ${e.message}",
        )
    }

    @Test
    fun `fimStream routes to custom baseUrl`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { path ->
            if (path == "/beta/completions") {
                sse(
                    """{"id":"f1","object":"text_completion","created":1,"model":"m","choices":[{"index":0,"text":"x"}]}""",
                    "[DONE]",
                )
            } else error("unexpected path: $path")
        }

        val ds = statelessDeepseek("sk-test") {
            baseUrl = "https://fim-provider.example.com"
            sharingPool = pool
        }

        ds.fimStream("def ").collect { }

        assertEquals(1, requests.size, "FIM 请求应恰好发起一次")
        assertEquals("https://fim-provider.example.com/beta/completions", requests[0].url)
        assertTrue(requests[0].body.contains("\"prompt\""), "请求体应包含 prompt: ${requests[0].body}")
    }
}
