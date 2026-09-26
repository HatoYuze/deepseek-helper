package io.github.hatoyuze.deepseek.protocol.api.dsl

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.ImageUrlDetail
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.image.fileSourceOfResource
import io.github.hatoyuze.deepseek.protocol.api.image.imageBytesOfResource
import io.github.hatoyuze.deepseek.protocol.api.image.imageResourceUrl
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `buildDeepseekMessages` DSL 的契约。
 *
 * 直接钉住需求里给出的样例写法，避免以后重构把 infix 语法改坏：
 * ```kotlin
 * Role.System says "You are a helpful assistant"
 * val image = imageOf("cat.jpg")
 * Role.User says image + "What is its content"
 * Role.Assistance says "This image describes ..."
 * ```
 */
class MessageDslTest {

    private val sampleResource = "/sample.jpg"

    // ── 基本语法 ──

    @Test
    fun `sample usage from the request compiles and builds the expected history`() {
        // 夹具用图片字节代替文件路径：语法与需求样例逐行一致
        val image = imageOf(imageBytesOfResource(sampleResource))

        val messages = buildDeepseekMessages {
            Role.System says "You are a helpful assistant"
            Role.User says image + "What is its content"
            Role.Assistance says "This image describes a scene that ..."
        }

        assertEquals(3, messages.size)
        assertEquals(listOf(Role.System, Role.User, Role.Assistance), messages.map { it.role })
        assertEquals("You are a helpful assistant", messages[0].content?.asText())
        assertEquals("This image describes a scene that ...", messages[2].content?.asText())

        // user 消息 = 图片块 + 文本块，顺序为「先图后文」
        val parts = assertIs<MessageContent.Parts>(messages[1].content).parts
        assertEquals(2, parts.size)
        assertIs<ContentPart.ImagePart>(parts[0])
        assertEquals("What is its content", (parts[1] as ContentPart.TextPart).text)
        assertEquals("What is its content", messages[1].content?.asText())
    }

    @Test
    fun `string messages stay plain text content`() {
        val messages = buildDeepseekMessages {
            Role.User says "你好"
        }

        assertEquals(MessageContent.Text("你好"), messages.single().content)
    }

    @Test
    fun `says returns the appended message`() {
        val messages = buildDeepseekMessages {
            val first = Role.User says "一"
            val second = Role.User says "二"

            assertEquals(Role.User, first.role)
            assertEquals("一", first.content?.asText())
            assertEquals("二", second.content?.asText())
        }

        assertEquals(2, messages.size, "返回值不应影响追加行为")
    }

    @Test
    fun `empty block yields an empty history`() {
        assertEquals(emptyList(), buildDeepseekMessages { })
    }

    @Test
    fun `built list is a snapshot`() {
        val builder = DeepseekMessagesBuilder()
        builder.add(io.github.hatoyuze.deepseek.protocol.api.entity.Message(Role.User, MessageContent.of("x")))
        val snapshot = builder.build()

        builder.add(io.github.hatoyuze.deepseek.protocol.api.entity.Message(Role.User, MessageContent.of("y")))

        assertEquals(1, snapshot.size, "build() 之后追加不应改变已返回的列表")
        assertEquals(2, builder.build().size)
    }

    // ── `+` 组合 ──

    @Test
    fun `plus combines images and text in order`() {
        val bytes = imageBytesOfResource(sampleResource)
        val content = imageOf(bytes) + "这是什么？"

        val parts = content.parts
        assertEquals(2, parts.size)
        assertIs<ContentPart.ImagePart>(parts[0])
        assertEquals("这是什么？", (parts[1] as ContentPart.TextPart).text)
    }

    @Test
    fun `plus combines two images`() {
        val bytes = imageBytesOfResource(sampleResource)
        val content = imageOf(bytes) + imageOf(bytes, ImageUrlDetail.Low)

        assertEquals(2, content.parts.size)
        content.parts.forEach { assertIs<ContentPart.ImagePart>(it) }
        assertEquals(ImageUrlDetail.Low, (content.parts[1] as ContentPart.ImagePart).detail)
    }

    @Test
    fun `text part first concatenation keeps the text block first`() {
        // 注意：`"文本" + imageOf(...)` 会被 stdlib 的 String.plus(Any?) 吃掉（成员优先于扩展），
        // 因此文本在前的写法要用 MessageContent.textPart(...)
        val content = MessageContent.textPart("先看图：") + imageOf(imageBytesOfResource(sampleResource))

        assertEquals(2, content.parts.size)
        assertEquals("先看图：", (content.parts[0] as ContentPart.TextPart).text)
        assertIs<ContentPart.ImagePart>(content.parts[1])
    }

    @Test
    fun `plain text plus text concatenates blocks`() {
        val content = MessageContent.of("a") + "b"

        assertEquals("ab", content.asText())
        assertEquals(2, content.parts.size)
    }

    // ── imageOf 的来源判定 ──

    @Test
    fun `imageOf infers mime from the bytes`() {
        val jpeg = imageBytesOfResource(sampleResource)
        val url = (imageOf(jpeg).parts.single() as ContentPart.ImagePart).imageUrl!!

        assertTrue(url.startsWith("data:image/jpeg;base64,"), "JPEG 应按内容判定为 image/jpeg：${url.take(40)}")
        assertContentEquals(jpeg, kotlin.io.encoding.Base64.Default.decode(url.substringAfter(',')))
    }

