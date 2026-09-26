package io.github.hatoyuze.deepseek.protocol.net

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.plugins.sse.sse
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException

/**
 * 一次 multipart 上传的文件分片。
 *
 * 只承载**字节**，不承载路径：文件系统读取由 [io.github.hatoyuze.deepseek.protocol.api.entity.FileSource]
 * 负责，网络层保持与平台无关。
 */
internal class MultipartFilePart(
    val name: String,
    val bytes: ByteArray,
    val filename: String?,
    val contentType: ContentType?,
)

internal class Network(
    baseUrl: String,
    private val apiKey: String,
    private val pool: DeepseekHttpClientPool = DeepseekHttpClientPool.Global,
) {
    /** 归一化后的 API 服务地址（无尾部 `/`），同时作为连接池的缓存 key */
    internal val host: String = normalizeBaseUrl(baseUrl)

    private suspend fun net(): HttpClient = pool.client(host)

    /**
     * 触发请求 hook。
     *
     * hook 面向日志/调试，第三方接入后收到的一切都会进入应用日志，因此这里先做**脱敏**：
     * - data URL（`data:image/...;base64,...`）整体替换为 `data:<mime>;base64,<N bytes,redacted>`
     *   —— 内联图片可达 32 MiB，属于用户私有内容，绝不能落进日志
     * - 超长且没有任何空白的载荷（大段 base64 / opaque token）截断为 `<N chars,redacted>`
     * - 整体超过 [HOOK_BODY_LIMIT] 字符的请求体截断（日志膨胀保护）
     *
     * 注意：这是**跨全部请求路径**的统一策略（chat / responses / FIM 的 JSON 请求体、
     * Files 上传的 multipart 摘要），不是只针对新加的图片路径。
     *
     * 规则之外的内容按原样进入日志，**其中包括模型自己写下的文本**：0.4.1 起带 `tools` 的请求会
     * 回传历史里的 `reasoning_content`（官方强制要求），思考内容因此也会出现在 hook 收到的请求体里。
     * 思考内容可能引用工具返回的数据，而上面的替换只看文本形状（含不含空白、是否超长），不看字段
     * 语义，所以把 hook 接到长期日志或第三方可观测平台时，请自行评估留存策略。
     */
    private fun fireRequestHooks(method: String, fullUrl: String, requestBody: String?) {
        val safeBody = requestBody?.let(::redactForHook)
        HttpHookRegistry.forEach { hook ->
            hook.onRequest(method, fullUrl, emptyMap(), safeBody)
        }
    }

    suspend fun execute(
        url: String,
        method: String = "GET",
        requestBody: String? = null,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse {
        val fullUrl = this.host + url
        fireRequestHooks(method, fullUrl, requestBody)
        return net().request {
            block()
            url(fullUrl)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
    }

    /**
     * 发起一个 multipart/form-data 请求（Files API 上传）。
     *
     * 与 [execute] 一致地触发请求/响应 hook，但请求体 hook 只收到 [bodySummary] —— 文件内容
     * 可能上百 MiB，既不该进日志也不该被复制成字符串。
     *
     * 取消随调用方协程传播（Ktor 会中止上传）。
     *
     * @param bodySummary 面向 hook 的请求体摘要（不得包含文件内容或本地路径）
     */
    suspend fun executeMultipart(
        url: String,
        method: String,
        formFields: List<Pair<String, String>>,
        files: List<MultipartFilePart>,
        bodySummary: String,
    ): HttpResponse {
        val fullUrl = this.host + url
        fireRequestHooks(method, fullUrl, bodySummary)
        val response = net().request {
            this.method = HttpMethod.parse(method)
            url(fullUrl)
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        formFields.forEach { (name, value) -> append(name, value) }
                        files.forEach { file ->
                            // ktor 会给每个分片自己拼上 `Content-Disposition: form-data; name=<key>`
                            // （formDsl 的 append(key, value, headers) 实现），并把同名字段用 "; " 合并。
                            // 因此这里**只**提供 filename 参数：再传一个完整 disposition 会得到
                            // `form-data; name=file; file; name=file; filename=...`（多出裸参数 + name 重复），
                            // 严格解析器（Go mime/multipart、busboy）会拒绝这种分片。
                            // 文件名已按白名单校验（见 requireValidFilename），不含需要转义的字符。
                            val partHeaders = Headers.build {
                                file.filename?.let {
                                    append(
                                        HttpHeaders.ContentDisposition,
                                        ContentDisposition.Parameters.FileName + "=" + it,
                                    )
                                }
                                file.contentType?.let { type -> append(HttpHeaders.ContentType, type.toString()) }
                            }
                            append(key = file.name, value = file.bytes, headers = partHeaders)
                        }
                    },
                ),
            )
        }
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            // 取消必须继续传播：吞掉它会让协程在已取消的状态下继续跑完整个流程
            throw e
        } catch (_: Exception) {
            // 读不出响应体只影响 hook 的内容（错误码仍由 checkHttpStatus 判定），不值得让调用失败
            null
        }
        HttpHookRegistry.forEach { hook ->
            hook.onResponse(method, fullUrl, response.status.value, collectHeaders(response.headers), body)
        }
        return response
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
        fireRequestHooks(method, fullUrl, requestBody)
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
 * 面向 hook 的请求体长度上限（字符）：超过即截断，避免日志被整段请求体淹没。
 */
