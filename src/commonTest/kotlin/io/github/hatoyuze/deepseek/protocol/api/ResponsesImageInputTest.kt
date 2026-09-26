package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.ImageUrlDetail
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.impl.extractResponsesInstructions
import io.github.hatoyuze.deepseek.protocol.api.impl.requireImagesAllowed
import io.github.hatoyuze.deepseek.protocol.api.impl.toResponsesInputItems
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Responses API 侧的图片输入映射。
 *
 * 官方文档（create-response）：message item 的 content 可以是 `input_text` /
 * `output_text` / `input_image` 内容块列表；`input_image` 的 `image_url` 与 `file_id`
 * 互斥，设置 `file_id` 时 `detail` 被忽略。
 */
class ResponsesImageInputTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun blocks(message: Message) = listOf(message).toResponsesInputItems().jsonArray[0]
        .jsonObject["content"]!!.jsonArray

    @Test
    fun `text parts map to input_text`() {
        val message = Message(
            Role.User,
            MessageContent.of(MessageContent.textPart("看图"), MessageContent.textPart("再说一遍")),
        )
        val blocks = blocks(message)
        assertEquals(2, blocks.size)
        blocks.forEach {
            assertEquals("input_text", it.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertEquals("看图", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `image url part maps to input_image with image_url`() {
        val message = Message(Role.User, MessageContent.image("https://example.com/cat.jpg"))
        val block = blocks(message).single().jsonObject
        assertEquals("input_image", block["type"]!!.jsonPrimitive.content)
        assertEquals("https://example.com/cat.jpg", block["image_url"]!!.jsonPrimitive.content)
        // detail 默认 auto 时不下发：与服务端默认等价
        assertNull(block["detail"])
        assertNull(block["file_id"])
    }

    @Test
    fun `image url part with explicit detail sends the detail value`() {
        val message = Message(
            Role.User,
            MessageContent.of(ContentPart.ImagePart(imageUrl = "https://example.com/a.jpg", detail = ImageUrlDetail.Low)),
        )
        assertEquals("low", blocks(message).single().jsonObject["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `file id image part maps to input_image with file_id and no detail`() {
        val message = Message(
            Role.User,
            MessageContent.of(ContentPart.ImagePart(fileId = "file-api-1", detail = ImageUrlDetail.Low)),
        )
        val block = blocks(message).single().jsonObject
        assertEquals("input_image", block["type"]!!.jsonPrimitive.content)
        assertEquals("file-api-1", block["file_id"]!!.jsonPrimitive.content)
        // 官方：设置 file_id 时 detail 被忽略 —— 因此这里根本不发送，避免给服务端制造歧义
        assertNull(block["detail"])
        assertNull(block["image_url"])
    }

    @Test
    fun `file part with file id maps to input_image with file_id`() {
        val message = Message(Role.User, MessageContent.of(ContentPart.FilePart(fileId = "file-api-2")))
        val block = blocks(message).single().jsonObject
        assertEquals("input_image", block["type"]!!.jsonPrimitive.content)
        assertEquals("file-api-2", block["file_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `file part with inline data maps to input_image with image_url`() {
        // Responses 没有 `file` 内容块；file_data 本身就是 base64 data URL，语义等价
        val message = Message(Role.User, MessageContent.fileData("data:image/png;base64,QQ==", "a.png"))
        val block = blocks(message).single().jsonObject
        assertEquals("input_image", block["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,QQ==", block["image_url"]!!.jsonPrimitive.content)
        // input_image 没有 filename 字段：不透传（KDoc 已说明）
        assertNull(block["filename"])
    }

    @Test
    fun `mixed text and image keeps the order`() {
        val message = Message(
            Role.User,
            MessageContent.Parts(
                listOf(
                    MessageContent.textPart("先看这张"),
                    MessageContent.imageFile("file-api-1").parts.single(),
                    MessageContent.textPart("再说结论"),
                ),
            ),
        )
        val blocks = blocks(message).map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("input_text", "input_image", "input_text"), blocks)
    }

    @Test
    fun `plain text message still maps to a single input_text block`() {
        val blocks = blocks(Message(Role.User, MessageContent.of("hello")))
        assertEquals(1, blocks.size)
        assertEquals("hello", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `null content maps to an empty input_text block`() {
        val blocks = blocks(Message(Role.User, null))
        assertEquals("", blocks.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    // ── instructions 提取 ──

    @Test
    fun `system prompt is extracted as instructions via asText`() {
        val messages = listOf(
            Message(Role.System, MessageContent.of("be nice")),
            Message(Role.User, MessageContent.of("hi")),
        )
        val (instructions, rest) = extractResponsesInstructions(messages)
        assertEquals("be nice", instructions)
        assertEquals(1, rest.size)
    }

    @Test
    fun `image only system message is not used as instructions`() {
        val messages = listOf(
            Message(Role.System, MessageContent.image("https://example.com/a.jpg")),
            Message(Role.User, MessageContent.of("hi")),
        )
        val (instructions, rest) = extractResponsesInstructions(messages)
        assertNull(instructions, "没有文本的 system 消息不应作为 instructions")
        assertEquals(2, rest.size)
    }

    // ── 角色限制 ──

    @Test
    fun `images are rejected outside user messages`() {
        val assistant = Message(Role.Assistant, MessageContent.image("https://example.com/a.jpg"))
        val failure = assertFailsWith<IllegalArgumentException> { listOf(assistant).requireImagesAllowed() }
        assertTrue(failure.message!!.contains("user"), "异常消息应说明限制：${failure.message}")

        assertFailsWith<IllegalArgumentException> {
            listOf(Message(Role.System, MessageContent.imageFile("file-api-1"))).requireImagesAllowed()
        }
        assertFailsWith<IllegalArgumentException> {
            listOf(Message(Role.Tool, MessageContent.of(ContentPart.FilePart(fileId = "file-api-1"))))
                .requireImagesAllowed()
        }
    }

    @Test
    fun `text messages pass the role check for every role`() {
        listOf(
            Message(Role.System, MessageContent.of("s")),
            Message(Role.User, MessageContent.of("u")),
            Message(Role.Assistant, MessageContent.of("a")),
            Message(Role.Tool, MessageContent.of("t")),
            Message(Role.Assistant, null),
        ).requireImagesAllowed()
    }

    @Test
    fun `user messages with images pass the role check`() {
        listOf(
            Message(Role.User, MessageContent.image("https://example.com/a.jpg")),
            Message(Role.User, MessageContent.imageFile("file-api-1")),
        ).requireImagesAllowed()
    }

    @Test
    fun `toResponsesInputItems enforces the role restriction`() {
        val messages = listOf(Message(Role.Assistant, MessageContent.imageFile("file-api-1")))
        assertFailsWith<IllegalArgumentException> { messages.toResponsesInputItems() }
    }

    @Test
    fun `responses input items remain valid json`() {
        val messages = listOf(
            Message(
                Role.User,
                MessageContent.Parts(
                    listOf(MessageContent.textPart("hi"), MessageContent.imageFile("file-api-1").parts.single()),
                ),
            ),
        )
        val encoded = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), messages.toResponsesInputItems())
        assertTrue(encoded.contains("\"input_image\""), "应产出 input_image 块：$encoded")
        assertTrue(encoded.contains("file-api-1"), "应带上 file_id：$encoded")
    }
}
