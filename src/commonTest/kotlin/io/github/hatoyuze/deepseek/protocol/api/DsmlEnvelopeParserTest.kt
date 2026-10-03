package io.github.hatoyuze.deepseek.protocol.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `DsmlEnvelopeParser` 与 `withoutDsmlEnvelopes` 的契约测试：上游把内部工具调用语法当正文下发时，
 * 正文必须干净、可执行的信封必须被完整还原。
 *
 * 定界符一律写成转义（`\uFF5C` 是全角竖线），源码里不出现难以辨认的全角竖线字面量。
 */
class DsmlEnvelopeParserTest {

    @Test
    fun `double bar wrapper with two parameters yields one envelope`() {
        val segments = parse(wrappedWeatherCall())

        val envelope = envelopes(segments).single()
        assertEquals("get_weather", envelope.toolName)
        assertNull(envelope.reason)
        val arguments = argumentsOf(envelope)
        assertEquals(setOf("location", "days"), arguments.keys)
        assertEquals("Hangzhou", arguments.getValue("location").jsonPrimitive.content)
        assertEquals(3, arguments.getValue("days").jsonPrimitive.int)
    }

    @Test
    fun `prose before and after an envelope is preserved verbatim`() {
        val before = "Let me check the weather.\n"
        val after = "\nAll done."

        val segments = parse(before + wrappedWeatherCall() + after)

        assertEquals(before + after, texts(segments))
        assertEquals(1, envelopes(segments).size)
    }

    @Test
    fun `single bar no leading bar and ascii gateway delimiters parse identically`() {
        for (marker in listOf(SINGLE_BAR, NO_LEADING_BAR, ASCII_GATEWAY)) {
            val input = "before " + wrap(
                marker,
                invoke(marker, "get_weather", parameter(marker, "location", "true", "Hangzhou")),
            ) + " after"

            val segments = parse(input)

            assertEquals("before  after", texts(segments), "定界符变体 <$marker> 吞掉了正文")
            val envelope = envelopes(segments).single()
            assertEquals("get_weather", envelope.toolName, "定界符变体 <$marker> 未解析出调用")
            assertEquals("Hangzhou", argumentsOf(envelope).getValue("location").jsonPrimitive.content)
        }
    }

    @Test
    fun `bare invoke without a wrapper is parsed and the following prose survives`() {
        val call = invoke(DOUBLE_BAR, "get_weather", parameter(DOUBLE_BAR, "location", "true", "Hangzhou"))

        val segments = parse(call + "Trailing prose.")

        val envelope = envelopes(segments).single()
        assertEquals("get_weather", envelope.toolName, "没有容器时也必须解析出可执行的调用")
        assertNull(envelope.reason)
        assertEquals("Hangzhou", argumentsOf(envelope).getValue("location").jsonPrimitive.content)
        assertEquals("Trailing prose.", texts(segments))
    }

    @Test
    fun `self-closing invoke yields empty arguments`() {
        val input = "Ping: " + wrap(DOUBLE_BAR, DOUBLE_BAR + "invoke name=\"ping\" />") + " done"

        val segments = parse(input)

        val envelope = envelopes(segments).single()
        assertEquals("ping", envelope.toolName)
        assertNull(envelope.reason)
        assertEquals("{}", envelope.argumentsJson)
        assertEquals("Ping:  done", texts(segments))
    }

    @Test
    fun `bare json body is parsed into the arguments object`() {
        val call = invoke(DOUBLE_BAR, "get_weather", "{\"location\":\"HZ\",\"days\":3}\n")

        val segments = parse(wrap(DOUBLE_BAR, call))

        val envelope = envelopes(segments).single()
        assertEquals("get_weather", envelope.toolName)
        assertNull(envelope.reason)
        val arguments = argumentsOf(envelope)
        assertEquals("HZ", arguments.getValue("location").jsonPrimitive.content)
        assertEquals(3, arguments.getValue("days").jsonPrimitive.int)
    }