private const val HOOK_BODY_LIMIT: Int = 4096

/**
 * 单段「无空白长载荷」的截断阈值（字符）：大段 base64 与不透明 token 都在此列。
 *
 * 阈值按 OpenAI 兼容接口里最长的合法小标量（URL ≤ 8192 字符）之下取值，因此正常请求
 * 里的 URL / id / 文本不会被误伤，而 base64 图片一定超过它。
 */
private const val HOOK_OPAQUE_SEGMENT_LIMIT: Int = 2048

/** data URL 识别：`data:<mime>[;base64],<payload>`（payload 也允许百分号编码） */
private val DATA_URL_PATTERN =
    Regex("""data:[A-Za-z0-9.+-]*/?[A-Za-z0-9.+-]*(;base64)?,[A-Za-z0-9+/=%._~-]+""", RegexOption.IGNORE_CASE)

/** 分片扫描时视为「结构」的字符：引号、逗号与各种括号 */
private const val STRUCTURAL_CHARS = "\"',[]{}()"

/** 允许原样透传的超长段前缀：文档允许的外部 URL（≤ 8192）与 data URL */
private val PRESERVED_SEGMENT_PREFIXES = listOf("http://", "https://", "data:")
/**
 * 把请求体脱敏成「可安全写进日志」的字符串（见 [Network.fireRequestHooks]）。
 *
 * 该函数**永不删除结构**：只替换大块载荷内容，因此 hook 依然能看到请求的形状
 * （model / messages / 字段名），只是看不到用户的图片字节。
 */
internal fun redactForHook(body: String): String {
    // 单趟扫描代替正则：`[^\s...]{N,}` 式的正则在每个下标都要重试最小长度匹配，
    // 最坏代价约为 长度 × 段长 / 2（实测 200 KB 的 2 KB 段要 800ms+），而这条路径在
    // **每次请求**前同步执行，属于热路径，必须 O(n)。
    val redacted = redactLongSegments(redactDataUrls(body))
    if (redacted.length <= HOOK_BODY_LIMIT) return redacted

    // 在阈值内回退到最近的空白，避免把 JSON 结构从中间切断得太难看
    val cut = redacted.lastIndexOf(' ', HOOK_BODY_LIMIT).takeIf { it > HOOK_BODY_LIMIT / 2 } ?: HOOK_BODY_LIMIT
    return redacted.take(cut) + "…<truncated ${redacted.length - cut} chars>"
}

/**
 * 把内联 data URL 的载荷替换为占位符。
 *
 * 只接受「整段以 `data:` 开头」的匹配（前一字符不是标识符字符）：否则 `data:` 会匹配到
 * URL 路径或 base64 正文的中间，把合法内容切碎。占位符保留原始前缀
 * （mime 与是否 `;base64`），避免日志里出现「本来不是 base64 却写着 ;base64」的假信息。
 */
private fun redactDataUrls(body: String): String {
    val builder = StringBuilder(body.length)
    var cursor = 0
    for (match in DATA_URL_PATTERN.findAll(body)) {
        val start = match.range.first
        val precededByIdentifier = start > 0 && isSegmentChar(body[start - 1])
        if (precededByIdentifier) continue
        builder.append(body, cursor, start)
        builder.append(match.value.substringBefore(','))
        builder.append(",<redacted ").append(match.value.length).append(" chars>")
        cursor = match.range.last + 1
    }
    builder.append(body, cursor, body.length)
    return builder.toString()
}

/** 是否属于「不含空白的连续段」的字符（与 [redactLongSegments] 的分段规则一致） */
private fun isSegmentChar(char: Char): Boolean = !char.isWhitespace() && char !in STRUCTURAL_CHARS

/**
 * 把不含空白的超长段替换为占位符（单趟扫描，O(n)）。
 *
 * 保留 `http(s)://` 与 `data:` 开头的段：前者是文档允许的（≤ 8192 字符），
 * 后者已由 [DATA_URL_PATTERN] 处理过 —— 对合法请求体不做无谓改写。
 */
private fun redactLongSegments(body: String): String {
    val builder = StringBuilder(body.length)
    var index = 0
    while (index < body.length) {
        val char = body[index]
        if (char.isWhitespace() || char in STRUCTURAL_CHARS) {
            builder.append(char)
            index++
            continue
        }
        var end = index
        while (end < body.length && !body[end].isWhitespace() && body[end] !in STRUCTURAL_CHARS) {
            end++
        }
        val segment = body.substring(index, end)
        val preserved = segment.length <= HOOK_OPAQUE_SEGMENT_LIMIT ||
            PRESERVED_SEGMENT_PREFIXES.any { segment.startsWith(it, ignoreCase = true) }
        if (preserved) builder.append(segment) else builder.append("<redacted ${segment.length} chars>")
        index = end
    }
    return builder.toString()
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
