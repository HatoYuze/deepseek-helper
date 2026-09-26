package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MessageContent` / `ContentPart` 的构造期不变量与读取语义。
 *
 * 这里固化的是「库侧 fail-fast」的部分：官方文档明确规定
 * `image_url` 与 `file_id` 互斥、`file_id` 与 `file_data` 互斥，
 * 都不传也都是 `400`。库把这三种情况变成构造期异常，而不是留给服务端。
 */
class MessageContentTest {

    // ── 文本读取 ──

    @Test
    fun `asText returns the plain text`() {
        assertEquals("你好", MessageContent.Text("你好").asText())
        assertEquals("你好", MessageContent.of("你好").asText())
    }

    @Test
    fun `asText concatenates text parts in order`() {
        val content = MessageContent.Parts(
            listOf(
                MessageContent.textPart("a"),
                MessageContent.image("https://example.com/a.jpg").parts.single(),
                MessageContent.textPart("b"),
            ),
        )
        assertEquals("ab", content.asText())
    }

    @Test
    fun `asText is null when there is no text part`() {
        assertNull(MessageContent.image("https://example.com/a.jpg").asText())
        assertNull(MessageContent.imageFile("file-api-1").asText())
        assertNull(MessageContent.textPart("").let { MessageContent.of(it) }.asText())
    }

    @Test
    fun `textOrNull maps parts to their text view`() {
        assertEquals("hi", MessageContent.textPart("hi").textOrNull())
        assertNull(ContentPart.ImagePart(imageUrl = "https://example.com/a.jpg").textOrNull())
        assertNull(ContentPart.FilePart(fileId = "file-api-1").textOrNull())
    }

    // ── 便捷构造 ──

    @Test
    fun `factories build the expected shapes`() {
        assertEquals(MessageContent.Text("x"), MessageContent.of("x"))
        assertEquals(
            listOf(ContentPart.TextPart("x"), ContentPart.TextPart("y")),
            MessageContent.of(MessageContent.textPart("x"), MessageContent.textPart("y")).parts,
        )
        assertEquals(
            ContentPart.ImagePart(imageUrl = "https://example.com/a.jpg", detail = ImageUrlDetail.Auto),
            MessageContent.image("https://example.com/a.jpg").parts.single(),
        )
        assertEquals(
            ContentPart.ImagePart(fileId = "file-api-1"),
            MessageContent.imageFile("file-api-1").parts.single(),
        )
        assertEquals(
            ContentPart.FilePart(fileData = "data:image/png;base64,QQ==", filename = "a.png"),
            MessageContent.fileData("data:image/png;base64,QQ==", "a.png").parts.single(),
        )
    }

    @Test
    fun `asText single pass handles text parts mixed with images`() {
        // 单趟实现：文本块之间夹着图片块、只有一个文本块、连续多个文本块都要正确
        assertEquals("a", MessageContent.of(MessageContent.textPart("a")).asText())
        assertEquals(
            "a",
            MessageContent.Parts(
                listOf(MessageContent.textPart("a"), MessageContent.image("https://e/a.jpg").parts.single()),
            ).asText(),
        )
        assertEquals(
            "ab",
            MessageContent.Parts(
                listOf(
                    MessageContent.textPart("a"),
                    MessageContent.image("https://e/a.jpg").parts.single(),
                    MessageContent.textPart("b"),
                ),
            ).asText(),
        )
        assertEquals(
            "abc",
            MessageContent.of(
                MessageContent.textPart("a"),
                MessageContent.textPart("b"),
                MessageContent.textPart("c"),
            ).asText(),
        )
        assertNull(
            MessageContent.of(
                MessageContent.textPart(""),
                MessageContent.image("https://e/a.jpg").parts.single(),
            ).asText(),
            "全空文本块应视为没有文本",
        )
    }

    @Test
    fun `empty parts list is rejected`() {
        assertFailsWith<IllegalArgumentException> { MessageContent.Parts(emptyList()) }
        assertFailsWith<IllegalArgumentException> { MessageContent.of(emptyList()) }
    }

    // ── 图片块不变量 ──

    @Test
    fun `image part requires exactly one source`() {
        val both = assertFailsWith<IllegalArgumentException> {
            ContentPart.ImagePart(imageUrl = "https://example.com/a.jpg", fileId = "file-api-1")
        }
        assertTrue(both.message!!.contains("互斥"), "异常消息应说明互斥：${both.message}")

        val neither = assertFailsWith<IllegalArgumentException> { ContentPart.ImagePart() }
        assertTrue(neither.message!!.contains("imageUrl 或 fileId"), "异常消息应指明缺什么：${neither.message}")
    }

    @Test
    fun `image part keeps the given detail`() {
        val part = ContentPart.ImagePart(imageUrl = "https://example.com/a.jpg", detail = ImageUrlDetail.Low)
        assertEquals(ImageUrlDetail.Low, part.detail)
        assertEquals(ImageUrlDetail.Auto, ContentPart.ImagePart(fileId = "file-api-1").detail)
    }

    // ── 文件块不变量 ──

    @Test
    fun `file part requires exactly one source`() {
        val both = assertFailsWith<IllegalArgumentException> {
            ContentPart.FilePart(fileId = "file-api-1", fileData = "data:image/png;base64,QQ==")
        }
        assertTrue(both.message!!.contains("互斥"), "异常消息应说明互斥：${both.message}")

        assertFailsWith<IllegalArgumentException> { ContentPart.FilePart() }
    }

    // ── 图片地址与体积的本地 fail-fast ──

    @Test
    fun `image url must be http or data`() {
        assertEquals(
            "https://example.com/a.jpg",
            (MessageContent.image("https://example.com/a.jpg").parts.single() as ContentPart.ImagePart).imageUrl,
        )
        MessageContent.image("data:image/png;base64,QQ==")

        listOf("file:///etc/passwd", "ftp://example.com/a.jpg", "/local/cat.jpg", "example.com/a.jpg")
            .forEach { url ->
                val failure = assertFailsWith<IllegalArgumentException>("应拒绝：$url") {
                    MessageContent.image(url)
                }
                assertTrue(failure.message!!.contains("http"), "应说明支持的形态：${failure.message}")
            }

        val blank = assertFailsWith<IllegalArgumentException> { MessageContent.image("  ") }
        assertTrue(blank.message!!.contains("空白"), "空白地址应有明确提示：${blank.message}")
    }

    @Test
    fun `external image url longer than the documented limit is rejected`() {
        val tooLong = "https://example.com/" + "a".repeat(MessageContent.MAX_IMAGE_URL_LENGTH)
        assertFailsWith<IllegalArgumentException> { MessageContent.image(tooLong) }
    }

    @Test
    fun `data urls are not subject to the external url length limit`() {
        // 内联图片本来就比 8192 字符长（官方限制只针对外部 URL）
        // 9000 字节 → base64 约 12000 字符，超过外部 URL 的 8192 上限
        val bigInline = MessageContent.imageDataUrl("image/png", ByteArray(9000) { 1 })
        assertTrue(bigInline.length > MessageContent.MAX_IMAGE_URL_LENGTH)
        MessageContent.image(bigInline)
    }

    @Test
    fun `inline bytes larger than the documented limit are rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            MessageContent.dataUrl("image/png", ByteArray(MessageContent.MAX_INLINE_IMAGE_BYTES + 1))
        }
        assertTrue(failure.message!!.contains("MiB"), "应说明上限来源：${failure.message}")
    }

    @Test
    fun `file part keeps filename for inline data`() {
        val part = ContentPart.FilePart(fileData = "data:image/png;base64,QQ==", filename = "a.png")
        assertEquals("a.png", part.filename)
        assertEquals("data:image/png;base64,QQ==", part.fileData)
        assertNull(part.fileId)
    }

    // ── 与 Message 组合 ──

    @Test
    fun `message accepts a string literal through the companion conversion`() {
        // 隐式转换只对**字面量**生效；这是既有代码 `Message(Role.User, "hi")` 的兼容路径
        val message = Message(Role.User, MessageContent.of("hi"))
        assertEquals("hi", message.content?.asText())
    }

    @Test
    fun `message carries image parts`() {
        val message = Message(
            role = Role.User,
            content = MessageContent.Parts(
                listOf(
                    MessageContent.textPart("看图"),
                    MessageContent.imageFile("file-api-1").parts.single(),
                ),
            ),
        )
        val parts = assertIs<MessageContent.Parts>(message.content).parts
        assertEquals(2, parts.size)
        assertIs<ContentPart.ImagePart>(parts[1])
    }
}

/**
 * `UploadOptions` 的有效期区间（官方：1 小时到 30 天，不传则永久有效）。
 */
class UploadOptionsTest {

    @Test
    fun `null means never expires`() {
        assertNull(UploadOptions().expiresAfterSeconds)
    }

    @Test
    fun `inclusive bounds are accepted`() {
        assertEquals(3600, UploadOptions(UploadOptions.MIN_EXPIRY_SECONDS).expiresAfterSeconds)
        assertEquals(2_592_000, UploadOptions(UploadOptions.MAX_EXPIRY_SECONDS).expiresAfterSeconds)
    }

    @Test
    fun `out of range values are rejected`() {
        assertFailsWith<IllegalArgumentException> { UploadOptions(3599) }
        assertFailsWith<IllegalArgumentException> { UploadOptions(2_592_001) }
        assertFailsWith<IllegalArgumentException> { UploadOptions(0) }
        assertFailsWith<IllegalArgumentException> { UploadOptions(-1) }
    }
}
