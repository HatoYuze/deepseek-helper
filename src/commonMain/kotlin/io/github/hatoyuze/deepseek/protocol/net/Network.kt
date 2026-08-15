package io.github.hatoyuze.deepseek.protocol.net

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.plugins.sse.sse
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.sse.ServerSentEvent

internal class Network(
    baseUrl: String,
    private val apiKey: String,
    private val pool: DeepseekHttpClientPool = DeepseekHttpClientPool.Global,
) {
    /** 归一化后的 API 服务地址（无尾部 `/`），同时作为连接池的缓存 key */
    internal val host: String = normalizeBaseUrl(baseUrl)

    private suspend fun net(): HttpClient = pool.client(host)

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
                    if (!HttpHookRegistry.isEmpty()) {
                        HttpHookRegistry.forEach { hook -> hook.onSseEvent(data) }
                    }
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

/**
 * 归一化并校验 API base URL。
 *
 * 规则：
 * - 必须是非空白的绝对 `http(s)` 地址，且必须包含非空 host
 * - 不支持 userinfo（`https://user:pass@host`，可被用于伪造目标主机）、
 *   query（`?…`）与 fragment（`#…`）——当前按 `host + path` 拼接端点路径，
 *   这些成分会导致路由静默错位，因此直接 fail-fast
 * - 尾部 `/` 会被去除，避免与端点路径拼接出 `//` 双斜杠；路径前缀（如
 *   `https://host/v1`）保留
 * - 校验失败抛 [IllegalArgumentException]，在客户端创建时 fail-fast
 *   （[Network] 由后端实现在客户端构造期创建）
 */
internal fun normalizeBaseUrl(raw: String): String {
    require(raw.isNotBlank()) { "baseUrl must not be blank" }
    val schemeSep = raw.indexOf("://")
    require(schemeSep > 0) { "baseUrl must be an absolute http(s) URL, but was: $raw" }
    val scheme = raw.substring(0, schemeSep).lowercase()
    require(scheme == "http" || scheme == "https") {
        "baseUrl must be an absolute http(s) URL, but was: $raw"
    }
    // 在 authority 段（scheme 之后、第一个 / ? # 之前）手工扫描 userinfo / query / fragment：
    // Ktor 的 Url 模型不暴露这些成分，而它们会让按 host+path 拼接的路由静默错位。
    val authorityStart = schemeSep + 3
    val authorityEnd = raw.indexOfAny(charArrayOf('/', '?', '#'), startIndex = authorityStart)
        .let { if (it < 0) raw.length else it }
    val authority = raw.substring(authorityStart, authorityEnd)
    require(authority.isNotBlank()) { "baseUrl must include a host, but was: $raw" }
    require('@' !in authority) {
        // 不回显原始 URL：userinfo 段可能携带凭据（user:pass@），会随异常泄漏进日志
        "baseUrl must not contain userinfo (user:pass@)"
    }
    require(authorityEnd >= raw.length || raw[authorityEnd] != '?') {
        // 不回显原始 URL：query 段可能携带 token 等敏感参数
        "baseUrl must not contain a query string"
    }
    require(authorityEnd >= raw.length || raw[authorityEnd] != '#') {
        "baseUrl must not contain a fragment"
    }
    val url = try {
        Url(raw)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("baseUrl must be a valid absolute http(s) URL, but was: $raw", e)
    }
    val canonicalAuthority = buildString {
        append(url.host.lowercase())
        if (url.port != url.protocol.defaultPort) append(':').append(url.port)
    }
    val path = url.encodedPath.trimEnd('/')
    return "$scheme://$canonicalAuthority$path"
}
