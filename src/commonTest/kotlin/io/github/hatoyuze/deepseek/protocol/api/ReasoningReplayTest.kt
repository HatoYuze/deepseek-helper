package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientFactory
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 思考内容（`reasoning_content`）必须**原样回传**。
 *
 * 规则是**请求级**的（DeepSeek 官方 thinking-mode 文档）：请求带 `tools` 时，历史里所有轮次的
 * `reasoning_content` 都必须完整回传，包括没有发生工具调用的轮次，缺任意一轮 API 直接返回 400
 * （"The `reasoning_content` in the thinking mode must be passed back to the API"）；请求不带
 * `tools` 时服务端忽略该字段。曾经的实现无条件执行 `withoutReasoningContent()` 把它剥掉，
 * 于是带工具的多轮请求全部非法——「继续生成」按下去必然失败。
 *
 * 本文件的两层断言分工，缺一不可：
 * - **history 级**（用 [GatedBackend]）：库自己写进历史的消息是否带上了正确的思考内容。
 *   断点在后端边界，与本模块其它用例同一口径。
 * - **协议级**（用 [DeepseekHttpClientPool] + [recordingHook]）：装配完成的**请求体**里到底有没有
 *   这个字段。剥离曾经发生在 [DeepseekStandardApiImpl][io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekStandardApiImpl]
 *   的装配代码里，而 [GatedBackend] 会把那个实现整个替换掉——只断言 history 级，等于把 bug 所在的
 *   那一层留成盲区，删掉修复也不会有用例失败。
 */
@OptIn(ExperimentalDeepseekApi::class)
class ReasoningReplayTest {

    private fun assistantsOf(messages: List<Message>): List<Message> =
        messages.filter { it.role == Role.Assistant }

    /**
     * 历史里带思考内容的 assistant 消息，必须原样交到后端边界（协议层见本文件下半部分：
     * 装配后的请求体）。
     */
    @Test
    fun `reasoning in history reaches the backend boundary untouched`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = statefulDeepseek(GatedBackend { messages ->
            seen += messages
            flowOf(ChatChunk.Done(1, 1, 1, "stop"))
        })
        ds.replaceHistory(
            listOf(
                Message(Role.User, MessageContent.of("问题")),
                Message(Role.Assistant, MessageContent.of("答案"), reasoningContent = "先想一下"),
                Message(Role.User, MessageContent.of("再问一句")),
            ),
        )

        ds.continueStream().toList()

