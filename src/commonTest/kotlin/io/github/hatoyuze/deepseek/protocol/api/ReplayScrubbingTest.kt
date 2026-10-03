package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.InlineToolCallPolicy
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientFactory
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 历史回放清洗与请求侧不变量诊断。
 *
 * 装配层的用例（`assembledRequest...`）**必须**在协议层做：`GatedBackend` 会把
 * `DeepseekStandardApiImpl` 整个替换掉，只断言 history 的话，把清洗从装配代码里删掉也照样全绿。
 */
@OptIn(ExperimentalDeepseekApi::class)
class ReplayScrubbingTest {

    @Test
    fun `scrubbing strips envelopes from assistant content only`() {
        val leaked = "答案在下面。\n\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}"
        val history = listOf(
            Message(Role.User, MessageContent.of("用户消息里的 DSML 字样不动")),
            Message(Role.Assistant, MessageContent.of(leaked)),
            Message(
                Role.Assistant,
                content = null,
                toolCalls = listOf(ToolCall("call_1", "code_run", """{"source":"// DSML 只是注释"}""")),
            ),
        )

        val scrubbed = history.scrubbedForReplay()

        assertEquals("用户消息里的 DSML 字样不动", scrubbed[0].content?.asText())
        assertEquals("答案在下面。\n\n", scrubbed[1].content?.asText())
        assertEquals(
            """{"source":"// DSML 只是注释"}""",
            scrubbed[2].toolCalls?.single()?.arguments,
            "工具参数里合法出现该字样时不得误删",
        )
    }

    @Test
    fun `scrubbing returns the same list instance when there is nothing to do`() {
        val history = listOf(
            Message(Role.User, MessageContent.of("普通问题")),
            Message(Role.Assistant, MessageContent.of("普通回答")),
        )

        assertSame(history, history.scrubbedForReplay(), "无信封时必须零分配地原样返回")
    }

    @Test
    fun `tool channel diagnostic fires only when tools are missing but history has tool turns`() {
        val toolTurns = listOf(
            Message(Role.User, MessageContent.of("问题")),
            Message(
                Role.Assistant,
                content = null,
                toolCalls = listOf(ToolCall("call_1", "get_weather", "{}")),
            ),
            Message(Role.Tool, MessageContent.of("结果"), toolCallId = "call_1"),
        )
        val plainHistory = listOf(Message(Role.User, MessageContent.of("问题")))

        assertNotNull(replayToolChannelDiagnostic(toolTurns, tools = null))
        assertNotNull(replayToolChannelDiagnostic(toolTurns, tools = emptyList()))
        assertNull(replayToolChannelDiagnostic(toolTurns, tools = listOf(toolDefinition("get_weather"))))
        assertNull(replayToolChannelDiagnostic(plainHistory, tools = null))
    }

    @Test
    fun `assembled request body carries scrubbed history`() = runTest {
        val bodies = mutableListOf<String>()
        val ds = Deepseek("sk-test", sharingPool = capturingPool(bodies))
        ds.replaceHistory(
            listOf(
                Message(Role.User, MessageContent.of("问题")),
                Message(
                    Role.Assistant,
                    MessageContent.of("答案。\n\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}"),
                ),
                Message(Role.User, MessageContent.of("再问一句")),
            ),
        )

        ignoringSseDeliveryFailure { ds.continueStream().collect { } }

        val body = bodies.single()
        assertFalse(body.contains("DSML"), "装配后的请求体里不能残留信封，实际：${body.take(400)}")
        assertTrue(body.contains("答案。"), "信封外的正文必须保留，实际：${body.take(400)}")
    }

    @Test
    fun `assembled request body keeps markup that lives in tool arguments`() = runTest {
        val bodies = mutableListOf<String>()
        val ds = Deepseek("sk-test", sharingPool = capturingPool(bodies))
        ds.replaceHistory(
            listOf(
                Message(Role.User, MessageContent.of("问题")),
                Message(
                    Role.Assistant,
                    content = null,
                    toolCalls = listOf(
                        ToolCall("call_1", "code_run", """{"source":"printf(\"DSML\");"}"""),
                    ),
                ),
                Message(Role.Tool, MessageContent.of("ok"), toolCallId = "call_1"),
            ),
        )

        ignoringSseDeliveryFailure { ds.continueStream().collect { } }

        val body = bodies.single()
        assertTrue(body.contains("DSML"), "工具参数里的字样属于数据，清洗不得触碰，实际：${body.take(400)}")
    }

    @Test
    fun `PASSTHROUGH leaves historical envelopes untouched in the assembled body`() = runTest {
        val bodies = mutableListOf<String>()
        val config = ChatConfig().apply { inlineToolCallPolicy = InlineToolCallPolicy.PASSTHROUGH }
        val ds = Deepseek("sk-test", config = config, sharingPool = capturingPool(bodies))
        ds.replaceHistory(
            listOf(
                Message(Role.User, MessageContent.of("问题")),
                Message(
                    Role.Assistant,
                    MessageContent.of("答案。\n\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}"),
                ),
            ),
        )

        ignoringSseDeliveryFailure { ds.continueStream().collect { } }

        val body = bodies.single()
        assertTrue(
            body.contains("DSML"),
            "PASSTHROUGH 表示调用方自行后处理，库连回放历史都不许改，实际：${body.take(400)}",
        )
    }

    private fun toolDefinition(name: String): ToolDefinition = ToolDefinition(
        name = name,
        description = "test tool",
        parameters = kotlinx.serialization.json.buildJsonObject { },
    )

    /** 记录请求体的 MockEngine 池；与 `ReasoningReplayTest` 同一口径（直读 request.body）。 */
    private fun capturingPool(bodies: MutableList<String>): DeepseekHttpClientPool =
        DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory {
                HttpClient(
                    MockEngine { request ->
                        if (request.url.encodedPath.endsWith("/chat/completions")) {
                            bodies += request.body.toByteArray().decodeToString()
                        }
                        respond(
                            content = "data: {\"choices\":[]}\n\ndata: [DONE]\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                ) {
                    install(SSE) { maxReconnectionAttempts = 0 }
                }
            },
        )

    /** 忽略「SSE 事件在 MockEngine 上不投递」造成的异常（仓库已知限制，见 `HookRedactionTest`）。 */
    private suspend fun ignoringSseDeliveryFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 事件投递失败与请求体无关：请求体在请求发出的那一刻已经定型
        }
    }
}

private val BAR: Char = '\uFF5C'

private val MARKER: String = "$BAR${BAR}DSML$BAR$BAR"

private fun envelopeOf(tool: String, vararg parameters: String): String = buildString {
    append(MARKER).append(" calls>\n")
    append(MARKER).append(" invoke name=\"").append(tool).append("\">\n")
    parameters.forEach { append(it).append('\n') }
    append("</").append(MARKER).append(" invoke>\n")
    append("</").append(MARKER).append(" calls>")
}

private fun stringParameter(name: String, value: String): String =
    "$MARKER parameter name=\"$name\" string=\"true\">$value</$MARKER parameter>"
