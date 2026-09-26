package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.FileOrder
import io.github.hatoyuze.deepseek.protocol.api.entity.FilePurpose
import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.UploadOptions
import io.github.hatoyuze.deepseek.protocol.api.entity.openFileSource
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientConfig
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientFactory
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files API 的 wire format 契约（`POST/GET/DELETE /files`）。
 *
 * 用 MockEngine 记录真实发出的请求：断言的是**逐字段的官方形状**
 * （`multipart/form-data` 的 `purpose` / `expires_after[anchor]` / `expires_after[seconds]`、
 * 游标分页 query、响应解析），而不是「调用了某个方法」。
 *
 * 注：Ktor 的 SSE 插件在 MockEngine 上不投递解析后的事件，但 Files API 全是普通 JSON 请求，
 * 因此这里可以直接断言端到端行为。
 */
class FilesApiTest {

    /** 一条被 MockEngine 记录的请求 */
    private class RecordedRequest(
        val method: String,
        val url: String,
        val contentType: String?,
        val body: String,
    )

    private fun recordingPool(
        requests: MutableList<RecordedRequest>,
        respondWith: (RecordedRequest) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" },
    ): DeepseekHttpClientPool = DeepseekHttpClientPool(
        factory = DeepseekHttpClientFactory { _: DeepseekHttpClientConfig ->
            HttpClient(
                MockEngine { request ->
                    val body = request.body
                    val recorded = RecordedRequest(
                        method = request.method.value,
                        url = request.url.toString(),
                        contentType = body.contentType?.toString(),
                        // multipart 的字节体会在这里被完整读出，用于断言文件名与文件内容真的进去了
                        body = body.readBodyAsString(),
                    )
                    requests.add(recorded)
                    val (status, payload) = respondWith(recorded)
                    respond(
                        content = payload,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        },
    )

    private fun client(pool: DeepseekHttpClientPool, baseUrl: String = "https://api.deepseek.com") =
        Deepseek("sk-test", baseUrl = baseUrl, sharingPool = pool)

    /**
     * 读出请求体字节。
     *
     * multipart 内容（[OutgoingContent.WriteChannelContent]）需要真的写入一个 channel 才会生成
     * 字节流 —— 这正是「断言文件名与文件内容确实进了请求体」的前提；无请求体的请求返回空串。
     */
    private suspend fun OutgoingContent.readBytesOrEmpty(): ByteArray = when (this) {
        is OutgoingContent.NoContent -> ByteArray(0)
        is OutgoingContent.ByteArrayContent -> bytes()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            writeTo(channel)
            channel.flushAndClose()
            channel.readRemaining().readByteArray()
        }

        is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readByteArray()
        else -> error("unexpected request body type: ${this::class}")
    }

    private suspend fun OutgoingContent.readBodyAsString(): String = when (this) {
        is OutgoingContent.NoContent -> ""
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel(autoFlush = true)
            writeTo(channel)
            channel.flushAndClose()
            channel.readRemaining().readByteArray().decodeToString()
        }

        is OutgoingContent.ReadChannelContent -> readFrom().readRemaining().readByteArray().decodeToString()
        else -> error("unexpected request body type: ${this::class}")
    }

    private val fileJson =
        """{"id":"file-api-1","object":"file","bytes":102400,"created_at":1700000000,""" +
            """"filename":"image.jpg","purpose":"user_data"}"""

    // ── 上传 ──

    @Test
    fun `upload posts multipart form data with purpose and file part`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { HttpStatusCode.OK to fileJson }

        val uploaded = client(pool).files().upload(
            FileSource.Bytes(byteArrayOf(1, 2, 3)),
            mimeType = "image/jpeg",
            filename = "cat.jpg",
        )

        assertEquals("file-api-1", uploaded.id)
        assertEquals(102400, uploaded.bytes)
        assertEquals("image.jpg", uploaded.filename)
        assertNull(uploaded.expiresAt, "未设置有效期时不应出现 expires_at")

        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.deepseek.com/files", request.url)
        assertTrue(
            request.contentType!!.startsWith("multipart/form-data; boundary="),
            "必须是 multipart/form-data：${request.contentType}",
        )
        // 表单字段
        assertTrue(request.body.contains("name=purpose"), "应有 purpose 字段：${request.body}")
        assertTrue(request.body.contains("user_data"), "purpose 必须是 user_data：${request.body}")
        // 文件分片：字段名 file + 文件名 + 内容类型
        // 逐字断言分片头：ktor 会自己拼 `form-data; name=<key>`，我们只补 filename
        // （传完整 disposition 会得到 `form-data; name=file; file; name=file; filename=...`，
        // 严格解析器会拒绝这种分片 —— 这条断言就是那次回归的护栏）
        assertTrue(
            request.body.contains("Content-Disposition: form-data; name=file; filename=cat.jpg"),
            "分片头必须恰好是 form-data; name=file; filename=...：${request.body}",
        )
        assertFalse(
            request.body.contains("name=file; file;"),
            "不得出现裸 `file;` 参数或重复的 name：${request.body}",
        )
        assertTrue(request.body.contains("Content-Type: image/jpeg"), "应带分片 Content-Type：${request.body}")
        assertTrue(request.body.contains("\u0001\u0002\u0003"), "应带文件原始字节：${request.body}")
    }