    @Test
    fun `multi-line parameter value with quotes and bars survives intact`() {
        val value = "line1\nline2 \"quoted\" | pipe"
        val call = invoke(DOUBLE_BAR, "echo", parameter(DOUBLE_BAR, "text", "true", value))

        val segments = parse(wrap(DOUBLE_BAR, call))

        assertEquals(value, argumentsOf(envelopes(segments).single()).getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `parameter value containing an envelope-like marker is not truncated`() {
        val value = "before " + DOUBLE_BAR + "invoke name=\"evil\"> after"
        val call = invoke(DOUBLE_BAR, "echo", parameter(DOUBLE_BAR, "text", "true", value))

        val segments = parse(wrap(DOUBLE_BAR, call))

        // 值只在该 parameter 自己的闭合标签处结束，内层「像信封开头」的文本原样保留
        assertEquals(value, argumentsOf(envelopes(segments).single()).getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `marker split across deltas never leaks envelope fragments into text`() {
        val before = "Let me check the weather.\n"
        val after = "\nAll done."
        val input = before + wrappedWeatherCall() + after

        val parser = DsmlEnvelopeParser()
        val segments = mutableListOf<DsmlSegment>()
        for (char in input) {
            val emitted = parser.append(char.toString())
            assertFalse(texts(emitted).contains("DSML"), "半个定界符漏进了正文：$emitted")
            segments += emitted
        }
        segments += parser.finish()

        assertEquals(before + after, texts(segments), "逐字符喂入与一次性喂入必须得到同样的正文")
        val envelope = envelopes(segments).single()
        assertEquals("get_weather", envelope.toolName)
        assertEquals(setOf("location", "days"), argumentsOf(envelope).keys)
    }

    @Test
    fun `unterminated envelope is reported as unterminated after finish`() {
        val parser = DsmlEnvelopeParser()
        val streamed = parser.append(
            "Calling a tool.\n" + DOUBLE_BAR + "calls>\n" + DOUBLE_BAR + "invoke name=\"get_weather\">\n" +
                DOUBLE_BAR + "parameter name=\"city\" string=\"true\">HZ",
        )
        val finished = parser.finish()

        assertEquals("Calling a tool.\n", texts(streamed))
        assertFalse(texts(streamed + finished).contains("DSML"))
        val envelope = envelopes(finished).single()
        assertEquals("get_weather", envelope.toolName)
        assertEquals("unterminated-envelope", envelope.reason)
        assertNull(envelope.argumentsJson)
    }

    @Test
    fun `stray closing fragment in prose never reaches the visible text`() {
        val segments = parse("Before." + "</" + DOUBLE_BAR + "calls>" + " After.")

        assertFalse(texts(segments).contains("DSML"))
        assertTrue(texts(segments).contains("After."))
    }

    @Test
    fun `withoutDsmlEnvelopes strips the envelope and keeps the prose`() {
        val before = "Before. "
        val after = " After."

        assertEquals(before + after, (before + wrappedWeatherCall() + after).withoutDsmlEnvelopes())
    }

    @Test
    fun `withoutDsmlEnvelopes returns the same string when there is no marker`() {
        val plain = "no envelope in this text at all"

        assertSame(plain, plain.withoutDsmlEnvelopes())
    }

    @Test
    fun `withoutDsmlEnvelopes drops a stray closing fragment`() {
        val cleaned = ("Before." + "</" + DOUBLE_BAR + "calls>" + " After.").withoutDsmlEnvelopes()

        assertFalse(cleaned.contains("DSML"), "孤立的闭合碎片必须被剔除，不能留在正文里")
        assertTrue(cleaned.contains("After."))
    }

    @Test
    fun `plain prose with a single bar or the DSML word is passed through unchanged`() {
        val prose = "The DSML label and the \uFF5C bar are just text."

        assertEquals(prose, texts(parse(prose)))
    }

    @Test
    fun `envelope over the size limit is dropped without leaking into text`() {
        val parser = DsmlEnvelopeParser(maxEnvelopeChars = 64)
        val segments = parser.append(
            "Tool:\n" + DOUBLE_BAR + "calls>\n" + DOUBLE_BAR + "invoke name=\"big\">" + "A".repeat(500),
        ) + parser.finish()

        val visible = texts(segments)
        assertFalse(visible.contains("DSML"))
        assertFalse(visible.contains("AAA"))
        val envelope = envelopes(segments).single()
        assertEquals("envelope-too-large", envelope.reason)
        assertNull(envelope.argumentsJson)
    }

    @Test
    fun `all three wrapper tag names are accepted`() {
        for (wrapper in listOf("calls", "tool_calls", "function_calls")) {
            val body = DOUBLE_BAR + wrapper + ">\n" +
                invoke(DOUBLE_BAR, "get_weather", parameter(DOUBLE_BAR, "location", "true", "HZ")) +
                "</" + DOUBLE_BAR + wrapper + ">"

            val envelope = envelopes(parse(body)).single()
            assertEquals("get_weather", envelope.toolName, "容器标签 <$wrapper> 未被接受")
            assertEquals("HZ", argumentsOf(envelope).getValue("location").jsonPrimitive.content)
        }
    }

    @Test
    fun `invalid json in a non-string parameter falls back to a text value`() {
        val call = invoke(DOUBLE_BAR, "echo", parameter(DOUBLE_BAR, "value", "false", "not json"))

        val segments = parse(wrap(DOUBLE_BAR, call))

        assertEquals("not json", argumentsOf(envelopes(segments).single()).getValue("value").jsonPrimitive.content)
    }

    @Test
    fun `single quoted attributes are accepted`() {
        val call = invoke(DOUBLE_BAR, "echo", parameter(DOUBLE_BAR, "location", "true", "HZ"))
            .replace("\"", "'")

        val envelope = envelopes(parse(wrap(DOUBLE_BAR, call))).single()

        assertEquals("echo", envelope.toolName)
        assertEquals("HZ", argumentsOf(envelope).getValue("location").jsonPrimitive.content)
    }

    @Test
    fun `invoke without a name attribute is reported as missing tool name`() {
        val call = DOUBLE_BAR + "invoke>\n" + parameter(DOUBLE_BAR, "x", "true", "1") +
            "</" + DOUBLE_BAR + "invoke>\n"

        val envelope = envelopes(parse(wrap(DOUBLE_BAR, call))).single()

        assertNull(envelope.toolName)
        assertEquals("missing-tool-name", envelope.reason)
    }

    @Test
    fun `two invoke elements in one wrapper yield two envelopes`() {
        val input = wrap(
            DOUBLE_BAR,
            invoke(DOUBLE_BAR, "first", parameter(DOUBLE_BAR, "x", "true", "1")) + "\n" +
                invoke(DOUBLE_BAR, "second", parameter(DOUBLE_BAR, "y", "false", "2")) + "\n",
        ) + "after"

        val segments = parse(input)

        val parsed = envelopes(segments)
        assertEquals(listOf("first", "second"), parsed.map { it.toolName })
        assertEquals("1", argumentsOf(parsed[0]).getValue("x").jsonPrimitive.content)
        assertEquals(2, argumentsOf(parsed[1]).getValue("y").jsonPrimitive.int)
        assertEquals("after", texts(segments))
    }

    @Test
    fun `unknown tag inside an envelope is skipped together with its body`() {
        val input = "A" + DOUBLE_BAR + "bogus attr=\"1\">junk</" + DOUBLE_BAR + "bogus>B"

        val visible = texts(parse(input))

        assertTrue(visible.contains("A"))
        assertFalse(visible.contains("junk"))
        assertFalse(visible.contains("DSML"))
    }

    // ── 夹具与断言辅助 ──

    /** 线上最常见的信封：双竖线定界符 + 容器 + 一个两参调用。 */
    private fun wrappedWeatherCall(): String = wrap(
        DOUBLE_BAR,
        invoke(
            DOUBLE_BAR,
            "get_weather",
            parameter(DOUBLE_BAR, "location", "true", "Hangzhou") +
                parameter(DOUBLE_BAR, "days", "false", "3"),
        ),
    )

    private fun wrap(marker: String, body: String): String = marker + "calls>\n" + body + "</" + marker + "calls>"

    private fun invoke(marker: String, name: String, body: String): String =
        marker + "invoke name=\"" + name + "\">\n" + body + "</" + marker + "invoke>"

    private fun parameter(marker: String, name: String, string: String?, value: String): String {
        val stringAttribute = if (string == null) "" else " string=\"$string\""
        return marker + "parameter name=\"" + name + "\"" + stringAttribute + ">" + value +
            "</" + marker + "parameter>\n"
    }

    /** 一次性喂完再收尾，等价于历史回放清洗的单次调用路径。 */
    private fun parse(text: String): List<DsmlSegment> =
        DsmlEnvelopeParser().let { it.append(text) + it.finish() }

    private fun texts(segments: List<DsmlSegment>): String =
        segments.filterIsInstance<DsmlSegment.Text>().joinToString("") { it.text }

    private fun envelopes(segments: List<DsmlSegment>): List<DsmlSegment.ToolEnvelope> =
        segments.filterIsInstance<DsmlSegment.ToolEnvelope>()

    /** 参数对象的比较与 key 顺序无关，因此断言 `JsonObject` 而不是字符串。 */
    private fun argumentsOf(envelope: DsmlSegment.ToolEnvelope): JsonObject =
        Json.parseToJsonElement(assertNotNull(envelope.argumentsJson)).jsonObject

    private companion object {
        /** 双竖线定界符（全角竖线 ×2 + `DSML` + 全角竖线 ×2），实测最常见的线上形态。 */
        const val DOUBLE_BAR: String = "\uFF5C\uFF5CDSML\uFF5C\uFF5C"

        /** 单竖线定界符（全角竖线 + `DSML` + 全角竖线）。 */
        const val SINGLE_BAR: String = "\uFF5CDSML\uFF5C"

        /** 无前导竖线定界符（`DSML` + 全角竖线）。 */
        const val NO_LEADING_BAR: String = "DSML\uFF5C"

        /** 网关转码过的 ASCII 形态：`< | DSML |`。 */
        const val ASCII_GATEWAY: String = "< | DSML |"
    }
}
