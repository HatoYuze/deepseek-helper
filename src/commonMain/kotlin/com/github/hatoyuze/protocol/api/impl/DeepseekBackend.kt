package com.github.hatoyuze.protocol.api.impl

import com.github.hatoyuze.protocol.api.ChatChunk
import com.github.hatoyuze.protocol.api.ChatConfig
import com.github.hatoyuze.protocol.api.entity.Message
import com.github.hatoyuze.protocol.data.Model
import com.github.hatoyuze.protocol.data.UserBalance
import com.github.hatoyuze.protocol.net.HttpHookRegistry
import com.github.hatoyuze.protocol.net.Network
import com.github.hatoyuze.protocol.net.collectHeaders
import com.github.hatoyuze.tool.registry.ToolDefinition
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.client.plugins.sse.SSEClientException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


internal interface DeepseekApiBackend {
    suspend fun models(): List<Model>

    suspend fun userBalance(): UserBalance

    suspend fun completions(
        messages: List<Message>,
        model: Model,
        config: ChatConfig,
        tools: List<ToolDefinition>? = null,
    ): Flow<ChatChunk>
}


internal abstract class DeepseekApiBase(
    apiKey: String,
    baseUrl: String,
) : DeepseekApiBackend {

    protected val net = Network(baseUrl, apiKey)

    override suspend fun models(): List<Model> {
        @Serializable
        data class Response(val data: List<Model>)
        return net.call<Response>("/models").data
    }

    override suspend fun userBalance(): UserBalance = net.call("/user/balance")
}

internal object DeepseekSseErrors {
    var lastSseError: String? = null
}


internal inline fun <reified T> Network.sseStream(
    action: String,
    bodyJson: String,
    json: Json,
    method: HttpMethod = HttpMethod.Post,
): Flow<T> {
    val hostUrl = this.host
    val methodStr = method.value.uppercase()
    val fullUrl = hostUrl + action
    return callbackFlow {
        try {
            executeSSE(action, requestBody = bodyJson, {
                this.method = method
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }, onResponse = { response ->
                checkHttpStatus(response)
                // onResponse hook is fired by executeSSE after the stream ends (with accumulated SSE body)
            }) { event ->
                val data = event.data ?: return@executeSSE
                if (data == "[DONE]") {
                    close()
                    return@executeSSE
                }
                try {
                    val decoded = json.decodeFromString<T>(data)
                    send(decoded)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Store for debugging — tool call deltas with missing fields end up here
                    DeepseekSseErrors.lastSseError = "SSE: ${e.message} — ${data.take(200)}"
                }
            }
        } catch (e: SSEClientException) {
            // Ktor SSE 在 onResponse 之前就会对非 200 抛异常。
            // 通过一次非流式请求获取完整错误信息，复用 checkHttpStatus 处理。
            val self = this@sseStream
            val response = self.execute(action, method = methodStr, requestBody = bodyJson) {
                this.method = method
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
            val body = try { response.bodyAsText() } catch (_: Exception) { null }
            HttpHookRegistry.forEach { hook ->
                hook.onResponse(methodStr, fullUrl, response.status.value, collectHeaders(response.headers), body)
            }
            checkHttpStatus(response)
        }
        // 服务端正常结束流时（如 Responses API 以 response.completed /
        // response.incomplete / response.failed 事件结束，且没有 data: [DONE]），
        // 显式关闭回调流，否则 awaitClose 会永久挂起、外层流永远不会结束。
        close()
        awaitClose()
    }
}

internal suspend inline fun <reified T> Network.call(
    action: String,
    method: HttpMethod = HttpMethod.Get,
): T {
    val methodStr = method.value.uppercase()
    val fullUrl = this.host + action
    val response = execute(action, method = methodStr) {
        this.method = method
    }

    checkHttpStatus(response)

    val body = response.bodyAsText()
    HttpHookRegistry.forEach { hook ->
        hook.onResponse(methodStr, fullUrl, response.status.value, collectHeaders(response.headers), body)
    }
    if (body.isEmpty() || body == "null") {
        throw IllegalStateException("Response returns nothing")
    }

    return json.decodeFromString(body)
}

internal val json = Json {
    ignoreUnknownKeys = true
}
