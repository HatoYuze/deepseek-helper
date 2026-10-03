package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM 重压测试：真实并行调度下验证内联信封过滤的**无共享状态**特性。
 *
 * 过滤器（`DsmlEnvelopeParser` / `InlineToolCallRecovery`）按「一条模型响应一个实例」设计，
 * 外加信封被切成 token 级碎片到达，因此这里刻意用多线程 + 碎片化输入来逼出串台与边界问题。
 */
class InlineToolCallRecoveryStressTest {

    @Test
    fun `300 concurrent streams recover their own fragmented envelope without cross-talk`() = runBlocking {
        val backend = GatedBackend { messages ->
            // 第二轮：历史里已经有配对的 tool 结果 ⇒ 收尾
            if (messages.any { it.role == Role.Tool }) {
                flow {
                    emit(ChatChunk.ContentDelta("done"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            } else {
                val index = messages.last().content?.asText()?.removePrefix("user-")?.toIntOrNull() ?: -1
                val envelope = envelopeOf(index)
                flow {
                    emit(ChatChunk.ContentDelta("前$index"))
                    // 3 字符一刀：定界符与标签属性都会被切开
                    envelope.chunked(3).forEach { emit(ChatChunk.ContentDelta(it)) }
                    emit(ChatChunk.ContentDelta("后$index"))
                    emit(ChatChunk.Done(1, 1, 1, "stop"))
                }
            }
        }
        val ds = StatelessDeepseek(
            "test-key",
            testCore(singleSession = false, backend = backend, prompt = "sys"),
        )
        ds.toolHost = echoHost()

        val chunksPerStream = withTimeout(120_000) {
            (1..300).map { i ->
                async(Dispatchers.Default) { ds.chatStream("user-$i").toList() }
            }.awaitAll()
        }

        chunksPerStream.forEachIndexed { offset, chunks ->
            val index = offset + 1
            val content = chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content }
            assertFalse(content.contains("DSML"), "第 $index 条流出泄漏了信封：$content")
            assertEquals("前${index}后${index}done", content, "第 $index 条流的正文被串台或吞掉了")

            val recovered = chunks.filterIsInstance<ChatChunk.ToolCallRequest>()
            assertEquals(1, recovered.size, "第 $index 条流应恰好恢复一次调用")
            assertEquals("echo", recovered.single().call.name)
            assertTrue(
                recovered.single().call.arguments.contains("\"value\":\"v$index\""),
                "第 $index 条流恢复出的参数串台了：${recovered.single().call.arguments}",
            )
        }
    }

    private fun echoHost(): ToolCallHost {
        val host = ToolCallHost(ToolRegistry())
        val schema = parametersOf {
            string("value") { required = true }
        }
        host.register("echo", "Echo the value back", schema = schema) { bag, _ ->
            """{"echo":"${bag.getString("value")}"}"""
        }
        return host
    }
}

private val BAR: Char = '\uFF5C'

private val MARKER: String = "$BAR${BAR}DSML$BAR$BAR"

private fun envelopeOf(index: Int): String = buildString {
    append(MARKER).append(" calls>\n")
    append(MARKER).append(" invoke name=\"echo\">\n")
    append(MARKER).append(" parameter name=\"value\" string=\"true\">v").append(index)
        .append("</").append(MARKER).append(" parameter>\n")
    append("</").append(MARKER).append(" invoke>\n")
    append("</").append(MARKER).append(" calls>")
}
