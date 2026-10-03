package io.github.hatoyuze.deepseek.protocol.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * 解析器的资源上限用例。
 *
 * 这些用例守的是**性能契约**而不是功能：一份畸形/恶意的上游输出不能让解析器在长流里退化。
 */
class DsmlEnvelopeParserLimitsTest {

    /**
     * 开启标签迟迟不闭合时必须封顶。
     *
     * 未封顶时 `parseOpenTag` 每次都从头解析整个标签，逐字符喂 200 KB 是 O(n²)（约 10^10 次比较，
     * 分钟级）；封顶后只解析到 4 KiB 就 fail-closed，因此这里同时断言耗时上界。
     */
    @Test
    fun `unterminated open tag is capped instead of re-parsed quadratically`() {
        val parser = DsmlEnvelopeParser()
        // 容器开着，随后是一个永远不闭合的 invoke 标签（20 万个字符）
        val hostile = "\n" + MARKER + " invoke name=\"" + "a".repeat(200_000)

        val elapsed = measureTime {
            val visible = StringBuilder()
            val reasons = mutableListOf<String>()

            fun collect(segments: List<DsmlSegment>) {
                for (segment in segments) {
                    when (segment) {
                        is DsmlSegment.Text -> visible.append(segment.text)
                        is DsmlSegment.ToolEnvelope -> reasons += segment.reason ?: "complete"
                    }
                }
            }

            collect(parser.append(MARKER + " calls>"))
            for (char in hostile) collect(parser.append(char.toString()))
            collect(parser.finish())

            assertTrue(reasons.contains("tag-too-long"), "必须按坏结构 fail-closed，实际：$reasons")
            assertTrue(!visible.contains("aaaa"), "畸形标签的尾部不得作为正文吐出：${visible.take(80)}")
        }

        assertTrue(elapsed.inWholeMilliseconds < 15_000, "畸形标签的处理必须封顶（实测 $elapsed）")
    }

    /** 没有定界符起始字符的 delta 走快路径：直接透传，且不影响后续信封的识别。 */
    @Test
    fun `plain deltas skip buffering without breaking later envelope detection`() {
        val parser = DsmlEnvelopeParser()
        val plain = parser.append("ordinary prose without marker characters")
        assertEquals(1, plain.size)
        assertEquals("ordinary prose without marker characters", (plain[0] as DsmlSegment.Text).text)

        val afterEnvelope = parser.append(envelopeOf("echo", "v"))
        val envelope = afterEnvelope.filterIsInstance<DsmlSegment.ToolEnvelope>().single()
        assertEquals("echo", envelope.toolName)
        assertEquals("""{"value":"v"}""", envelope.argumentsJson)
        assertEquals("", afterEnvelope.filterIsInstance<DsmlSegment.Text>().joinToString("") { it.text })
    }

    /** 直填 JSON 的 body 校验通过后原样透传（不再 tree 化后重新序列化）。 */
    @Test
    fun `direct json body is passed through verbatim`() {
        val body = """{"value":"a\"b","n":3}"""
        val segments = parserOf(
            MARKER + " calls>\n" +
                MARKER + " invoke name=\"echo\">\n" +
                body + "\n</" + MARKER + " invoke>\n</" + MARKER + " calls>",
        )
        val envelope = segments.filterIsInstance<DsmlSegment.ToolEnvelope>().single()
        assertEquals(body, envelope.argumentsJson)
    }

    /**
     * 畸形结构必须**隔离到信封闭合处**，中间的任何字符都不许当正文（安全评审 MEDIUM-1）。
     *
     * 曾经的实现用「下一个 `>`」当边界，而信封里第一个 `>` 往往就是畸形标签自己的结尾——参数正文
     * 于是被当成普通正文吐出去、写进历史并被回放。现在隔离到 `</` + 定界符 为止；找不到就一直
     * 隔离到流结束（fail-closed，只保留畸形点之前的正文）。
     */
    @Test
    fun `malformed tag quarantines the rest of the envelope body`() {
        val segments = parserOf("前" + MARKER + "!!> 被隔离的参数正文> 后正文")
        val visible = segments.filterIsInstance<DsmlSegment.Text>().joinToString("") { it.text }
        val reasons = segments.filterIsInstance<DsmlSegment.ToolEnvelope>().mapNotNull { it.reason }

        assertEquals("前", visible, "畸形点之后的内容属于信封内部，一个字都不许当正文")
        assertTrue(reasons.contains("malformed-tag"), "畸形结构必须按不可执行上报，实际：$reasons")
    }

    /** 畸形之后仍有完整信封闭合时，闭合之后的正文要正常输出。 */
    @Test
    fun `prose after the envelope close survives a quarantined malformed region`() {
        val input = "前" + MARKER + "!!> 内部正文</" + MARKER + " calls> 后正文"
        val segments = parserOf(input)
        val visible = segments.filterIsInstance<DsmlSegment.Text>().joinToString("") { it.text }

        assertEquals("前 后正文", visible)
    }

    /**
     * 参数体很长但**结构完好**的信封不得被当成畸形（安全评审 HIGH-2）。
     *
     * 曾经的实现拿「标签起点到缓冲区末尾」跟标签上限比，于是一次性喂整条消息（历史回放就是这样）
     * 时，8 KB 的参数体会让整个信封被误判为 tag-too-long，参数正文还会被当正文吐出去。
     */
    @Test
    fun `well-formed envelope with a long parameter body survives one-shot parsing`() {
        val payload = "A".repeat(8 * 1024)
        val segments = parserOf(envelopeOf("echo", payload))

        val envelope = segments.filterIsInstance<DsmlSegment.ToolEnvelope>().single()
        assertEquals("echo", envelope.toolName)
        assertNull(envelope.reason)
        assertEquals(payload, argumentsOf(envelope).getValue("value").jsonPrimitive.content)
        assertEquals("", segments.filterIsInstance<DsmlSegment.Text>().joinToString("") { it.text })
    }

    private fun parserOf(text: String): List<DsmlSegment> = DsmlEnvelopeParser().let { it.append(text) + it.finish() }

    private fun argumentsOf(envelope: DsmlSegment.ToolEnvelope): JsonObject =
        Json.parseToJsonElement(assertNotNull(envelope.argumentsJson)).jsonObject
}

private val BAR: Char = '\uFF5C'

private val MARKER: String = "$BAR${BAR}DSML$BAR$BAR"

private fun envelopeOf(tool: String, value: String): String = buildString {
    append(MARKER).append(" calls>\n")
    append(MARKER).append(" invoke name=\"").append(tool).append("\">\n")
    append(MARKER).append(" parameter name=\"value\" string=\"true\">").append(value)
        .append("</").append(MARKER).append(" parameter>\n")
    append("</").append(MARKER).append(" invoke>\n")
    append("</").append(MARKER).append(" calls>")
}
