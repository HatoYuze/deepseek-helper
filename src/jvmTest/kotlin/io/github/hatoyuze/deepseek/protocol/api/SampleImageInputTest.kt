package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.openFileSource
import io.github.hatoyuze.deepseek.protocol.api.image.fileSourceOfResource
import io.github.hatoyuze.deepseek.protocol.api.image.imageBytesOfResource
import io.github.hatoyuze.deepseek.protocol.api.image.imageResourceUrl
import io.github.hatoyuze.deepseek.protocol.api.impl.requireValidFilename
import io.github.hatoyuze.deepseek.protocol.api.impl.defaultUploadFilename
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientFactory
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.net.HttpHook
import io.github.hatoyuze.deepseek.protocol.net.HttpHookRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 针对仓库根目录 `sample.jpg` 的真实图片输入测试（**离线**，CI 无密钥也能跑）。
 *
 * 这一层用真实 JPEG 走完整条链路、但不出网：
 * - 真实文件读取（`openFileSource(...).use { }`，经 expect/actual 的平台实现）
 * - 真实 base64 编码与解码回读比对（图片不是文本，丢一个字节就坏）
 * - 真实 multipart 分片组装（Files API 上传路径：分片头、Content-Type、二进制正文）
 * - 真实 chat 请求体的序列化形状，以及 hook 的脱敏结果
 *
 * 需要真正调用模型、验证「模型确实看见了图」的测试在 `ImageInputLiveTest`，
 * 它需要 `DEEPSEEK_API_KEY`，缺失时自动跳过。
 */
class SampleImageInputTest {

    /** 夹具：`src/jvmTest/resources/sample.jpg`（随测试 classpath 打包，不放在项目根目录） */
    private val sampleResource = "/sample.jpg"

    private fun sampleBytes(): ByteArray = imageBytesOfResource(sampleResource)

    /** 同一张图的本地文件路径（用于验证路径来源） */
    private fun samplePath(): String = fileSourceOfResource(sampleResource).use { it }.let {
        // FileSource 不暴露路径；用资源 URL 反推本地路径
        java.io.File(imageResourceUrl(sampleResource).toURI()).path
    }

    @AfterTest
    fun clearHooks() {
        HttpHookRegistry.remove(recordingHook)
    }

    private val recordingHook = object : HttpHook {
        val requestBodies = mutableListOf<String?>()

        override fun onRequest(method: String, url: String, headers: Map<String, String>, body: String?) {
            requestBodies.add(body)
        }

        override fun onResponse(
            method: String,
            url: String,
            status: Int,
            headers: Map<String, String>,
            body: String?,
        ) = Unit
    }

    // ── 真实文件读取与编码 ──

    @Test
    fun `sample jpg is read through FileSource and survives a base64 round trip`() {
        val raw = sampleBytes()
        assertTrue(message = "样例图片应当是一张真实照片，实际 ${raw.size} 字节", actual = raw.size > 10_000)
        assertJpegMagic(raw)

        val viaSource = fileSourceOfResource(sampleResource).use { it.readBytes() }
        assertContentEquals(raw, viaSource, "FileSource.Path 读到的内容应与直接读取一致")

        val dataUrl = MessageContent.imageDataUrl("image/jpeg", raw)
        assertTrue(message = "应编码为 JPEG data URL", actual = dataUrl.startsWith("data:image/jpeg;base64,"))
        assertContentEquals(
            raw,
            Base64.Default.decode(dataUrl.substringAfter(',')),
            "base64 往返必须无损",
        )
    }

    @Test
    fun `sample jpg becomes a content block next to the question`() {
        val raw = sampleBytes()
        val content = MessageContent.Parts(
            listOf(
                MessageContent.textPart("这张图片里有什么？"),
                ContentPart.ImagePart(imageUrl = MessageContent.imageDataUrl("image/jpeg", raw)),
            ),
        )

        assertEquals(message = "asText 应返回文本块", expected = "这张图片里有什么？", actual = content.asText())
        val image = content.parts[1]
        assertTrue(message = "第二个内容块应是图片", actual = image is ContentPart.ImagePart)
        assertTrue(message = "base64 长度应大于原始字节数", actual = image.imageUrl!!.length > raw.size)
    }

    // ── 与真实 HTTP 请求体的组合（MockEngine，不出网） ──

