package io.github.hatoyuze.deepseek.protocol.api.impl

import io.github.hatoyuze.deepseek.protocol.api.ChatChunk
import io.github.hatoyuze.deepseek.protocol.api.ChatConfig
import io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.ResponseFormat
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.protocol.api.entity.ToolChoice
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.toolcall.DEEPSEEK_WEB_SEARCH_TOOL
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCallType
import io.github.hatoyuze.deepseek.toolcall.pipeline.PipelineException
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class DeepseekResponsesApiImpl(apiKey: String) :
    DeepseekApiBase(
        apiKey = apiKey,
        baseUrl = "https://api.deepseek.com",
    ) {

    @OptIn(ExperimentalDeepseekApi::class)
    override suspend fun completions(
        messages: List<Message>,
        model: Model,
        config: ChatConfig,
        tools: List<ToolDefinition>?,
    ): Flow<ChatChunk> {

        @Serializable
        data class Reasoning(val effort: String? = null)

        @Serializable
        data class Format(val type: String)

        @Serializable
        data class Text(val format: Format? = null)

        @Serializable
        data class Tool(
            val type: String,
            val name: String? = null,
            val description: String? = null,
            val parameters: JsonElement? = null,
            val strict: Boolean = false,
        )

        @Serializable
        data class Request(
            val model: String,
            val instructions: String? = null,
            val input: JsonElement,
            @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stream: Boolean = true,
            val temperature: Double? = null,
            @SerialName("top_p") val topP: Double? = null,
            @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
            val reasoning: Reasoning? = null,
            val text: Text? = null,
            val tools: List<Tool>? = null,
            @SerialName("tool_choice") val toolChoice: JsonElement? = null,
            @SerialName("top_logprobs") val topLogprobs: Int? = null,
        )

        // ── 事件 DTO（严格按官方事件表）──

        @Serializable
        data class EventUsage(
            @SerialName("input_tokens") val inputTokens: Long? = null,
            @SerialName("output_tokens") val outputTokens: Long? = null,
            @SerialName("total_tokens") val totalTokens: Long? = null,
        )

        @Serializable
        data class EventError(val code: String? = null, val message: String? = null)

        @Serializable
        data class IncompleteDetails(val reason: String? = null)

        @Serializable
        data class EventResponse(
            val id: String? = null,
            val status: String? = null,
            val error: EventError? = null,
            @SerialName("incomplete_details") val incompleteDetails: IncompleteDetails? = null,
            val usage: EventUsage? = null,
        )

        @Serializable
        data class EventItem(
            val id: String? = null,
            val type: String? = null,
            @SerialName("call_id") val callId: String? = null,
            val name: String? = null,
            val action: JsonElement? = null,
        )

        @Serializable
        data class Event(
            val type: String,
            @SerialName("sequence_number") val sequenceNumber: Long? = null,
            val response: EventResponse? = null,
            val item: EventItem? = null,
            @SerialName("item_id") val itemId: String? = null,
            @SerialName("output_index") val outputIndex: Int? = null,
            @SerialName("content_index") val contentIndex: Int? = null,
            val delta: String? = null,
            val text: String? = null,
            val name: String? = null,
            val arguments: String? = null,
            val input: String? = null,
        )

        // ── 请求装配 ──

        val (instructions, inputMessages) = extractResponsesInstructions(messages)

        // 未配置或 Enabled 时保持模型默认思考（不发送 reasoning）；
        // Disabled → "none"；WithEffort → 官方 effort 值（high / max）。
        val reasoning = when (val mode = config.thinkingMode) {
            null, is ThinkingMode.Enabled -> null
            is ThinkingMode.Disabled -> Reasoning(effort = "none")
            is ThinkingMode.WithEffort -> Reasoning(effort = mode.effort.name.lowercase())
        }

        // TEXT / null 不发送（默认 text）；JSON_OBJECT → {"type":"json_object"}
        val text = when (config.responseFormat) {
            ResponseFormat.JSON_OBJECT -> Text(format = Format(type = "json_object"))
            null, ResponseFormat.TEXT -> null
        }

        val requestTools = tools
            ?.filter { it.name != DEEPSEEK_WEB_SEARCH_TOOL }
            ?.map {
                Tool(
                    type = "function",
                    name = it.name,
                    description = it.description,
                    parameters = it.parameters,
                    strict = it.strict,
                )
            }
            .orEmpty()
            .toMutableList()
        if (config.enableWebSearch) {
            requestTools.add(Tool(type = "web_search"))
        }

        val request = Request(
            model = model.id,
            instructions = instructions,
            input = inputMessages.toResponsesInputItems(),
            temperature = config.temperature,
            topP = config.topP,
            maxOutputTokens = config.maxTokens,
            reasoning = reasoning,
            text = text,
            tools = requestTools.ifEmpty { null },
            toolChoice = config.toolChoice?.toResponsesToolChoice(),
            topLogprobs = config.topLogprobs,
        )

        // ── 事件处理 ──

        // item_id -> function_call / custom_tool_call 的 name 与 call_id
        val itemNames = mutableMapOf<String, String>()
        val itemCallIds = mutableMapOf<String, String>()
        // item_id -> web_search_call 的 action JSON
        val itemActions = mutableMapOf<String, JsonElement>()
        // item_id -> 流式参数/输入分片
        val pendingArguments = mutableMapOf<String, StringBuilder>()

        return net.sseStream<Event>("/responses", json.encodeToString(request), json)
            .transform { event ->
                when (event.type) {
                    // 仅状态确认，无输出
                    "response.created", "response.in_progress" -> Unit

                    // 记录输出 item 的元信息
                    "response.output_item.added", "response.output_item.done" -> {
                        val item = event.item ?: return@transform
                        val id = item.id ?: return@transform
                        when (item.type) {
                            "function_call", "custom_tool_call" -> {
                                item.name?.let { itemNames[id] = it }
                                item.callId?.let { itemCallIds[id] = it }
                            }
                            "web_search_call" -> {
                                item.action?.let { itemActions[id] = it }
                            }
                        }
                    }

                    // 无输出
                    "response.content_part.added", "response.content_part.done",
                    "response.reasoning_text.done", "response.output_text.done" -> Unit

                    "response.reasoning_text.delta" -> {
                        val delta = event.delta ?: return@transform
                        val chunk = ChatChunk.ContentDelta(content = "", reasoningContent = delta)
                        emit(chunk)
                    }

                    "response.output_text.delta" -> {
                        val delta = event.delta ?: return@transform
                        val chunk = ChatChunk.ContentDelta(content = delta)
                        emit(chunk)
                    }

                    "response.function_call_arguments.delta", "response.custom_tool_call_input.delta" -> {
                        val id = event.itemId ?: return@transform
                        val delta = event.delta ?: return@transform
                        pendingArguments.getOrPut(id) { StringBuilder() }.append(delta)
                    }

                    "response.function_call_arguments.done", "response.custom_tool_call_input.done" -> {
                        val id = event.itemId ?: return@transform
                        val arguments = event.arguments
                            ?: event.input
                            ?: pendingArguments.remove(id)?.toString().orEmpty()
                        val name = itemNames[id]
                            ?: if (event.type == "response.custom_tool_call_input.done") "apply_patch" else ""
                        val toolCallId = itemCallIds[id] ?: id
                        val chunk = ChatChunk.ToolCallRequest(
                            ToolCall(id = toolCallId, name = name, arguments = arguments),
                        )
                        emit(chunk)
                    }

                    // 服务端联网搜索：仅 completed 对外暴露动作
                    "response.web_search_call.in_progress", "response.web_search_call.searching" -> Unit

                    "response.web_search_call.completed" -> {
                        val id = event.itemId ?: return@transform
                        val action = itemActions[id] ?: JsonNull
                        val chunk = ChatChunk.ToolCallRequest(
                            ToolCall(
                                id = id,
                                name = DEEPSEEK_WEB_SEARCH_TOOL,
                                arguments = action.toString(),
                                type = ToolCallType.WEB_SEARCH_CALL,
                            ),
                        )
                        emit(chunk)
                    }

                    "response.completed" -> {
                        val usage = event.response?.usage
                        val chunk = ChatChunk.Done(
                            promptTokens = usage?.inputTokens ?: 0L,
                            completionTokens = usage?.outputTokens ?: 0L,
                            totalTokens = usage?.totalTokens ?: 0L,
                            finishReason = "stop",
                        )
                        emit(chunk)
                    }

                    "response.incomplete" -> {
                        val usage = event.response?.usage
                        val reason = event.response?.incompleteDetails?.reason
                        val chunk = ChatChunk.Done(
                            promptTokens = usage?.inputTokens ?: 0L,
                            completionTokens = usage?.outputTokens ?: 0L,
                            totalTokens = usage?.totalTokens ?: 0L,
                            // max_output_tokens 等价于 Chat Completions 的 length
                            finishReason = if (reason == "max_output_tokens") "length" else reason,
                        )
                        emit(chunk)
                    }

                    "response.failed" -> {
                        val error = event.response?.error
                        val code = error?.code?.let { " (code=$it)" } ?: ""
                        val message = error?.message?.let { ": $it" } ?: ""
                        throw PipelineException("Responses API request failed$code$message")
                    }

                    // 未知事件忽略
                    else -> Unit
                }
            }
    }
}