    @Test
    fun `upload without filename omits the filename parameter`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { HttpStatusCode.OK to fileJson }

        client(pool).files().upload(FileSource.Bytes(byteArrayOf(9)), mimeType = "image/png")

        val body = requests.single().body
        assertFalse(body.contains("filename="), "未指定文件名时不应伪造一个：$body")
        assertTrue(
            body.contains("Content-Disposition: form-data; name=file"),
            "文件字段仍应存在且头合法：$body",
        )
    }

    @Test
    fun `upload with expiry sends both dotted form fields`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) {
            HttpStatusCode.OK to
                """{"id":"file-api-2","object":"file","bytes":1,"created_at":1700000000,""" +
                """"filename":"a.png","purpose":"user_data","expires_at":1700003600}"""
        }

        val uploaded = client(pool).files().upload(
            FileSource.Bytes(byteArrayOf(1)),
            mimeType = "image/png",
            options = UploadOptions(expiresAfterSeconds = 3600),
        )

        assertEquals(1700003600, uploaded.expiresAt)
        val body = requests.single().body
        // 官方字段名是点号形式（不是嵌套对象）
        assertTrue(body.contains("expires_after[anchor]"), "应有 anchor 字段：$body")
        assertTrue(body.contains("created_at"), "anchor 必须是 created_at：$body")
        assertTrue(body.contains("expires_after[seconds]"), "应有 seconds 字段：$body")
        assertTrue(body.contains("3600"), "应带有效期秒数：$body")
    }

    @Test
    fun `upload without expiry omits expires_after fields`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { HttpStatusCode.OK to fileJson }

        client(pool).files().upload(FileSource.Bytes(byteArrayOf(1)), mimeType = "image/png")

        val body = requests.single().body
        assertFalse(body.contains("expires_after"), "不传有效期时不应出现 expires_after：$body")
    }

    // ── 查询 / 列表 / 删除 ──

    @Test
    fun `retrieve gets the file by id`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { HttpStatusCode.OK to fileJson }

        val file = client(pool).files().retrieve("file-api-1")

        assertEquals("file-api-1", file.id)
        assertEquals("GET", requests.single().method)
        assertEquals("https://api.deepseek.com/files/file-api-1", requests.single().url)
    }

    @Test
    fun `list sends cursor limit order and purpose`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) {
            HttpStatusCode.OK to
                """{"object":"list","data":[],"first_id":null,"last_id":null,"has_more":false}"""
        }

        val page = client(pool).files().list(after = "file-api-9", limit = 20, order = FileOrder.Desc)

        assertFalse(page.hasMore)
        val url = requests.single().url
        assertTrue(url.startsWith("https://api.deepseek.com/files?"), url)
        assertTrue(url.contains("after=file-api-9"), url)
        assertTrue(url.contains("limit=20"), url)
        assertTrue(url.contains("order=desc"), url)
        assertTrue(url.contains("purpose=${FilePurpose.UserData.wireName}"), url)
    }

    @Test
    fun `list without cursor omits the after parameter`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) {
            HttpStatusCode.OK to """{"object":"list","data":[],"has_more":false}"""
        }

        client(pool).files().list()

        val url = requests.single().url
        assertFalse(url.contains("after="), url)
        assertFalse(url.contains("limit="), url)
        assertTrue(url.contains("order=asc"), url)
    }

    @Test
    fun `list parses files and pagination fields`() = runTest {
        val pool = recordingPool(mutableListOf()) {
            HttpStatusCode.OK to
                """{"object":"list","data":[$fileJson],"first_id":"file-api-1",""" +
                """"last_id":"file-api-1","has_more":true}"""
        }

        val page = client(pool).files().list()

        assertEquals(1, page.data.size)
        assertEquals("file-api-1", page.firstId)
        assertEquals("file-api-1", page.lastId)
        assertTrue(page.hasMore)
    }

    @Test
    fun `delete sends DELETE to the file resource`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) {
            HttpStatusCode.OK to """{"id":"file-api-1","object":"file","deleted":true}"""
        }

        val deletion = client(pool).files().delete("file-api-1")

        assertEquals("file-api-1", deletion.id)
        assertTrue(deletion.deleted)
        assertEquals(HttpMethod.Delete.value, requests.single().method)
        assertEquals("https://api.deepseek.com/files/file-api-1", requests.single().url)
    }

    // ── 本地 fail-fast ──

    @Test
    fun `list limit out of range fails fast without a network round trip`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests)
        val files = client(pool).files()

        assertFailsWith<IllegalArgumentException> { files.list(limit = 0) }
        assertFailsWith<IllegalArgumentException> { files.list(limit = 1001) }
        assertFailsWith<IllegalArgumentException> { files.list(after = " ") }
        assertEquals(0, requests.size, "非法参数不应产生网络往返")
    }

    @Test
    fun `blank file id fails fast without a network round trip`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests)
        val files = client(pool).files()

        assertFailsWith<IllegalArgumentException> { files.retrieve("") }
        assertFailsWith<IllegalArgumentException> { files.delete("  ") }
        assertEquals(0, requests.size, "非法参数不应产生网络往返")
    }

    // ── 错误传播 ──

    @Test
    fun `upload surfaces server errors through the shared status mapping`() = runTest {
        val pool = recordingPool(mutableListOf()) { HttpStatusCode.PaymentRequired to """{"error":"no balance"}""" }

        val failure = assertFailsWith<IllegalStateException> {
            client(pool).files().upload(FileSource.Bytes(byteArrayOf(1)), mimeType = "image/png")
        }
        assertTrue(failure.message!!.contains("余额"), "应复用统一的错误映射：${failure.message}")
    }

    @Test
    fun `upload maps 400 to IllegalArgumentException`() = runTest {
        val pool = recordingPool(mutableListOf()) { HttpStatusCode.BadRequest to """{"error":"bad"}""" }

        assertFailsWith<IllegalArgumentException> {
            client(pool).files().upload(FileSource.Bytes(byteArrayOf(1)), mimeType = "image/png")
        }
    }

    // ── 装配与共享 ──

    @Test
    fun `files is available on both clients and respects a custom base url`() = runTest {
        val requests = mutableListOf<RecordedRequest>()
        val pool = recordingPool(requests) { HttpStatusCode.OK to fileJson }

        val stateful = Deepseek("sk-test", baseUrl = "https://my-provider.example.com", sharingPool = pool)
        val stateless = StatelessDeepseek("sk-test", baseUrl = "https://my-provider.example.com", sharingPool = pool)

        stateful.files().retrieve("file-api-1")
        stateless.files().retrieve("file-api-1")

        assertEquals(
            listOf(
                "https://my-provider.example.com/files/file-api-1",
                "https://my-provider.example.com/files/file-api-1",
            ),
            requests.map { it.url },
        )
    }

    @Test
    fun `files returns the same instance on repeated calls`() {
        val ds = Deepseek("sk-test", sharingPool = recordingPool(mutableListOf()))
        assertTrue(ds.files() === ds.files(), "同一客户端应复用同一个 Files API 实例")
    }

    /**
     * 共享模型：`DeepseekFilesApiImpl` 自身无状态，因此**同一个实例**应能承接并发调用，
     * 每个请求各带自己的 multipart 体，互不串味。
     *
     * 这条用例是并发契约的回归保护：只要有人在实现里加上「当前上传」这类字段，它就会失败。
     */
    @Test
    fun `concurrent uploads on one instance stay isolated`() = runTest {
        val bodies = mutableListOf<ByteArray>()
        val bodiesLock = Mutex()
        val pool = DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory {
                HttpClient(
                    MockEngine { request ->
                        val bytes = request.body.readBytesOrEmpty()
                        bodiesLock.withLock { bodies.add(bytes) }
                        respond(
                            content =
                            """{"id":"file-api-1","object":"file","bytes":1,"created_at":1,""" +
                                """"filename":"x","purpose":"user_data"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            },
        )
        val files = Deepseek("sk-test", sharingPool = pool).files()

        // 20 个并发上传，每个 payload 互不相同，通过 multipart 边界识别归属
        val payloads = (0 until 20).map { "payload-$it-${"x".repeat(64)}" }
        val results = coroutineScope {
            payloads.map { payload ->
                async(Dispatchers.Default) {
                    files.upload(
                        FileSource.Bytes(payload.encodeToByteArray()),
                        mimeType = "image/jpeg",
                        filename = "f-${payload.substringAfter("payload-").substringBefore("-")}.jpg",
                    )
                }
            }.awaitAll()
        }

        assertEquals(message = "全部并发上传都应成功", expected = 20, actual = results.size)
        assertEquals(message = "应恰好发出 20 个请求", expected = 20, actual = bodies.size)
        // 每个请求体只能包含它自己那次的 payload 与文件名
        payloads.forEachIndexed { index, payload ->
            val matching = bodies.filter { it.decodeToString().contains(payload) }
            assertEquals(
                message = "payload #$index 应恰好出现在一个请求体里（无串味、无丢失）",
                expected = 1,
                actual = matching.size,
            )
            assertTrue(
                message = "请求体应带自己的文件名：${matching.single().decodeToString().take(160)}",
                actual = matching.single().decodeToString().contains("name=file; filename=f-$index.jpg"),
            )
        }
    }

    /** 并发调用不应互相阻塞成串行：20 个上传的挂钟耗时必须远小于「串行 20 × 单次延迟」 */
    @Test
    fun `concurrent calls do not serialize behind one another`() = runTest {
        var concurrent = 0
        var maxConcurrent = 0
        val counterLock = Mutex()
        val pool = DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory {
                HttpClient(
                    MockEngine {
                        val now = counterLock.withLock { ++concurrent }
                        maxConcurrent = maxOf(maxConcurrent, now)
                        delay(50)
                        counterLock.withLock { concurrent-- }
                        respond(
                            content =
                            """{"id":"file-api-1","object":"file","bytes":1,"created_at":1,""" +
                                """"filename":"x","purpose":"user_data"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            },
        )
        val files = Deepseek("sk-test", sharingPool = pool).files()

        coroutineScope {
            (0 until 8).map { async(Dispatchers.Default) { files.retrieve("file-api-$it") } }.awaitAll()
        }

        assertTrue(
            message = "8 个请求应真正并发（实测最大并发 $maxConcurrent），而不是被实例级锁串行化",
            actual = maxConcurrent > 1,
        )
    }

    @Test
    fun `dsl built clients expose files`() {
        val pool = recordingPool(mutableListOf())
        val ds = deepseek("sk-test") { sharingPool = pool }
        assertTrue(ds.files() === ds.files())
    }
}
