package io.github.hatoyuze.deepseek.protocol.net

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.plugins.sse.sse
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.sse.ServerSentEvent

internal class Network(
    internal val host: String,
    private val apiKey: String,
) {
    private suspend fun net(): HttpClient = DeepseekHttpClientPool.client(host)

    suspend fun execute(
        url: String,
        method: String = "GET",
        requestBody: String? = null,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val fullUrl = this.host + url
        HttpHookRegistry.forEach { hook ->
            hook.onRequest(method, fullUrl, emptyMap(), requestBody)
        }
        return net().request {
            block()
            url(fullUrl)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    suspend fun executeSSE(
        url: String,
        requestBody: String? = null,
        block: HttpRequestBuilder.() -> Unit = {},
        onResponse: suspend (HttpResponse) -> Unit = {},
        onEvent: suspend (ServerSentEvent) -> Unit,
    ) {
        val fullUrl = this.host + url
        val method = "POST"
        HttpHookRegistry.forEach { hook ->
            hook.onRequest(method, fullUrl, emptyMap(), requestBody)
        }
        var respStatus = 0
        var respHeaders: Map<String, String> = emptyMap()
        net().sse(request = {
            block()
            url(fullUrl)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            // SSE 为长连接且响应无 Content-Length，禁用连接复用可避免
            // 共享客户端上复用未完全排空的连接导致下一次请求挂起
            header(HttpHeaders.Connection, "close")
        }) {
            respStatus = this.call.response.status.value
            respHeaders = collectHeaders(this.call.response.headers)
            onResponse(this.call.response)
            this.incoming.collect { event ->
                event.data?.let { data ->
                    HttpHookRegistry.forEach { hook -> hook.onSseEvent(data) }
                }
                onEvent(event)
            }
        }
        // Stream completed — fire response hook with accumulated SSE body
        HttpHookRegistry.forEach { hook ->
            hook.onResponse(method, fullUrl, respStatus, respHeaders, null)
        }
    }
}