        assertEquals(
            "先想一下",
            assistantsOf(seen.single()).single().reasoningContent,
            "装配阶段不得再剥离 reasoning_content：剥离后带 tools 的请求会被 API 以 400 拒绝",
        )
    }

    /** 缺失就是缺失：没有思考内容时不写该字段（空串同样是谎报）。 */
    @Test
    fun `messages without reasoning stay without it`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = statefulDeepseek(GatedBackend { messages ->
            seen += messages
            flowOf(ChatChunk.Done(1, 1, 1, "stop"))
        })
        ds.replaceHistory(listOf(Message(Role.User, MessageContent.of("问题"))))

        ds.continueStream().toList()

        assertTrue(seen.single().all { it.reasoningContent == null }, "无思考内容时不应凭空生成该字段")
    }

    /** 流里收到的思考内容要跟着 assistant 消息进历史。 */
    @Test
    fun `streamed reasoning is recorded in history`() = runTest {
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("", reasoningContent = "在想"))
                    emit(ChatChunk.ContentDelta("答案"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )

        ds.chatStream("问题").toList()

        val assistant = ds.messages.single { it.role == Role.Assistant }
        assertEquals("答案", assistant.content?.asText())
        assertEquals(
            "在想",
            assistant.reasoningContent,
            "思考内容必须进库内历史，否则下一条带着它的历史消息就是非法请求",
        )
    }

    /** 模型没思考时历史里不留痕。 */
    @Test
    fun `reasoning stays null when the model did not think`() = runTest {
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("答案"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )

        ds.chatStream("问题").toList()

        assertNull(ds.messages.single { it.role == Role.Assistant }.reasoningContent)
    }

    /**
     * 工具轮：产生 `tool_calls` 的那条 assistant 消息要带上**产生它的那一轮思考**，
     * 最终回复带最后一轮的思考（多轮思考不能被拼成一段）。
     */
    @Test
    fun `each tool call turn records its own reasoning`() = runTest {
        // 后端每被问一次就换一轮答案：先请求工具，拿到结果后再给出最终回复。
        var round = 0
        val ds = statefulDeepseek(GatedBackend {
            round++
            if (round == 1) {
                flow {
                    emit(ChatChunk.ContentDelta("让我查一下", reasoningContent = "先查天气"))
                    emit(ChatChunk.ToolCallRequest(ToolCall("call_1", "get_weather", "{}")))
                    emit(ChatChunk.Done(1, 1, 1, "tool_calls"))
                }
            } else {
                flow {
                    emit(ChatChunk.ContentDelta("今天晴", reasoningContent = "再回答"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            }
        })
        ds.toolHost = weatherHost()

        val chunks = ds.chatStream("今天天气").toList()

        assertTrue(
            chunks.any { it is ChatChunk.ToolResultData },
            "前提：这一轮确实执行了工具，实际事件：${chunks.map { it::class.simpleName }}",
        )

        val assistants = assistantsOf(ds.messages)
        assertEquals(2, assistants.size, "历史里应有一轮 tool_calls assistant + 一条最终回复，实际：$assistants")
        assertNotNull(assistants[0].toolCalls, "tool_calls 不能因为回填 reasoning 而丢失")
        assertEquals(
            "先查天气",
            assistants[0].reasoningContent,
            "tool_calls 那条 assistant 消息必须带上产生这次调用的思考内容",
        )
        assertEquals(
            "再回答",
            assistants[1].reasoningContent,
            "最终回复带的是最后一轮的思考，而不是把多轮思考拼在一起",
        )
    }

    /**
     * 收尾轮**没有**思考时，收尾消息就是没有思考：思考按轮归属，绝不跨轮挪用。
     *
     * 这条守住的是**归属**，不是那道写入闸门：`if (toolResults.isNotEmpty())`（决定「这一轮的思考算
     * 不算已经写进历史」）由 `reasoning survives a tool call that could not be executed` 覆盖——
     * 把 clear 改成无条件执行，只有那一条用例会失败。
     */
    @Test
    fun `closing round without reasoning does not inherit the previous round`() = runTest {
        var round = 0
        val ds = statefulDeepseek(GatedBackend {
            round++
            if (round == 1) {
                flow {
                    emit(ChatChunk.ContentDelta("让我查一下", reasoningContent = "先查天气"))
                    emit(ChatChunk.ToolCallRequest(ToolCall("call_1", "get_weather", "{}")))
                    emit(ChatChunk.Done(1, 1, 1, "tool_calls"))
                }
            } else {
                flow {
                    emit(ChatChunk.ContentDelta("今天晴"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            }
        })
        ds.toolHost = weatherHost()

        ds.chatStream("今天天气").toList()

        val assistants = assistantsOf(ds.messages)
        assertEquals(2, assistants.size)
        assertEquals("先查天气", assistants[0].reasoningContent, "工具轮的思考照旧要留住")
        assertNull(assistants[1].reasoningContent, "这一轮没思考就是没思考，不能把上一轮的思考挪过来")
    }

    /**
     * 工具**跑不成**（这里：没有 toolHost）时 `handleToolCalls` 直接返回空列表、历史里什么都没写。
     * 那种情况下这一轮的思考是这个回合唯一的推理记录，必须活到收尾消息上——被 clear 掉就等于
     * 凭空造出一条"思考模式下缺 reasoning_content"的消息。
     *
     * 后端只会被问一次（空结果立刻 `break`），因此这里只备一轮回答。
     */
    @Test
    fun `reasoning survives a tool call that could not be executed`() = runTest {
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("让我查一下", reasoningContent = "先查天气"))
                    emit(ChatChunk.ToolCallRequest(ToolCall("call_1", "get_weather", "{}")))
                    emit(ChatChunk.Done(1, 1, 1, "tool_calls"))
                }
            },
        )
        // 故意不装 toolHost：调用执行不了，历史里不会留下 assistant(tool_calls)。

        ds.chatStream("今天天气").toList()

        val assistants = assistantsOf(ds.messages)
        assertEquals(1, assistants.size, "工具没执行，历史里只该有收尾那条，实际：$assistants")
        assertEquals(
            "先查天气",
            assistants.single().reasoningContent,
            "这一轮唯一的推理记录必须跟到收尾消息上，否则下一条请求就是非法请求",
        )
    }

    // ── 协议级：装配完成的请求体（MockEngine 不出网） ──

    /**
     * 历史里的思考内容必须出现在**真正发出去的请求体**里。
     *
     * 这一层不可省：剥离曾经发生在 `DeepseekStandardApiImpl` 的装配代码里，而 history 级用例用的
     * [GatedBackend] 把那个实现整个替换掉了——只断言 history，删掉修复也不会有用例失败。
     */
    @Test
    fun `assembled request body carries the replayed reasoning`() = runTest {
        val bodies = mutableListOf<String>()
        val ds = Deepseek("sk-test", sharingPool = capturingPool(bodies))
        // 带 tools 的请求才是官方强制回传的场景
        ds.toolHost = weatherHost()
        ds.replaceHistory(
            listOf(
                Message(Role.User, MessageContent.of("问题")),
                Message(Role.Assistant, MessageContent.of("答案"), reasoningContent = "先想一下"),
                Message(Role.User, MessageContent.of("再问一句")),
            ),
        )

        ignoringSseDeliveryFailure { ds.continueStream().collect { } }

        val body = bodies.single()
        assertTrue(
            actual = body.contains("\"tools\""),
            message = "\"tools\" in body，前提：请求确实带了 tools，否则这条断言不成立",
        )
        assertTrue(
            actual = body.contains("\"reasoning_content\":\"先想一下\""),
            message = "装配后的请求体必须原样保留 reasoning_content，实际：${body.take(400)}",
        )
    }

    /** 没有思考内容时，装配层不得凭空写出该字段（空串同样是谎报）。 */
    @Test
    fun `assembled request body writes no reasoning field when there is none`() = runTest {
        val bodies = mutableListOf<String>()
        val ds = Deepseek("sk-test", sharingPool = capturingPool(bodies))
        ds.toolHost = weatherHost()
        ds.replaceHistory(listOf(Message(Role.User, MessageContent.of("问题"))))

        ignoringSseDeliveryFailure { ds.continueStream().collect { } }

        val body = bodies.single()
        assertFalse(
            actual = body.contains("reasoning_content"),
            message = "无思考内容时字段应整条不出现（`explicitNulls = false`），实际：${body.take(400)}",
        )
    }

    /**
     * 记录请求体的 MockEngine 池。
     *
     * 断言直接读 MockEngine 收到的 `request.body`（与 `BaseUrlTest` 同一口径），**不**经请求 hook：
     * hook 拿到的是脱敏并截断后的副本，长段无空白文本会被整体替换、超长请求体只保留头部，用它断言
     * 「线上发了什么」会被脱敏规则左右。
     */
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
                    // chat 走 SSE：不装这个插件，请求根本发不出去，断言会退化成"什么都没抓到"
                    install(SSE) { maxReconnectionAttempts = 0 }
                }
            },
        )

    /**
     * 跑一次流式调用，忽略「SSE 事件在 MockEngine 上不投递」造成的异常（仓库已知限制，
     * 见 `HookRedactionTest` 注释）：断言只针对请求体，而请求体在请求发出的那一刻已经定型。
     *
     * 取消必须原样抛出——吞掉 [CancellationException] 会让被取消的测试报告成功。
     */
    private suspend fun ignoringSseDeliveryFailure(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 事件投递失败与请求体无关，忽略
        }
    }

    private fun weatherHost(): ToolCallHost {
        val host = ToolCallHost(ToolRegistry())
        host.register(
            name = "get_weather",
            description = "Get weather",
            schema = parametersOf { string("city") { required = false } },
        ) { _, _ -> """{"weather":"sunny"}""" }
        return host
    }
}