/**
 * 从消息历史中提取 Responses API 的 `instructions`。
 *
 * 取首条非空 System 消息作为 `instructions`（对应 API 的第一条 system 消息），
 * 并从 input 列表中排除；其余 System 消息保留为普通 message item。
 */
internal fun extractResponsesInstructions(messages: List<Message>): Pair<String?, List<Message>> {
    val index = messages.indexOfFirst { it.role == Role.System && !it.content.isNullOrEmpty() }
    if (index < 0) return null to messages
    return messages[index].content to messages.filterIndexed { i, _ -> i != index }
}

/**
 * 将内部消息历史转换为 Responses API 的 input item 列表（`JsonArray`）。
 *
 * item 类型严格限制在官方白名单：
 * `message` / `function_call` / `function_call_output` / `reasoning` / `web_search_call`。
 */
@OptIn(ExperimentalDeepseekApi::class)
internal fun List<Message>.toResponsesInputItems(): JsonElement {
    var reasoningCounter = 0
    return buildJsonArray {
        for (msg in this@toResponsesInputItems) {
            when (msg.role) {
                Role.System, Role.User -> add(buildJsonObject {
                    put("type", "message")
                    put("role", msg.role.name.lowercase())
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", msg.content ?: "")
                        })
                    })
                })

                Role.Assistance -> {
                    if (!msg.reasoningContent.isNullOrEmpty()) {
                        reasoningCounter++
                        add(buildJsonObject {
                            put("type", "reasoning")
                            put("id", "rs_$reasoningCounter")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "reasoning_text")
                                    put("text", msg.reasoningContent)
                                })
                            })
                        })
                    }
                    if (!msg.content.isNullOrEmpty()) {
                        add(buildJsonObject {
                            put("type", "message")
                            put("role", "assistant")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "output_text")
                                    put("text", msg.content)
                                })
                            })
                        })
                    }
                    msg.toolCalls?.forEach { tc ->
                        when (tc.type) {
                            ToolCallType.FUNCTION -> add(buildJsonObject {
                                put("type", "function_call")
                                put("call_id", tc.id)
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                            ToolCallType.WEB_SEARCH_CALL -> add(buildJsonObject {
                                put("type", "web_search_call")
                                put("id", tc.id)
                                put(
                                    "action",
                                    runCatching { json.parseToJsonElement(tc.arguments) }
                                        .getOrElse { JsonNull },
                                )
                            })
                            // 其他类型忽略
                        }
                    }
                }

                Role.Tool -> {
                    // 服务端已执行联网搜索，魔法名工具消息无需回传（不要求配对）
                    if (msg.name == DEEPSEEK_WEB_SEARCH_TOOL) continue
                    add(buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", msg.toolCallId ?: "")
                        put("output", msg.content ?: "")
                    })
                }
            }
        }
    }
}

internal fun ToolChoice?.toResponsesToolChoice(): JsonElement? = when (this) {
    null -> null
    ToolChoice.None -> JsonPrimitive("none")
    ToolChoice.Auto -> JsonPrimitive("auto")
    ToolChoice.Required -> JsonPrimitive("required")
    is ToolChoice.Named -> if (name == DEEPSEEK_WEB_SEARCH_TOOL) {
        buildJsonObject { put("type", "web_search") }
    } else {
        buildJsonObject {
            put("type", "function")
            put("name", name)
        }
    }
}
