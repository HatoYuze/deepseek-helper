package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.InlineToolCallPolicy
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `streamLoop` 层的内联信封恢复用例。
 *
 * 这一层必须在场：信封解析器自己（`DsmlEnvelopeParserTest`）与请求装配层（`ReplayScrubbingTest`）
 * 都换掉了真实循环，只有这里能证明「恢复出来的调用确实走了工具循环、且没有进正文与历史」。
 */
@OptIn(ExperimentalDeepseekApi::class)
class InlineToolCallRecoveryTest {

    @Test
    fun `registered tool in an envelope is recovered and executed without reaching content`() = runTest {
        val executed = mutableListOf<String>()
        var round = 0
        val ds = statefulDeepseek(
            GatedBackend {
                round++
                if (round == 1) {
                    flow {
                        emit(ChatChunk.ContentDelta("先说结论。\n\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}"))
                        emit(ChatChunk.Done(1, 1, 1, "stop"))
                    }
                } else {
                    flow {
                        emit(ChatChunk.ContentDelta("工具说今天晴。"))
                        emit(ChatChunk.Done(1, 1, 1, "stop"))
                    }
                }
            },
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("杭州天气？").toList()

        val content = chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content }
        assertFalse(content.contains("DSML"), "正文里不能出现信封，实际：$content")
        assertEquals("先说结论。\n\n工具说今天晴。", content)
        assertEquals(listOf("get_weather"), executed, "恢复出来的调用必须真的执行")

        val recovered = chunks.filterIsInstance<ChatChunk.ToolCallRequest>()
        assertEquals(1, recovered.size, "恢复出来的调用也要对外可见")
        assertTrue(
            recovered.single().call.id.startsWith("dsml_"),
            "恢复出来的调用要能一眼认出，实际 id=${recovered.single().call.id}",
        )

        val toolTurn = ds.messages.firstOrNull { it.toolCalls != null }
        assertEquals("get_weather", toolTurn?.toolCalls?.single()?.name, "历史必须写下 assistant(tool_calls)")
        assertEquals("""{"city":"Hangzhou"}""", toolTurn?.toolCalls?.single()?.arguments)
        val toolResult = ds.messages.firstOrNull { it.role == Role.Tool }
        assertTrue(
            toolResult?.content?.asText()?.contains("Hangzhou") == true,
            "历史里必须有配对的 tool 结果，实际：${ds.messages.map { it.role }}",
        )
        assertEquals(2, round, "执行完工具后要继续下一轮请求")
    }

