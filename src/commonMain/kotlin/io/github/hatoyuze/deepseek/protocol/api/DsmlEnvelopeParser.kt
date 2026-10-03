package io.github.hatoyuze.deepseek.protocol.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 所有线上定界符变体都包含这个子串，作为「这串文本值得细看」的便宜前置判断。
 *
 * 它同时用于流式过滤与历史回放清洗，因此对模块内可见。
 */
internal const val DSML_SUBSTRING: String = "DSML"

/**
 * 流式内容里出现的「内部工具调用信封」解析结果。
 *
 * 上游偶发地把模型的原生工具调用语法当作正文下发（官方文档从未定义该语法，见
 * `README.md` 的「上游工具调用语法泄漏」一节）。本类型是该区域被识别后的最小事实描述，
 * **不包含任何策略**：是否执行、是否只剔除，由调用方（`DeepseekCore.streamLoop`）决定。
 */
internal sealed interface DsmlSegment {

    /** 信封之外的普通正文，应当原样展示。 */
    data class Text(val text: String) : DsmlSegment

    /**
     * 一个工具调用候选。
     *
     * @property toolName 信封里写明的工具名；解析不出名称时为 `null`
     * @property argumentsJson 组装好的 JSON 参数对象；信封不完整或结构非法时为 `null`
     * @property reason 非 `null` 表示这个候选**不可执行**（未闭合、超限、结构非法、缺工具名等），
     *   内容只用于日志
     */
    data class ToolEnvelope(
        val toolName: String?,
        val argumentsJson: String?,
        val reason: String? = null,
    ) : DsmlSegment
}

/**
 * 内部工具调用语法（下称「信封」）的**增量**解析器：喂入流式 delta，吐出可见正文与工具调用候选。
 *
 * ## 它认什么
 *
 * 定界符取全角竖线（U+FF5C）与 ASCII 竖线四种变体：双竖线（实测最常见的线上形态）、单竖线、
 * 无前导竖线、以及网关转码过的 `< | DSML |`。标签名与结构：
 *
 * - 容器（可缺省）：`calls` / `tool_calls` / `function_calls`
 * - 调用：`invoke`，属性 `name`；支持自闭合形态（零参调用）
 * - 参数：`parameter`，属性 `name` 与可选 `string`（`true` 原样文本、`false` 按 JSON 解析）；
 *   一个 `invoke` 的 body 也允许直接写 JSON 对象
 *
 * ## 为什么必须是增量且线性的
 *
 * SSE delta 是 token 级的，几 KB 的信封会被切成几十上百个 delta；定界符本身也可能被切开
 * （上游 survey 把「start marker 被切分」列为泄漏的独立成因）。因此本解析器：
 *
 * - 逐字符匹配定界符，**数据不足时扣留**而不是退化去匹配更短的变体——双竖线形态的内部天然包含
 *   单竖线形态，贪心匹配短变体会把信封切错位置（这是实现过程中真实踩过的坑，用例见
 *   `InlineToolCallRecoveryTest.marker split into single characters never leaks a partial marker`）；
 * - 用游标推进而非每次从头重扫，整体代价 O(n)，信封缓冲区另有上限 [maxEnvelopeChars]；
 * - 结构性异常一律 **fail-closed**：宁可丢弃，绝不把信封内容或碎片放进正文。
 *
 * ## 已知边界
 *
 * - 信封出现在被 JSON 转义的字符串里（转义引号 `\"`）时，标签属性无法可靠还原，会走
 *   fail-closed 分支：整块丢弃并记日志，不执行调用。
 * - 标签结构本身坏掉时同样 fail-closed，丢弃到**当前缓冲区末尾**：SSE delta 是 token 级的，
 *   因此代价通常只有这一小段文本；宁可少显示，也不把信封碎片当正文。
 * - 未闭合的信封会一直等到 `finish()` 才上报，因此 `invoke` 里的超大 body 只受
 *   [maxEnvelopeChars] 约束（超过即丢弃）。
 *
 * ## 线程模型
 *
 * **实例非线程安全，且不应跨协程共享**：一条流一个实例（`streamLoop` 里每次迭代新建），
 * 只在收集该流的协程内使用。历史回放清洗用 [withoutDsmlEnvelopes] 这个一次性入口。
 *
 * @param maxEnvelopeChars 单个信封缓冲区上限（字符）。超限即判为不可执行并丢弃，
 *   防止一个未闭合的信封把整条流的剩余内容吞进内存。
 */
