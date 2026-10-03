package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.toolcall.DEEPSEEK_WEB_SEARCH_TOOL
import io.github.hatoyuze.deepseek.toolcall.Logger
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.protocol.api.impl.sanitizedForLog
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import kotlin.random.Random

private val inlineToolCallLogger = Logger("InlineToolCallRecovery")

/**
 * 一条模型响应里的内联信封过滤器：把 streaming 的 content / reasoning 增量过一遍，
 * 输出「可展示文本」与「可执行的工具调用」。
 *
 * 策略与理由（官方文档只承认结构化的 `tool_calls`，信封属于上游内部表示的泄漏）：
 *
 * - **正文**：信封能完整解析、工具名在本次请求的 `tools` 里、参数是合法 JSON ⇒ 恢复成工具调用
 *   并照常进入 `ToolCallHost` 管道执行；其余情况一律剔除 + 记日志，**绝不把信封当正文**。
 * - **思考内容**：只剔除、**绝不执行**。thinking 是模型的内心草稿，把草稿变成副作用是越界行为。
 * - 魔法工具 `_deepseek__web_search` 由服务端执行，恢复出来没有语义，因此也只剔除。
 *
 * 线程模型：实例非线程安全，一条模型响应一个实例，只在收集该响应的协程内使用（与
 * [DsmlEnvelopeParser] 相同）。
 */
internal class InlineToolCallRecovery(
    /**
     * 本次请求**允许**出现的工具名集合：既受注册表约束，也受调用方声明的工具策略约束
     * （`ChatConfig.toolChoice` 为 `None` / `Named(x)` 时，不在白名单里的调用一律不恢复）。
     */
    private val allowedTools: Set<String>,
    /**
     * 不可执行的原因（写进日志）：`toolHost` 为空、或配置要求只剔除（[InlineToolCallPolicy.STRIP]）。
     * `null` 表示允许执行。
     */
    private val executionDisabledReason: String? = null,
) {

    private val contentParser = DsmlEnvelopeParser()
    private val thinkingParser = DsmlEnvelopeParser()

    /** 已恢复成工具调用的信封数。 */
    var recoveredEnvelopes: Int = 0
        private set

    /** 被剔除（未执行）的信封数。 */
    var droppedEnvelopes: Int = 0
        private set

    /** 出现在 reasoning 通道、被剔除的信封数。 */
    var thinkingEnvelopes: Int = 0
        private set

    /** 被丢弃（未展示）的字符数：fail-closed 的代价要让调用方看得见，否则「被吞掉的长回答」与
     *  「简短回答」在输出上无法区分。 */
    val droppedChars: Int get() = contentParser.droppedChars + thinkingParser.droppedChars

    /** 正文增量 → 可见文本 + 本轮新恢复的工具调用。 */
    fun acceptContent(delta: String): FilteredContent = filterContent(contentParser.append(delta))

    /** 流结束时的正文收尾：未闭合的信封在这里被剔除并记账。 */
    fun flushContent(): FilteredContent = filterContent(contentParser.finish())

    /** 思考增量 → 剔除信封后的可见思考文本。 */
    fun acceptThinking(delta: String): String = filterThinking(thinkingParser.append(delta))

    /** 流结束时的思考收尾。 */
    fun flushThinking(): String = filterThinking(thinkingParser.finish())

    private fun filterContent(segments: List<DsmlSegment>): FilteredContent {
        // 快路径：纯正文（无信封）时不做任何拼接，直接透传
        if (segments.size == 1 && segments[0] is DsmlSegment.Text) {
            return FilteredContent((segments[0] as DsmlSegment.Text).text, emptyList())
        }
        val visible = StringBuilder()
        val recovered = mutableListOf<ToolCall>()
        for (segment in segments) {
            when (segment) {
                is DsmlSegment.Text -> visible.append(segment.text)
                is DsmlSegment.ToolEnvelope -> {
                    val call = recover(segment)
                    if (call == null) {
                        droppedEnvelopes++
                        inlineToolCallLogger.error {
                            "丢弃内联工具调用信封：tool=${segment.toolName?.sanitizedForLog() ?: "-"} " +
                                "reason=${segment.reason ?: rejectReason(segment)}"
                        }
                    } else {
                        recoveredEnvelopes++
                        recovered += call
                        inlineToolCallLogger.info {
                            "恢复内联工具调用：tool=${call.name} args=${call.arguments.length}B " +
                                "（上游把工具调用写成了正文，见 README「上游工具调用语法泄漏」）"
                        }
                    }
                }
            }
        }
        return FilteredContent(visible.toString(), recovered)
    }

    private fun filterThinking(segments: List<DsmlSegment>): String {
        val visible = StringBuilder()
        for (segment in segments) {
            when (segment) {
                is DsmlSegment.Text -> visible.append(segment.text)
                is DsmlSegment.ToolEnvelope -> {
                    thinkingEnvelopes++
                    inlineToolCallLogger.error {
                        "思考通道里出现内联工具调用信封（只剔除、不执行）：" +
                            "tool=${segment.toolName?.sanitizedForLog() ?: "-"} " +
                            "reason=${segment.reason ?: "complete"}"
                    }
                }
            }
        }
        return visible.toString()
    }

    private fun recover(segment: DsmlSegment.ToolEnvelope): ToolCall? {
        val name = segment.toolName ?: return null
        val arguments = segment.argumentsJson ?: return null
        if (executionDisabledReason != null) return null
        if (name == DEEPSEEK_WEB_SEARCH_TOOL) return null
        if (name !in allowedTools) return null
        return ToolCall(id = newRecoveredCallId(), name = name, arguments = arguments)
    }

    private fun rejectReason(segment: DsmlSegment.ToolEnvelope): String = when {
        segment.toolName == null -> "missing-tool-name"
        segment.argumentsJson == null -> "unparseable-envelope"
        executionDisabledReason != null -> executionDisabledReason
        segment.toolName == DEEPSEEK_WEB_SEARCH_TOOL -> "server-side-web-search"
        segment.toolName !in allowedTools -> "tool-not-allowed"
        else -> "rejected"
    }
}