    @Test
    fun `envelope naming an unregistered tool is dropped without executing or asking again`() = runTest {
        val executed = mutableListOf<String>()
        var round = 0
        val ds = statefulDeepseek(
            GatedBackend {
                round++
                flow {
                    emit(ChatChunk.ContentDelta("正文。${envelopeOf("not_registered", stringParameter("x", "1"))}"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("问").toList()

        assertEquals(1, round, "未注册的工具不得触发第二轮请求")
        assertTrue(chunks.filterIsInstance<ChatChunk.ToolCallRequest>().isEmpty())
        assertEquals("正文。", chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content })
        assertEquals(emptyList(), executed)
        assertTrue(ds.messages.none { it.toolCalls != null }, "未执行的调用不能写进历史")
    }

    @Test
    fun `envelope without a tool host is dropped and never surfaces as content`() = runTest {
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("前言${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}后记"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )

        val chunks = ds.chatStream("问").toList()

        assertEquals("前言后记", chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content })
        assertTrue(chunks.filterIsInstance<ChatChunk.ToolCallRequest>().isEmpty())
        assertTrue(ds.messages.none { it.toolCalls != null })
    }

    @Test
    fun `envelope inside the reasoning channel is stripped and never executed`() = runTest {
        val executed = mutableListOf<String>()
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(
                        ChatChunk.ContentDelta(
                            content = "",
                            reasoningContent = "我先看看。${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}结论如下。",
                        ),
                    )
                    emit(ChatChunk.ContentDelta("最终回答"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("问").toList()

        val reasoning = chunks.filterIsInstance<ChatChunk.ContentDelta>()
            .mapNotNull { it.reasoningContent }
            .joinToString("")
        assertFalse(reasoning.contains("DSML"), "思考内容里也不能出现信封碎片，实际：$reasoning")
        assertTrue(reasoning.contains("我先看看。") && reasoning.contains("结论如下。"), "信封外的思考要保留")
        assertEquals(emptyList(), executed, "思考通道里的调用绝不执行")
        assertTrue(ds.messages.none { it.toolCalls != null })
        val assistant = ds.messages.last { it.role == Role.Assistant }
        assertFalse(
            assistant.reasoningContent?.contains("DSML") == true,
            "写进历史的思考同样不能带信封，实际：${assistant.reasoningContent}",
        )
    }

    @Test
    fun `marker split into single characters never leaks a partial marker`() = runTest {
        val executed = mutableListOf<String>()
        var round = 0
        val body = "前言${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}后记"
        val ds = statefulDeepseek(
            GatedBackend {
                round++
                flow {
                    if (round == 1) {
                        body.forEach { emit(ChatChunk.ContentDelta(it.toString())) }
                    } else {
                        emit(ChatChunk.ContentDelta("完成。"))
                    }
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("问").toList()

        val content = chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content }
        assertEquals("前言后记完成。", content)
        assertFalse(content.contains("DSML"))
        assertEquals(listOf("get_weather"), executed)
        assertEquals(1, chunks.filterIsInstance<ChatChunk.ToolCallRequest>().size)
    }

    @Test
    fun `unterminated envelope at stream end is dropped and not executed`() = runTest {
        val executed = mutableListOf<String>()
        val unterminated = "$MARKER calls>\n$MARKER invoke name=\"get_weather\">\n" +
            stringParameter("city", "Hangzhou")
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("前言$unterminated"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("问").toList()

        val content = chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content }
        assertEquals("前言", content, "未闭合信封不能再吐出任何东西")
        assertEquals(emptyList(), executed)
        assertTrue(chunks.filterIsInstance<ChatChunk.ToolCallRequest>().isEmpty())
    }

    @Test
    fun `stray closing fragment in prose is dropped while prose survives`() = runTest {
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("正文</$MARKER parameter>尾巴"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )

        val content = ds.chatStream("问").toList()
            .filterIsInstance<ChatChunk.ContentDelta>()
            .joinToString("") { it.content }

        assertEquals("正文尾巴", content)
    }

    @Test
    fun `ordinary content mentioning DSML is passed through untouched`() = runTest {
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("单个全角竖线 $BAR 与单词 DSML"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )

        val content = ds.chatStream("问").toList()
            .filterIsInstance<ChatChunk.ContentDelta>()
            .joinToString("") { it.content }

        assertEquals("单个全角竖线 $BAR 与单词 DSML", content)
    }

    @Test
    fun `recovered call is emitted after the prose that preceded it`() = runTest {
        var round = 0
        val ds = statefulDeepseek(
            GatedBackend {
                round++
                if (round == 1) {
                    flow {
                        emit(
                            ChatChunk.ContentDelta(
                                "先说结论。\n\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}",
                            ),
                        )
                        emit(ChatChunk.Done(1, 1, 1, "stop"))
                    }
                } else {
                    flow {
                        emit(ChatChunk.ContentDelta("完成。"))
                        emit(ChatChunk.Done(1, 1, 1, "stop"))
                    }
                }
            },
        )
        ds.toolHost = weatherHost(mutableListOf())

        val chunks = ds.chatStream("问").toList()

        val callIndex = chunks.indexOfFirst { it is ChatChunk.ToolCallRequest }
        val proseIndex = chunks.indexOfFirst { it is ChatChunk.ContentDelta && it.content.contains("先说结论") }
        assertTrue(proseIndex >= 0 && callIndex >= 0, "正文与工具调用都应出现：prose=$proseIndex call=$callIndex")
        assertTrue(
            proseIndex < callIndex,
            "信封在正文之后，恢复出来的调用也必须排在它后面（实际 prose=$proseIndex call=$callIndex）",
        )
    }

    @Test
    fun `recovered call duplicating a structured call executes once`() = runTest {
        val executed = mutableListOf<String>()
        var round = 0
        val ds = statefulDeepseek(
            GatedBackend {
                round++
                if (round == 1) {
                    flow {
                        // 网关同时下发两条通道：结构化 tool_calls + 正文里的同一个信封
                        // 故意与恢复出来的紧凑 JSON 排版不同：去重必须做结构比较而不是字符串比较
                        emit(ChatChunk.ToolCallRequest(ToolCall("call_1", "get_weather", """{"city": "Hangzhou"}""")))
                        emit(
                            ChatChunk.ContentDelta(
                                "正文。\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}",
                            ),
                        )
                        emit(ChatChunk.Done(1, 1, 1, "tool_calls"))
                    }
                } else {
                    flow {
                        emit(ChatChunk.ContentDelta("完成。"))
                        emit(ChatChunk.Done(1, 1, 1, "stop"))
                    }
                }
            },
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("问").toList()

        assertEquals(listOf("get_weather"), executed, "同一个调用绝不能执行两次")
        assertEquals(1, chunks.filterIsInstance<ChatChunk.ToolCallRequest>().size, "对外也只能看到一次调用")
        assertEquals(1, ds.messages.count { it.toolCalls != null }, "历史里只应有一条 assistant(tool_calls)")
    }

    @Test
    fun `cancel during a suspending tool leaves no unpaired tool turn`() = runTest {
        val started = CompletableDeferred<Unit>()
        val host = ToolCallHost(ToolRegistry())
        val schema = parametersOf {
            string("value") { required = true }
        }
        host.register("slow_tool", "hangs until cancelled", schema = schema) { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ToolCallRequest(ToolCall("call_1", "slow_tool", """{"value":"v"}""")))
                    emit(ChatChunk.Done(1, 1, 1, "tool_calls"))
                }
            },
        )
        ds.toolHost = host

        val job = launch { ds.chatStream("问").collect { } }
        started.await()
        ds.cancelStream()
        job.join()

        assertTrue(
            ds.messages.none { it.role == Role.Assistant && it.toolCalls != null },
            "取消后不得留下没有配对 tool 结果的 assistant(tool_calls)：${ds.messages.map { it.role }}",
        )
    }

    @Test
    fun `toolChoice None disables execution of recovered calls`() = runTest {
        val executed = mutableListOf<String>()
        // 调用方显式声明「不调用任何工具」：恢复通道是客户端解释出来的调用，服务端无从否决，
        // 因此必须由库自己守住这条策略（注意配置要挂在 core 用到的那个实例上）
        val config = ChatConfig().apply { toolChoice = ToolChoice.None }
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("正文。\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
            config = config,
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("问").toList()

        assertEquals(emptyList(), executed, "toolChoice=None 时一个工具都不许执行")
        assertTrue(chunks.filterIsInstance<ChatChunk.ToolCallRequest>().isEmpty())
        assertEquals("正文。\n", chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content })
    }

    @Test
    fun `stray close fragment split across deltas never leaks a bare delimiter`() = runTest {
        val body = "正文</$MARKER parameter>尾巴"
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    body.chunked(2).forEach { emit(ChatChunk.ContentDelta(it)) }
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
        )

        val content = ds.chatStream("问").toList()
            .filterIsInstance<ChatChunk.ContentDelta>()
            .joinToString("") { it.content }

        assertEquals("正文尾巴", content, "delta 边界落在闭合标签中间时也不能漏出半截标记")
    }

    @Test
    fun `STRIP policy removes the envelope without executing it`() = runTest {
        val executed = mutableListOf<String>()
        val config = ChatConfig().apply { inlineToolCallPolicy = InlineToolCallPolicy.STRIP }
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("正文。\n${envelopeOf("get_weather", stringParameter("city", "Hangzhou"))}"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
            config = config,
        )
        ds.toolHost = weatherHost(executed)

        val chunks = ds.chatStream("问").toList()

        assertEquals(emptyList(), executed, "STRIP 下绝不执行")
        assertEquals("正文。\n", chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content })
    }

    @Test
    fun `PASSTHROUGH policy neither strips nor executes`() = runTest {
        val executed = mutableListOf<String>()
        val leak = envelopeOf("get_weather", stringParameter("city", "Hangzhou"))
        val config = ChatConfig().apply { inlineToolCallPolicy = InlineToolCallPolicy.PASSTHROUGH }
        val ds = statefulDeepseek(
            GatedBackend {
                flow {
                    emit(ChatChunk.ContentDelta("正文。\n$leak"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            },
            config = config,
        )
        ds.toolHost = weatherHost(executed)

        val content = ds.chatStream("问").toList()
            .filterIsInstance<ChatChunk.ContentDelta>()
            .joinToString("") { it.content }

        assertEquals(emptyList(), executed, "PASSTHROUGH 下库不执行任何东西")
        assertEquals("正文。\n$leak", content, "PASSTHROUGH 是逃生舱：逐字透传，库不做任何干预")
    }

    private fun weatherHost(executed: MutableList<String>): ToolCallHost {
        val host = ToolCallHost(ToolRegistry())
        val schema = parametersOf {
            string("city") { required = true }
        }
        host.register("get_weather", "Get weather by city", schema = schema) { bag, _ ->
            val city = bag.getString("city")
            executed += "get_weather"
            """{"city":"$city","weather":"sunny"}"""
        }
        return host
    }
}

/** 实测最常见的线上形态：双全角竖线。 */
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