internal class DsmlEnvelopeParser(
    private val maxEnvelopeChars: Int = MAX_ENVELOPE_CHARS,
) {

    /** 解析阶段。`SKIP` 用于丢弃我们不认识的标签区间（连同它的 body）。 */
    private enum class Phase { TEXT, ENVELOPE, INVOKE, PARAMETER, SKIP }

    private val buffer = StringBuilder()
    private var phase = Phase.TEXT

    /** 已消费位置：正文态从这里开始扫描，也是压缩缓冲区的依据。 */
    private var textCursor = 0

    /** 信封内「下一个标签事件」的扫描游标（纯前视，失败时前移到尾部附近，避免重复扫描）。 */
    private var scanCursor = 0

    /** 已消费定界符、但标签本身还没解析的起点；`-1` 表示没有待解析标签。 */
    private var pendingTagStart = -1

    /** 当前信封的起始位置，用于上限判断。 */
    private var envelopeStart = 0

    /** 当前 `invoke` 的名称、body 起点与已解析参数。 */
    private var invokeName: String? = null
    private var invokeBodyStart = -1

    /** `invoke` body 直接写 JSON 对象时，校验通过后原样保留（省一次 tree 化 + 重新序列化）。 */
    private var directArgumentsJson: String? = null
    private val invokeParameters = LinkedHashMap<String, JsonElement>()

    /** `PARAMETER` / `SKIP` 态：待结束的标签名、body 起点、参数名与 `string` 语义。 */
    private var openTagName: String? = null
    private var openTagBodyStart = -1
    private var parameterName: String? = null
    private var parameterIsString = true

    /** 是否见过容器标签（决定 `invoke` 闭合后留在信封里，还是回到正文态）。 */
    private var wrapperSeen = false

    /**
     * fail-closed 之后的**重同步**状态：畸形处之后的内容仍然是畸形构造的尾巴，不能当正文吐出去。
     * 只在下一个标签边界（或信封闭合处）恢复正文态；等待期间只保留可能构成跨 delta 序列的一小段
     * 尾巴，因此内存与时间都与上游灌多少垃圾无关。
     */
    private var resync: ResyncMode = ResyncMode.NONE

    /**
     * 被丢弃（未展示）的字符数累计。
     *
     * fail-closed 的代价是「宁可少显示」，但调用方至少要知道少了多少——否则一次被吞掉的长回答
     * 与一次简短回答在输出上无法区分。调用方（`InlineToolCallRecovery`）会把它写进汇总日志。
     */
    var droppedChars: Int = 0
        private set

    /**
     * 喂入一个流式 delta，返回本次可确定的解析结果（可能同时含正文与工具调用候选）。
     */
    fun append(delta: String): List<DsmlSegment> {
        if (delta.isEmpty()) return emptyList()
        // 快路径（绝大多数 delta 都走这里）：正文态、没有扣留任何字符、不在重同步、且这批字符不可能
        // 开启定界符。定界符首字符只有三种（全角竖线 / `<` / `D`），因此判定完就可以直接当正文输出，
        // 不碰缓冲区——把「无信封」这条主路径的分配与拷贝压到最低
        if (resync == ResyncMode.NONE && phase == Phase.TEXT && textCursor == buffer.length &&
            delta.none { it == BAR || it == '<' || it == DSML[0] }
        ) {
            return listOf(DsmlSegment.Text(delta))
        }
        buffer.append(delta)
        return drain()
    }

    /**
     * 流结束时的收尾：未闭合的信封按不可执行上报（不输出任何正文），并清空内部状态。
     *
     * 调用后本实例不可再用（每条流 `finish()` 一次）。
     */
    fun finish(): List<DsmlSegment> {
        val out = when (phase) {
            // 正文态扣留的尾部：只要它不含竖线，就只是「形似定界符前缀」的普通文本（例如正文以
            // 字母 DSML 结尾），原样放行；含竖线的尾部是定界符碎片，按 fail-closed 丢弃。
            // 仍在重同步中时一律丢弃——那段内容是畸形构造的尾巴，不是正文
            Phase.TEXT -> {
                val tail = if (textCursor < buffer.length) buffer.substring(textCursor) else ""
                if (tail.isNotEmpty()) droppedChars += tail.length
                if (resync == ResyncMode.NONE && tail.isNotEmpty() &&
                    tail.none { it == BAR || it == '|' || it == '<' || it == '/' }
                ) {
                    listOf(DsmlSegment.Text(tail))
                } else {
                    emptyList()
                }
            }
            Phase.ENVELOPE, Phase.INVOKE, Phase.PARAMETER, Phase.SKIP -> {
                if (buffer.length > textCursor) droppedChars += buffer.length - textCursor
                listOf(DsmlSegment.ToolEnvelope(invokeName, null, REASON_UNTERMINATED))
            }
        }
        reset()
        return out
    }

    // ── 主循环 ──

    private fun drain(): List<DsmlSegment> {
        val out = mutableListOf<DsmlSegment>()
        while (true) {
            val progressed = if (resync != ResyncMode.NONE) {
                advanceResync()
            } else {
                when (phase) {
                    Phase.TEXT -> scanText(out)
                    Phase.ENVELOPE -> scanEnvelope(out)
                    Phase.INVOKE -> scanInvokeBody(out)
                    Phase.PARAMETER -> scanRawBody(out, keepValue = true)
                    Phase.SKIP -> scanRawBody(out, keepValue = false)
                }
            }
            if (!progressed) break
        }
        if (phase == Phase.TEXT && textCursor > COMPACT_THRESHOLD) compact()
        return out
    }

    /** @return `true` 表示状态已推进、可继续下一轮；`false` 表示需要更多数据 */
    private fun scanText(out: MutableList<DsmlSegment>): Boolean {
        var index = textCursor
        while (index < buffer.length) {
            when (val step = matchMarkerAt(buffer, index)) {
                MarkerStep.NoMatch -> index++
                MarkerStep.Partial -> {
                    emitText(out, index)
                    return false
                }
                is MarkerStep.Complete -> {
                    // 定界符前若紧跟 `</`，这是一个孤立闭合标签（上游也会单独泄漏闭合碎片）：
                    // 连同它所在的标签一起丢弃，但前面的正文必须先放出去
                    if (index >= 2 && buffer[index - 1] == '/' && buffer[index - 2] == '<') {
                        val tagEnd = buffer.indexOf('>', index + step.length)
                        if (tagEnd < 0) {
                            if (buffer.length - index > MAX_CLOSE_TAG_TAIL) {
                                emitText(out, index - 2)
                                out += DsmlSegment.ToolEnvelope(null, null, REASON_STRAY_CLOSE_TAG)
                                consumeTo(buffer.length)
                                return true
                            }
                            emitText(out, index - 2)
                            return false
                        }
                        emitText(out, index - 2)
                        consumeTo(tagEnd + 1)
                        // 找不到 `>` 的残缺碎片上面已经报过；这里同样要上报，否则回放清洗
                        // 会因为「一个信封都没识别出来」而把原样字符串还回去（碎片留在历史里）
                        out += DsmlSegment.ToolEnvelope(null, null, REASON_STRAY_CLOSE_TAG)
                        return true
                    }

                    emitText(out, index)
                    envelopeStart = index
                    wrapperSeen = false
                    invokeName = null
                    invokeBodyStart = -1
                    invokeParameters.clear()
                    consumeTo(index + step.length)
                    scanCursor = index + step.length
                    pendingTagStart = index + step.length
                    phase = Phase.ENVELOPE
                    return true
                }
            }
        }
        emitText(out, buffer.length)
        return false
    }

    /**
     * 把 `[textCursor, end)` 作为正文放出；`end` 前若是一个普通的 `<`
     * （`<｜DSML｜invoke …` 形态），把它一起吞掉，别在正文尾巴留个孤零零的 `<`。
     */
    private fun emitText(out: MutableList<DsmlSegment>, end: Int) {
        var visibleEnd = minOf(end, buffer.length)
        // 扣住尾部可能构成 `</` + 定界符 的 1~2 个字符：`<` 与 `</` 都不能先吐出去，
        // 否则 delta 边界落在闭合标签中间时会漏出半个 `</`（安全评审 LOW-1）
        if (visibleEnd > textCursor && buffer[visibleEnd - 1] == '<') visibleEnd--
        if (visibleEnd - 1 > textCursor && buffer[visibleEnd - 1] == '/' && buffer[visibleEnd - 2] == '<') {
            visibleEnd -= 2
        }
        if (visibleEnd > textCursor) {
            out += DsmlSegment.Text(buffer.substring(textCursor, visibleEnd))
            textCursor = visibleEnd
        }
    }

    /** 信封态：逐个吃标签，直到容器闭合（或裸 `invoke` 闭合后回到正文态）。 */
    private fun scanEnvelope(out: MutableList<DsmlSegment>): Boolean {
        if (overEnvelopeLimit(out)) return true

        val tagStart = pendingTagStart
        if (tagStart >= 0) {
            // 开启标签迟迟不闭合时必须封顶：`parseOpenTag` 每次都从头解析整个标签，
            // 未封顶时它的代价随标签长度平方增长（性能评审的高危项），而真实标签只有几十字节
            return when (val parse = parseOpenTag(buffer, tagStart)) {
                TagParse.Incomplete -> {
                    // 封顶只对「仍未闭合」的标签生效：完整标签是一次 O(标签长度) 的解析，
                    // 而**不是**按标签起点到缓冲区末尾来衡量——后者会把「参数体很长但结构完好」的
                    // 信封误判成畸形，进而把参数正文当正文吐出去（安全评审 HIGH-2）
                    if (buffer.length - tagStart > MAX_TAG_CHARS) {
                        resync = ResyncMode.CLOSE_TAG
                        return failEnvelope(out, REASON_TAG_TOO_LONG, resumeAt = tagStart)
                    }
                    scanCursor = tagStart
                    false
                }
                TagParse.Malformed -> {
                    // 畸形之后一律隔离到信封的闭合标签为止：下一个 `>` 可能是参数值里的字符，
                    // 用它当边界会把信封内部（含工具参数）当正文吐出去（安全评审 MEDIUM-1）
                    resync = ResyncMode.CLOSE_TAG
                    failEnvelope(out, REASON_MALFORMED_TAG, resumeAt = tagStart)
                }
                is TagParse.Ok -> {
                    val tag = parse.tag
                    pendingTagStart = -1
                    consumeTo(tag.end)
                    scanCursor = tag.end
                    when {
                        tag.name in WRAPPER_TAGS -> {
                            wrapperSeen = true
                            true
                        }
                        tag.name == TAG_INVOKE -> startInvoke(tag, out)
                        tag.selfClosing -> true // 无关的自闭合标签：忽略
                        else -> {
                            openTagName = tag.name
                            openTagBodyStart = tag.end
                            phase = Phase.SKIP
                            true
                        }
                    }
                }
            }
        }

        return when (val event = findNextTagEvent(buffer, scanCursor)) {
            null -> {
                advanceScanCursor()
                false
            }
            is TagEvent.CloseTag -> {
                consumeTo(event.end)
                resetEnvelopeState()
                true
            }
            is TagEvent.OpenMarker -> {
                consumeTo(event.end)
                scanCursor = event.end
                pendingTagStart = event.end
                true
            }
        }
    }

    /** `invoke` body：既可能是若干 `parameter`，也可能是一段直填的 JSON 对象。 */
    private fun scanInvokeBody(out: MutableList<DsmlSegment>): Boolean {
        if (overEnvelopeLimit(out)) return true

        val tagStart = pendingTagStart
        if (tagStart >= 0) {
            return when (val parse = parseOpenTag(buffer, tagStart)) {
                TagParse.Incomplete -> {
                    // 封顶只对「仍未闭合」的标签生效：完整标签是一次 O(标签长度) 的解析，
                    // 而**不是**按标签起点到缓冲区末尾来衡量——后者会把「参数体很长但结构完好」的
                    // 信封误判成畸形，进而把参数正文当正文吐出去（安全评审 HIGH-2）
                    if (buffer.length - tagStart > MAX_TAG_CHARS) {
                        resync = ResyncMode.CLOSE_TAG
                        return failEnvelope(out, REASON_TAG_TOO_LONG, resumeAt = tagStart)
                    }
                    scanCursor = tagStart
                    false
                }
                TagParse.Malformed -> {
                    // 畸形之后一律隔离到信封的闭合标签为止：下一个 `>` 可能是参数值里的字符，
                    // 用它当边界会把信封内部（含工具参数）当正文吐出去（安全评审 MEDIUM-1）
                    resync = ResyncMode.CLOSE_TAG
                    failEnvelope(out, REASON_MALFORMED_TAG, resumeAt = tagStart)
                }
                is TagParse.Ok -> {
                    val tag = parse.tag
                    pendingTagStart = -1
                    consumeTo(tag.end)
                    scanCursor = tag.end
                    if (tag.name == TAG_PARAMETER) {
                        parameterName = tag.attributes[ATTR_NAME]
                        parameterIsString = tag.attributes[ATTR_STRING]?.equals("true", ignoreCase = true) != false
                        if (tag.selfClosing) {
                            putParameter("")
                        } else {
                            openTagName = tag.name
                            openTagBodyStart = tag.end
                            phase = Phase.PARAMETER
                        }
                    } else {
                        openTagName = tag.name
                        openTagBodyStart = tag.end
                        phase = Phase.SKIP
                    }
                    true
                }
            }
        }

        return when (val event = findNextTagEvent(buffer, scanCursor)) {
            null -> {
                advanceScanCursor()
                false
            }
            is TagEvent.CloseTag -> {
                if (invokeParameters.isEmpty()) collectDirectJsonBody(event.start)
                consumeTo(event.end)
                scanCursor = event.end
                out += toolEnvelope()
                invokeName = null
                if (wrapperSeen) {
                    phase = Phase.ENVELOPE
                } else {
                    resetEnvelopeState()
                }
                true
            }
            is TagEvent.OpenMarker -> {
                consumeTo(event.end)
                scanCursor = event.end
                pendingTagStart = event.end
                true
            }
        }
    }

    /** `parameter` / 未知标签的原始 body：一直吃到该标签自己的闭合标签。 */
    private fun scanRawBody(out: MutableList<DsmlSegment>, keepValue: Boolean): Boolean {
        if (overEnvelopeLimit(out)) return true
        val tag = openTagName
        if (tag == null) {
            phase = Phase.INVOKE
            return true
        }
        val close = findCloseTag(buffer, tag, maxOf(scanCursor, openTagBodyStart))
        if (close == null) {
            advanceScanCursor()
            return false
        }
        val raw = if (openTagBodyStart in 0 until close.start) {
            buffer.substring(openTagBodyStart, close.start)
        } else {
            ""
        }
        consumeTo(close.end)
        scanCursor = close.end
        openTagName = null
        openTagBodyStart = -1
        if (keepValue) putParameter(raw)
        phase = Phase.INVOKE
        return true
    }

    // ── 元素装配 ──

    private fun startInvoke(tag: OpenTag, out: MutableList<DsmlSegment>): Boolean {
        invokeName = tag.attributes[ATTR_NAME]
        invokeBodyStart = tag.end
        invokeParameters.clear()
        directArgumentsJson = null
        if (!tag.selfClosing) {
            phase = Phase.INVOKE
            return true
        }
        out += toolEnvelope()
        invokeName = null
        if (!wrapperSeen) resetEnvelopeState()
        return true
    }

    /**
     * 直接写在 `invoke` body 里的 JSON 对象形态（SGLang 的 V4 format 2）。
     *
     * 只有在一个 `parameter` 都没解析出来时才尝试，避免把参数标签误当 JSON。
     */
    private fun collectDirectJsonBody(bodyEnd: Int) {
        if (invokeBodyStart < 0 || bodyEnd <= invokeBodyStart) return
        val raw = buffer.substring(invokeBodyStart, bodyEnd).trim()
        if (raw.length < 2 || raw.first() != '{' || raw.last() != '}') return
        if (runCatching { JSON.parseToJsonElement(raw) }.getOrNull() !is JsonObject) return
        directArgumentsJson = raw
    }

    private fun putParameter(rawValue: String) {
        val name = parameterName
        parameterName = null
        if (name.isNullOrBlank()) return
        val value = if (parameterIsString) {
            JsonPrimitive(rawValue)
        } else {
            runCatching { JSON.parseToJsonElement(rawValue.trim()) }.getOrElse { JsonPrimitive(rawValue) }
        }
        parameterIsString = true
        invokeParameters[name] = value
    }

    private fun toolEnvelope(): DsmlSegment.ToolEnvelope {
        val name = invokeName
        return if (name.isNullOrBlank()) {
            DsmlSegment.ToolEnvelope(null, null, REASON_MISSING_TOOL_NAME)
        } else {
            DsmlSegment.ToolEnvelope(name, buildArgumentsJson(), null)
        }
    }

    private fun buildArgumentsJson(): String {
        val direct = directArgumentsJson
        if (direct != null && invokeParameters.isEmpty()) return direct
        return buildJsonObject {
            invokeParameters.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    // ── 缓冲区与状态 ──

    private fun overEnvelopeLimit(out: MutableList<DsmlSegment>): Boolean {
        if (buffer.length - envelopeStart <= maxEnvelopeChars) return false
        // 超限的信封可能是**结构完好**的（只是太大）：重同步到它的闭合标签，避免把信封内部当正文
        resync = ResyncMode.CLOSE_TAG
        out += DsmlSegment.ToolEnvelope(invokeName, null, REASON_TOO_LARGE)
        scanCursor = buffer.length
        pendingTagStart = -1
        resetEnvelopeState()
        return true
    }

    /**
     * 结构异常：上报一个不可执行候选并丢弃畸形区域（fail-closed）。
     *
     * @param resumeAt 重同步的起点。畸形标签从它开始，因此**不能**把缓冲区整段丢掉——一次性喂整条
     *   消息（历史回放）时那样会连带吞掉畸形点之后的正文
     */
    private fun failEnvelope(out: MutableList<DsmlSegment>, reason: String, resumeAt: Int = textCursor): Boolean {
        out += DsmlSegment.ToolEnvelope(invokeName, null, reason)
        if (resumeAt > textCursor) textCursor = resumeAt
        scanCursor = buffer.length
        pendingTagStart = -1
        resetEnvelopeState()
        return true
    }

    /**
     * 重同步：丢弃畸形构造，直到下一个标签边界（或信封闭合处）再回到正文态。
     *
     * 在**当前缓冲区**里推进（而不是等下一个 delta）：一次性喂整条消息（历史回放）时，畸形点之后
     * 的正文同样要保住，不能整条丢掉。等待边界期间只保留 [MAX_CLOSE_TAG_TAIL] 个字符的尾巴，
     * 因此无论上游灌多少垃圾，这里都是 O(1) 内存、O(n) 时间。
     *
     * @return `true` 表示已越过边界、状态机可以继续；`false` 表示边界还没到，需要更多数据
     */
    private fun advanceResync(): Boolean {
        val target = when (resync) {
            ResyncMode.CLOSE_TAG -> closeTagEndAny(buffer, textCursor)
            ResyncMode.NONE -> NOT_FOUND
        }
        if (target < 0) {
            // 边界还没出现：只留可能构成跨 delta 序列的尾巴，其余连同已消费前缀一起丢掉
            val keepFrom = buffer.length - MAX_CLOSE_TAG_TAIL
            if (keepFrom > 0) {
                droppedChars += keepFrom
                buffer.deleteRange(0, keepFrom)
                textCursor = 0
            }
            return false
        }
        if (target > textCursor) {
            droppedChars += target - textCursor
            textCursor = target
        }
        resync = ResyncMode.NONE
        return true
    }

    /** 从 `from` 起找 `</` + 任意定界符 + 标签名 + `>` 的结束位置；找不到返回 [NOT_FOUND]。 */
    private fun closeTagEndAny(text: CharSequence, from: Int): Int {
        var position = maxOf(from, 0)
        while (true) {
            val marker = findMarkerFrom(text, position) ?: return NOT_FOUND
            position = marker.end
            val isClose = marker.start >= 2 && text[marker.start - 1] == '/' && text[marker.start - 2] == '<'
            if (!isClose) continue
            val end = closeTagEnd(text, position) ?: return NOT_FOUND
            return end
        }
    }

    /** 把 `[0, position)` 标记为已消费。 */
    private fun consumeTo(position: Int) {
        if (position > textCursor) textCursor = position
    }

    /** 信封内扫描失败时把前视游标推到尾部附近，避免下一次从头重扫（线性代价的关键）。 */
    private fun advanceScanCursor() {
        val floor = buffer.length - MAX_CLOSE_TAG_TAIL
        if (floor > scanCursor) scanCursor = floor
    }

    private fun resetEnvelopeState() {
        phase = Phase.TEXT
        wrapperSeen = false
        invokeName = null
        invokeBodyStart = -1
        invokeParameters.clear()
        directArgumentsJson = null
        openTagName = null
        openTagBodyStart = -1
        parameterName = null
        parameterIsString = true
        envelopeStart = 0
        pendingTagStart = -1
        scanCursor = textCursor
    }

    private fun reset() {
        buffer.clear()
        resync = ResyncMode.NONE
        textCursor = 0
        scanCursor = 0
        envelopeStart = 0
        pendingTagStart = -1
        resetEnvelopeState()
    }

    /** 只在正文态压缩缓冲区，避免破坏其它阶段里的绝对下标。 */
    private fun compact() {
        if (textCursor <= 0) return
        // `delete` 不是 common 成员（JVM-only）：用 kotlin.text 的 `deleteRange`
        buffer.deleteRange(0, textCursor)
        textCursor = 0
        scanCursor = 0
        pendingTagStart = -1
    }

    // ── 扫描原语 ──

    private class MarkerMatch(val start: Int, val end: Int)

    /** fail-closed 之后如何重同步：隔离到信封的闭合标签为止（找不到就一直隔离到流结束）。 */
    private enum class ResyncMode { NONE, CLOSE_TAG }

    /** 定界符匹配的一步结果。 */
    private sealed interface MarkerStep {

        /** 完整匹配，[length] 为定界符长度。 */
        data class Complete(val length: Int) : MarkerStep

        /** 从该位置起是某个定界符变体的真前缀：数据不足，必须等，不能退化去匹配更短的变体。 */
        data object Partial : MarkerStep

        /** 该位置不可能是定界符。 */
        data object NoMatch : MarkerStep
    }

    /** 信封内的标签事件：开启定界符（其后是待解析标签）或完整闭合标签。 */
    private sealed interface TagEvent {
        val start: Int
        val end: Int

        data class OpenMarker(override val start: Int, override val end: Int) : TagEvent

        data class CloseTag(override val start: Int, override val end: Int) : TagEvent
    }

    private sealed interface TagParse {
        data object Incomplete : TagParse

        data object Malformed : TagParse

        data class Ok(val tag: OpenTag) : TagParse
    }

    private class OpenTag(
        val name: String,
        val attributes: Map<String, String>,
        val selfClosing: Boolean,
        val end: Int,
    )

    private companion object {
        const val MAX_ENVELOPE_CHARS: Int = 1 shl 20 // 1 MiB
        const val COMPACT_THRESHOLD: Int = 8 shl 10
        const val MAX_CLOSE_TAG_TAIL: Int = 64

        /**
         * 单个开启标签的长度上限。`parseOpenTag` 每次从头解析整个标签，未封顶时代价随标签长度
         * 平方增长；真实标签只有几十字节，超过这个上限一律按坏结构处理（fail-closed）。
         */
        const val MAX_TAG_CHARS: Int = 4 shl 10

        /** 扫描原语统一的「没找到」哨兵：只用 `< 0` 判断，避免 0（合法下标）被当成命中。 */
        const val NOT_FOUND: Int = -1

        const val TAG_INVOKE: String = "invoke"
        const val TAG_PARAMETER: String = "parameter"
        const val ATTR_NAME: String = "name"
        const val ATTR_STRING: String = "string"

        const val REASON_UNTERMINATED: String = "unterminated-envelope"
        const val REASON_TOO_LARGE: String = "envelope-too-large"
        const val REASON_MISSING_TOOL_NAME: String = "missing-tool-name"
        const val REASON_STRAY_CLOSE_TAG: String = "stray-close-tag"
        const val REASON_MALFORMED_TAG: String = "malformed-tag"
        const val REASON_TAG_TOO_LONG: String = "tag-too-long"

        /** 定界符用的全角竖线（U+FF5C）。 */
        const val BAR: Char = '\uFF5C'

        /** 网关把全角竖线转码后的 ASCII 形态。 */
        const val ASCII_MARKER: String = "< | DSML |"

        const val DSML: String = "DSML"

        val WRAPPER_TAGS: Set<String> = setOf("calls", "tool_calls", "function_calls")

        val JSON: Json = Json { isLenient = false }

        /**
         * 从 `index` 起匹配定界符。
         *
         * 顺序很关键：**双竖线优先**，且不足时返回 [MarkerStep.Partial]。双竖线形态的内部同时
         * 包含单竖线形态，若在数据不足时就退化去匹配单竖线，会把信封从中间切开、把后半段当正文
         * 吐出去（正是线上要防的那类泄漏）。
         */
        fun matchMarkerAt(text: CharSequence, index: Int): MarkerStep {
            if (index >= text.length) return MarkerStep.NoMatch
            when (text[index]) {
                '<' -> return prefixStep(text, index, ASCII_MARKER)
                'D' -> return prefixStep(text, index, DSML + BAR)
                BAR -> Unit
                else -> return MarkerStep.NoMatch
            }

            // 以竖线开头：先看有几个竖线，决定按双竖线还是单竖线形态匹配
            val hasSecond = index + 1 < text.length
            val doubleBar = hasSecond && text[index + 1] == BAR
            if (!hasSecond) return MarkerStep.Partial
            val pattern = if (doubleBar) "$BAR$BAR$DSML$BAR$BAR" else "$BAR$DSML$BAR"
            return prefixStep(text, index, pattern)
        }

        /** `text` 从 `index` 起与 [pattern] 的关系：全匹配 / 真前缀（数据不足）/ 不匹配。 */
        fun prefixStep(text: CharSequence, index: Int, pattern: String): MarkerStep {
            val available = minOf(text.length - index, pattern.length)
            for (offset in 0 until available) {
                if (text[index + offset] != pattern[offset]) return MarkerStep.NoMatch
            }
            return if (available >= pattern.length) MarkerStep.Complete(pattern.length) else MarkerStep.Partial
        }

        /** 从 `from` 起找下一个完整定界符；找不到或数据不足时返回 `null`。 */
        fun findMarkerFrom(text: CharSequence, from: Int): MarkerMatch? {
            var index = maxOf(from, 0)
            while (index < text.length) {
                when (val step = matchMarkerAt(text, index)) {
                    MarkerStep.NoMatch -> index++
                    MarkerStep.Partial -> return null
                    is MarkerStep.Complete -> return MarkerMatch(index, index + step.length)
                }
            }
            return null
        }

        /** 找下一个标签事件。 */
        fun findNextTagEvent(text: CharSequence, from: Int): TagEvent? {
            val marker = findMarkerFrom(text, from) ?: return null
            val isClose = marker.start >= 2 && text[marker.start - 1] == '/' && text[marker.start - 2] == '<'
            if (!isClose) return TagEvent.OpenMarker(marker.start, marker.end)
            val end = closeTagEnd(text, marker.end) ?: return null
            return TagEvent.CloseTag(marker.start - 2, end)
        }

        /** 闭合标签的 `>` 是否已到齐；未到齐返回 `null`。 */
        fun closeTagEnd(text: CharSequence, from: Int): Int? {
            val end = text.indexOf('>', from)
            if (end < 0 || end - from > MAX_CLOSE_TAG_TAIL) return null
            return end + 1
        }

        /** 解析开启标签：`name` + 若干 `attr="value"` + 可选 `/` + `>`。 */
        fun parseOpenTag(text: CharSequence, from: Int): TagParse {
            var position = from
            while (position < text.length && text[position].isWhitespace()) position++
            val nameStart = position
            while (position < text.length && (text[position].isLetterOrDigit() || text[position] == '_')) position++
            if (position == nameStart) {
                // 还没收到标签名字符：可能是数据不足，也可能就是坏结构
                return if (position >= text.length) TagParse.Incomplete else TagParse.Malformed
            }
            val name = text.subSequence(nameStart, position).toString()
            val attributes = mutableMapOf<String, String>()
            var selfClosing = false
            while (true) {
                while (position < text.length && text[position].isWhitespace()) position++
                if (position >= text.length) return TagParse.Incomplete
                when (val char = text[position]) {
                    '>' -> return TagParse.Ok(OpenTag(name, attributes, selfClosing, position + 1))
                    '/' -> {
                        selfClosing = true
                        position++
                    }
                    else -> {
                        if (!char.isLetter()) return TagParse.Malformed
                        val attrStart = position
                        while (position < text.length && (text[position].isLetterOrDigit() ||
                                text[position] == '_' || text[position] == '-')
                        ) {
                            position++
                        }
                        val attrName = text.subSequence(attrStart, position).toString()
                        var probe = position
                        while (probe < text.length && text[probe].isWhitespace()) probe++
                        if (probe >= text.length) return TagParse.Incomplete
                        if (text[probe] != '=') {
                            attributes[attrName] = ""
                            position = probe
                            continue
                        }
                        position = probe + 1
                        while (position < text.length && text[position].isWhitespace()) position++
                        if (position >= text.length) return TagParse.Incomplete
                        val quote = text[position]
                        if (quote != '"' && quote != '\'') return TagParse.Malformed
                        position++
                        val valueStart = position
                        while (position < text.length && text[position] != quote) position++
                        if (position >= text.length) return TagParse.Incomplete
                        attributes[attrName] = text.subSequence(valueStart, position).toString()
                        position++
                    }
                }
            }
        }

        /** 找 `tag` 的闭合标签；未出现（数据不足）时返回 `null`。 */
        fun findCloseTag(text: CharSequence, tag: String, from: Int): TagEvent.CloseTag? {
            var position = maxOf(from, 0)
            while (true) {
                val marker = findMarkerFrom(text, position) ?: return null
                position = marker.end
                val isClose = marker.start >= 2 && text[marker.start - 1] == '/' && text[marker.start - 2] == '<'
                if (!isClose) continue
                var cursor = position
                while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                val nameStart = cursor
                while (cursor < text.length && (text[cursor].isLetterOrDigit() || text[cursor] == '_')) cursor++
                val name = text.subSequence(nameStart, cursor).toString()
                while (cursor < text.length && text[cursor].isWhitespace()) cursor++
                if (cursor >= text.length) return null
                if (text[cursor] == '>' && name == tag) return TagEvent.CloseTag(marker.start - 2, cursor + 1)
            }
        }
    }
}

/**
 * 去掉整段文本里的信封，只保留正文；没有信封时原样返回。用于历史回放清洗。
 *
 * 与流式路径共用同一套解析原语，因此「能识别的形态」与运行时一致；区别只在于这里**只剔除、
 * 不恢复**，并且不做任何 IO。
 */
internal fun String.withoutDsmlEnvelopes(): String {
    if (indexOf(DSML_SUBSTRING) < 0) return this
    val parser = DsmlEnvelopeParser()
    val streamed = parser.append(this)
    val flushed = parser.finish()
    // 一个信封都没识别出来时原样返回：解析器在正文态会扣留「可能是定界符前缀」的尾部，
    // 只有确实命中了信封，那次扣留才是有意义的截断
    if (streamed.none { it is DsmlSegment.ToolEnvelope } && flushed.none { it is DsmlSegment.ToolEnvelope }) return this
    return buildString {
        streamed.forEach { if (it is DsmlSegment.Text) append(it.text) }
        flushed.forEach { if (it is DsmlSegment.Text) append(it.text) }
    }
}