    @Test
    fun `imageOf sniffs png gif and webp magic numbers`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        val gif = "GIF89a".toByteArray() + ByteArray(4)
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray()
        val unknown = byteArrayOf(1, 2, 3, 4)

        assertTrue(imageOf(png).parts.single().let { (it as ContentPart.ImagePart).imageUrl!! }.startsWith("data:image/png"))
        assertTrue(imageOf(gif).parts.single().let { (it as ContentPart.ImagePart).imageUrl!! }.startsWith("data:image/gif"))
        assertTrue(imageOf(webp).parts.single().let { (it as ContentPart.ImagePart).imageUrl!! }.startsWith("data:image/webp"))
        // 识别不出时退回 jpeg（服务端仍会按内容自行判定）
        assertTrue(imageOf(unknown).parts.single().let { (it as ContentPart.ImagePart).imageUrl!! }.startsWith("data:image/jpeg"))
    }

    @Test
    fun `imageOf keeps remote urls and data urls untouched`() {
        val remote = "https://example.com/cat.jpg"
        val dataUrl = "data:image/png;base64,QQ=="

        assertEquals(remote, (imageOf(remote).parts.single() as ContentPart.ImagePart).imageUrl)
        assertEquals(dataUrl, (imageOf(dataUrl).parts.single() as ContentPart.ImagePart).imageUrl)
        assertEquals(
            ImageUrlDetail.Low,
            (imageOf(remote, ImageUrlDetail.Low).parts.single() as ContentPart.ImagePart).detail,
        )
    }

    @Test
    fun `imageOf reads a local path`() {
        val localPath = File(imageResourceUrl(sampleResource).toURI()).path
        val part = imageOf(localPath).parts.single() as ContentPart.ImagePart

        assertTrue(part.imageUrl!!.startsWith("data:image/jpeg;base64,"), "本地路径应被读成内联 data URL")
        assertContentEquals(
            imageBytesOfResource(sampleResource),
            kotlin.io.encoding.Base64.Default.decode(part.imageUrl!!.substringAfter(',')),
        )
    }

    @Test
    fun `imageOf accepts FileSource without closing it`() {
        val source = fileSourceOfResource(sampleResource)
        val part = imageOf(source).parts.single() as ContentPart.ImagePart

        assertTrue(part.imageUrl!!.startsWith("data:image/jpeg;base64,"))
        // 库不负责关闭：这里显式释放，且不应抛错
        source.close()
    }

    @Test
    fun `imageOf rejects unsupported source types with a helpful message`() {
        val failure = assertFailsWith<IllegalArgumentException> { imageOf(42) }

        assertTrue(failure.message!!.contains("ByteArray"), "错误信息应说明可用类型：${failure.message}")
        assertTrue(failure.message!!.contains("FileSource"), "错误信息应说明可用类型：${failure.message}")
    }

    @Test
    fun `imageOf rejects blank urls and empty bytes`() {
        assertFailsWith<IllegalArgumentException> { imageOf("   ") }
        assertFailsWith<IllegalArgumentException> { imageOf(ByteArray(0)) }
    }

    @Test
    fun `imageOf on a missing path reports a read failure`() {
        assertFailsWith<io.github.hatoyuze.deepseek.protocol.api.entity.FileSourceReadException> {
            imageOf("/definitely/not/here/sample.jpg")
        }
    }

    // ── 平台扩展（JVM） ──

    @Test
    fun `jvm overloads accept File URI and InputStream`() {
        val file = File(imageResourceUrl(sampleResource).toURI())
        val expected = imageBytesOfResource(sampleResource)

        val fromFile = (imageOf(file).parts.single() as ContentPart.ImagePart).imageUrl!!
        val fromUri = (imageOf(file.toURI()).parts.single() as ContentPart.ImagePart).imageUrl!!
        val fromStream = (imageOf(ByteArrayInputStream(expected)).parts.single() as ContentPart.ImagePart).imageUrl!!
        val fromRemoteUri = imageOf(URI("https://example.com/cat.jpg")).parts.single() as ContentPart.ImagePart

        assertContentEquals(expected, kotlin.io.encoding.Base64.Default.decode(fromFile.substringAfter(',')))
        assertContentEquals(expected, kotlin.io.encoding.Base64.Default.decode(fromUri.substringAfter(',')))
        assertContentEquals(expected, kotlin.io.encoding.Base64.Default.decode(fromStream.substringAfter(',')))
        assertEquals("https://example.com/cat.jpg", fromRemoteUri.imageUrl)
    }

    @Test
    fun `jvm overload rejects unsupported uri schemes`() {
        val failure = assertFailsWith<IllegalArgumentException> { imageOf(URI("ftp://example.com/cat.jpg")) }

        assertTrue(failure.message!!.contains("scheme"), "应说明 scheme 不受支持：${failure.message}")
    }

    @Test
    fun `image resource helpers load the fixture and fail clearly on a missing one`() {
        assertTrue(imageBytesOfResource(sampleResource).size > 10_000)
        // 前导斜杠可有可无，两种写法都应能找到同一份资源
        assertContentEquals(imageBytesOfResource(sampleResource), imageBytesOfResource("sample.jpg"))
        fileSourceOfResource(sampleResource).use { source ->
            assertContentEquals(imageBytesOfResource(sampleResource), source.readBytes())
        }

        assertFailsWith<IllegalArgumentException> { imageBytesOfResource("/nope.jpg") }
    }

    @Test
    fun `resource source is a FileSource Path`() {
        assertIs<FileSource.Path>(fileSourceOfResource(sampleResource))
    }
}