    /** 上传真实图片：分片头、类型与**完整二进制**都要落在正确位置上。 */
    @Test
    fun `sample jpg uploads as multipart with intact bytes`() = runTest {
        val raw = sampleBytes()
        val bodies = mutableListOf<ByteArray>()
        val pool = DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory {
                HttpClient(
                    MockEngine { request ->
                        bodies.add(request.body.readAllBytes())
                        respond(
                            content =
                            """{"id":"file-api-sample","object":"file","bytes":${raw.size},""" +
                                """"created_at":1,"filename":"sample.jpg","purpose":"user_data"}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            },
        )

        val uploaded = Deepseek("sk-test", sharingPool = pool).files().upload(
            FileSource.Bytes(raw),
            mimeType = "image/jpeg",
            filename = "sample.jpg",
        )

        assertEquals(message = "上传返回的 id 应来自响应", expected = "file-api-sample", actual = uploaded.id)
        assertEquals(message = "字节数应与本地一致", expected = raw.size.toLong(), actual = uploaded.bytes)

        val body = bodies.single()
        val text = body.decodeToString()
        assertTrue(
            message = "分片头应带真实文件名：${text.take(200)}",
            actual = text.contains("Content-Disposition: form-data; name=file; filename=sample.jpg"),
        )
        assertTrue(message = "分片应带图片类型", actual = text.contains("Content-Type: image/jpeg"))
        // 正文里必须能找到 JPEG 的 SOI 标记（FF D8 FF）：证明二进制没有被转义/截断
        assertTrue(
            message = "multipart 正文应包含完整的 JPEG 数据",
            actual = body.containsSubArray(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
        )
    }

    /**
     * 真实的 chat 请求：内容块形状正确，且 hook 看到的请求体**不含**图片数据。
     *
     * 走一次真正的 chatStream（MockEngine 提供 SSE 响应），因此覆盖序列化 + hook 脱敏的
     * 端到端组合；未配置 key、无网络也能跑。
     */
    @Test
    fun `sample jpg chat request carries image blocks and hides them from the hook`() = runTest {
        HttpHookRegistry.add(recordingHook)
        val raw = sampleBytes()
        val dataUrl = MessageContent.imageDataUrl("image/jpeg", raw)
        val payloadFragment = dataUrl.substringAfter("base64,").take(64)

        val pool = DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory {
                HttpClient(
                    MockEngine {
                        respond(
                            content = "data: {\"choices\":[]}\n\ndata: [DONE]\n\n",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            },
        )
        val ds = StatelessDeepseek("sk-test", sharingPool = pool)

        // Ktor 的 SSE 插件在 MockEngine 上不投递解析后的事件（仓库已知限制），
        // 这里只关心「请求发出去时长什么样」，因此吞掉流本身的异常
        runCatching {
            ds.chatStream(
                MessageContent.Parts(
                    listOf(
                        MessageContent.textPart("这张图片里有什么？"),
                        ContentPart.ImagePart(imageUrl = dataUrl),
                    ),
                ),
            ).collect { }
        }

        val body = recordingHook.requestBodies.filterNotNull().firstOrNull()
            ?: error("chat 请求应当触发 hook：${recordingHook.requestBodies}")
        assertTrue(message = "请求体应含 image_url 内容块：${body.take(200)}", actual = body.contains("\"image_url\""))
        assertTrue(message = "请求体应含文本块", actual = body.contains("这张图片里有什么？"))
        assertTrue(message = "图片数据应被脱敏：${body.take(300)}", actual = body.contains("data:image/jpeg;base64,<redacted"))
        assertEquals(message = "脱敏后不得残留图片数据", expected = false, actual = body.contains(payloadFragment))
    }

    // ── 文件名与真实文件名的交互 ──

    @Test
    fun `sample jpg filename passes validation and its path yields a clean default name`() {
        requireValidFilename("sample.jpg")
        assertEquals(message = "真实路径应推导出文件名", expected = "sample.jpg", actual = defaultUploadFilename(samplePath()))
        assertEquals(message = "目录路径只取文件名", expected = "sample.jpg", actual = defaultUploadFilename("/tmp/photos/sample.jpg"))
        assertEquals(
            message = "Windows 路径只取文件名",
            expected = "sample.jpg",
            actual = defaultUploadFilename("""C:\photos\sample.jpg"""),
        )
    }

    @Test
    fun `reading the sample twice yields identical bytes`() {
        val first = fileSourceOfResource(sampleResource).use { it.readBytes() }
        val second = sampleBytes()

        assertContentEquals(first, second, "平台读取实现应与直接读取一致")
        assertTrue(message = "样例图片应是一张真实照片", actual = first.size > 10_000)
        assertEquals(message = "JPEG 应以此开头", expected = 0xFF.toByte(), actual = first[0])
        assertEquals(message = "JPEG 的第二字节应为 0xD8", expected = 0xD8.toByte(), actual = first[1])
    }

    @Test
    fun `reading a missing sample path reports the documented error`() {
        assertFailsWith<io.github.hatoyuze.deepseek.protocol.api.entity.FileSourceReadException> {
            openFileSource("does-not-exist-sample.jpg").readBytes()
        }
    }
}

/** JPEG 魔数：`FF D8 FF`（SOI 与第一个标记段） */
private fun assertJpegMagic(bytes: ByteArray) {
    assertTrue(bytes.size >= 3, "文件太小，不可能是 JPEG")
    assertEquals(0xFF.toByte(), bytes[0], "JPEG 应以 0xFF 开头")
    assertEquals(0xD8.toByte(), bytes[1], "JPEG 的第二字节应为 0xD8")
}

/** 在字节数组中查找子序列（断言二进制确实落在请求体里） */
private fun ByteArray.containsSubArray(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    outer@ for (i in 0..(size - needle.size)) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}

/** 读出任意 [OutgoingContent] 的字节（multipart 需要真正写入 channel 才会生成字节流） */
private suspend fun OutgoingContent.readAllBytes(): ByteArray = when (this) {
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
