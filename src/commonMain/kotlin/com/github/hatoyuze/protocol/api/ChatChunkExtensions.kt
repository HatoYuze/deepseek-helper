@file:JvmName("ChatChunkFlow")

package com.github.hatoyuze.protocol.api

import com.github.hatoyuze.protocol.api.entity.Usage
import com.github.hatoyuze.tool.executor.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 流结束时返回的完整响应，包含所有累积内容。
 *
 * 由 [collectResponse] 返回：
 *
 * ```kotlin
 * val response = ds.chatStream("你好").collectResponse()
 * println(response.thinkingContent)  // 思考内容，可能为 null
 * println(response.content)          // 正常回复文本
 * println(response.usage.totalTokens) // token 用量
 * ```
 *
 * 多轮工具调用场景下，[usage] 为各轮请求用量的累加值。
 *
 * @property thinkingContent 累积的思考内容，模型未思考时为 `null`
 * @property content 累积的回复文本
 * @property toolCalls 本次对话中模型发起的工具调用记录
 * @property usage token 用量统计
 * @property finishReason 结束原因（如 `stop`、`length`、`tool_calls`）
 */
public data class ChatResponse(
    val thinkingContent: String?,
    val content: String,
    val toolCalls: List<ToolCall>,
    val usage: Usage,
    val finishReason: String? = null,
)

/**
 * 收集整个 [Flow]<[ChatChunk]> 直到 [ChatChunk.Done]，聚合为 [ChatResponse]。
 *
 * 内部实现：累积所有 [ChatChunk.ContentDelta] 的 content 与 reasoningContent，
 * 收集 [ChatChunk.ToolCallRequest] 与 [ChatChunk.Done] 的用量；若流未发射 Done，
 * [ChatResponse.usage] 回退为全 0。
 *
 * ```kotlin
 * // 仅获取最终结果
 * val response = ds.chatStream("天气怎么样？").collectResponse()
 * println(response.content)
 *
 * // 搭配分流扩展：实时打印 + 最终统计
 * val response = ds.chatStream("Hello")
 *     .onThinking { print("思考: $it") }
 *     .onContent { print(it) }
 *     .collectResponse()
 * println("消耗 ${response.usage.totalTokens} tokens")
 * ```
 *
 * @return 聚合后的完整响应
 *
 * @see onThinking
 * @see onContent
 * @see onToolCall
 */
public suspend fun Flow<ChatChunk>.collectResponse(): ChatResponse {
    val thinkingBuilder = StringBuilder()
    val contentBuilder = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    var usage: Usage? = null
    var finishReason: String? = null

    collect { chunk ->
        when (chunk) {
            is ChatChunk.ContentDelta -> {
                if (chunk.reasoningContent != null) {
                    thinkingBuilder.append(chunk.reasoningContent)
                }
                if (chunk.content.isNotEmpty()) {
                    contentBuilder.append(chunk.content)
                }
            }
            is ChatChunk.ToolCallRequest -> {
                toolCalls.add(chunk.call)
            }
            is ChatChunk.ToolResultData -> { /* tracked via onToolResult hook */ }
            is ChatChunk.Done -> {
                usage = Usage(
                    promptTokens = chunk.promptTokens,
                    completionTokens = chunk.completionTokens,
                    totalTokens = chunk.totalTokens,
                )
                finishReason = chunk.finishReason
            }
        }
    }

    return ChatResponse(
        thinkingContent = thinkingBuilder.toString().ifEmpty { null },
        content = contentBuilder.toString(),
        toolCalls = toolCalls.toList(),
        usage = usage ?: Usage(0, 0, 0),
        finishReason = finishReason,
    )
}

/**
 * 对每个 [ChatChunk.ContentDelta] 的思考增量执行 [action]，并返回原 Flow 继续传递。
 *
 * 仅当 [ChatChunk.ContentDelta.reasoningContent] 非空时回调，适合实时展示思考过程：
 *
 * ```kotlin
 * ds.chatStream("证明费马大定理")
 *     .onThinking { printThinking(it) }   // 逐段打印思考内容
 *     .onContent { print(it) }
 *     .collectResponse()
 * ```
 *
 * @param action 思考增量回调
 * @return 原 [Flow]，可继续收集或串联其他扩展
 */
public fun Flow<ChatChunk>.onThinking(action: suspend (String) -> Unit): Flow<ChatChunk> =
    map { chunk ->
        if (chunk is ChatChunk.ContentDelta && chunk.reasoningContent != null) {
            action(chunk.reasoningContent)
        }
        chunk
    }

/**
 * 对每个 [ChatChunk.ContentDelta] 的内容增量执行 [action]，并返回原 Flow 继续传递。
 *
 * 过滤空字符串后立即回调，适合实时逐字/逐段输出：
 *
 * ```kotlin
 * ds.chatStream("写一首诗")
 *     .onContent { print(it) }    // 实时逐段输出
 *     .collectResponse()
 * ```
 *
 * @param action 内容增量回调
 * @return 原 [Flow]，可继续收集或串联其他扩展
 */
public fun Flow<ChatChunk>.onContent(action: suspend (String) -> Unit): Flow<ChatChunk> =
    map { chunk ->
        if (chunk is ChatChunk.ContentDelta && chunk.content.isNotEmpty()) {
            action(chunk.content)
        }
        chunk
    }

/**
 * 对每个 [ChatChunk.ToolCallRequest] 执行 [action]，并返回原 Flow 继续传递。
 *
 * 工具调用请求到达时立即回调，可实时感知模型准备调用的工具：
 *
 * ```kotlin
 * ds.chatStream("查天气")
 *     .onToolCall { tc ->
 *         println("🔧 调用工具: ${tc.call.name}(${tc.call.arguments})")
 *     }
 *     .collectResponse()
 * ```
 *
 * @param action 工具调用请求回调
 * @return 原 [Flow]，可继续收集或串联其他扩展
 */
public fun Flow<ChatChunk>.onToolCall(
    action: suspend (ChatChunk.ToolCallRequest) -> Unit,
): Flow<ChatChunk> = map { chunk ->
    if (chunk is ChatChunk.ToolCallRequest) {
        action(chunk)
    }
    chunk
}

/**
 * 对每个 [ChatChunk.ToolResultData] 执行 [action]，并返回原 Flow 继续传递。
 *
 * 工具执行完成后立即回调，可实时展示工具执行结果或错误：
 *
 * ```kotlin
 * ds.chatStream("查天气")
 *     .onToolResult { tr ->
 *         println("${tr.functionName} → ${tr.content} (error=${tr.isError})")
 *     }
 *     .collectResponse()
 * ```
 *
 * @param action 工具执行结果回调
 * @return 原 [Flow]，可继续收集或串联其他扩展
 */
public fun Flow<ChatChunk>.onToolResult(
    action: suspend (ChatChunk.ToolResultData) -> Unit,
): Flow<ChatChunk> = map { chunk ->
    if (chunk is ChatChunk.ToolResultData) {
        action(chunk)
    }
    chunk
}