/** 一次过滤的产物：[visible] 是应当展示的正文，[recovered] 是恢复出来的工具调用。 */
internal data class FilteredContent(
    val visible: String,
    val recovered: List<ToolCall>,
)

/**
 * 恢复出来的调用没有服务端 id，这里自己造一个：随机十六进制后缀保证并发/跨会话不重名，
 * 前缀便于在日志与历史里识别来源（不共享任何可变状态，因此天然并发安全）。
 */
internal fun newRecoveredCallId(): String = "$RECOVERED_CALL_PREFIX${Random.nextLong(Long.MAX_VALUE).toString(16)}"

private const val RECOVERED_CALL_PREFIX: String = "dsml_"

/**
 * 诊断「历史里带着 tool 轮次，但这次请求不会发送 `tools`」这一已知的上游触发条件。
 *
 * 官方文档只承认结构化 `tool_calls`，而服务端在工具通道关闭时会把模型的原生工具调用语法
 * 直接当正文下发（[deepseek-ai/DeepSeek-V3#1678](https://github.com/deepseek-ai/DeepSeek-V3/issues/1678)
 * 有确定性复现）。库无法替调用方补出工具定义（没有 host 就没有 schema），因此这里只负责把
 * 破防点写进日志；响应侧的兜底见 [InlineToolCallRecovery]。
 *
 * @return `null` 表示不变量成立；否则返回可直接写进日志的描述
 */
internal fun replayToolChannelDiagnostic(history: List<Message>, tools: List<ToolDefinition>?): String? {
    if (!tools.isNullOrEmpty()) return null
    val toolTurns = history.count { it.role == Role.Tool || !it.toolCalls.isNullOrEmpty() }
    if (toolTurns == 0) return null
    return "请求不会发送 tools（未注册任何工具），但历史里有 $toolTurns 条 tool 轮次/结果；" +
        "服务端可能把模型的工具调用语法当正文回传。回放带工具的历史时请装配同样的 ToolCallHost。"
}

/**
 * 回放清洗：把历史里 assistant 正文中残留的信封剔除（只剔除、不恢复），防止上游泄漏被当作
 * 自己的历史反复喂回去（业界称为 agent history self-poison）。
 *
 * 只动 **assistant** 的 `content` 文本；`toolCalls[].arguments`、user / tool 消息一律不动——
 * 工具参数里合法出现该语法是允许的，误删会破坏真实数据。
 *
 * 无信封时返回原列表实例（零分配），因此可以每轮请求无脑调用。
 */
internal fun List<Message>.scrubbedForReplay(): List<Message> {
    if (none { it.role == Role.Assistant && it.content.hasDsmlMarker() }) return this
    var changed = false
    val result = map { message ->
        val scrubbed = if (message.role == Role.Assistant && message.content.hasDsmlMarker()) {
            message.withoutDsmlContent()
        } else {
            message
        }
        if (scrubbed !== message) changed = true
        scrubbed
    }
    // 命中字样但没有真正剔除（例如正文只是提到了 DSML）时，依旧返回原列表，避免无谓拷贝
    return if (changed) result else this
}

private fun MessageContent?.hasDsmlMarker(): Boolean = when (this) {
    null -> false
    is MessageContent.Text -> text.contains(DSML_SUBSTRING)
    is MessageContent.Parts -> parts.any { it is ContentPart.TextPart && it.text.contains(DSML_SUBSTRING) }
}

private fun Message.withoutDsmlContent(): Message = when (val body = content) {
    null -> this
    is MessageContent.Text -> {
        val scrubbed = body.text.withoutDsmlEnvelopes()
        if (scrubbed === body.text) this else copy(content = MessageContent.of(scrubbed))
    }
    is MessageContent.Parts -> {
        var changed = false
        val parts = body.parts.map { part ->
            if (part !is ContentPart.TextPart) {
                part
            } else {
                val scrubbed = part.text.withoutDsmlEnvelopes()
                if (scrubbed === part.text) {
                    part
                } else {
                    changed = true
                    ContentPart.TextPart(scrubbed)
                }
            }
        }
        if (changed) copy(content = MessageContent.of(parts)) else this
    }
}
