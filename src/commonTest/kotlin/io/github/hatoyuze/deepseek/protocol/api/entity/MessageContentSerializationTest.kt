package io.github.hatoyuze.deepseek.protocol.api.entity

import io.github.hatoyuze.deepseek.protocol.api.DeepseekJson
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MessageContent` 的 wire format 契约。
 *
 * 这些断言逐字对应官方文档：
 * - 纯文本 → JSON 字符串；内容块 → JSON 数组
 * - `image_url` 形态：`{"type":"image_url","image_url":{"url":...,"detail":...}}`
 * - `file` 形态：`{"type":"file","file_id":...}` 或 `{"type":"file","file_data":...,"filename":...}`
 */
class MessageContentSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun encode(content: MessageContent): String =
        json.encodeToString(serializer<MessageContent>(), content)

    private fun decode(raw: String): MessageContent =
        json.decodeFromString(serializer<MessageContent>(), raw)

    // ── 形态分派 ──

    @Test
    fun `plain text serializes as a JSON string`() {
        assertEquals("\"你好\"", encode(MessageContent.Text("你好")))
    }

    @Test
    fun `empty text serializes as an empty JSON string`() {
        assertEquals("\"\"", encode(MessageContent.Text("")))
    }

    @Test
    fun `parts serialize as a JSON array`() {
        val encoded = encode(MessageContent.of(MessageContent.textPart("hi")))
        assertEquals("""[{"type":"text","text":"hi"}]""", encoded)
    }

    @Test
    fun `empty parts stay an empty array`() {
        // 官方并未禁止空数组（它只是没有意义）；库侧保留原样的形状，不做静默改写
        assertEquals("[]", json.encodeToString(serializer<List<ContentPart>>(), emptyList()))

        val parts = MessageContent.Parts(listOf(ContentPart.TextPart("")))
        assertEquals("""[{"type":"text","text":""}]""", encode(parts))
    }

    @Test
    fun `string decodes to text and array decodes to parts`() {
        assertEquals(MessageContent.Text("hello"), decode("\"hello\""))
        assertIs<MessageContent.Parts>(decode("""[{"type":"text","text":"hello"}]"""))
    }

    @Test
    fun `empty content array is rejected as a serialization error`() {
        // `{"content":[]}` 会被 Parts 的不变量拒绝：必须翻译成 SerializationException，
        // 否则只想 catch SerializationException 的调用方会漏掉这一类坏 JSON
        assertFailsWith<SerializationException> { decode("[]") }
    }

    @Test
    fun `file part with both file id and data is rejected`() {
        assertFailsWith<SerializationException> {
            decode("""[{"type":"file","file_id":"file-api-1","file_data":"data:image/png;base64,QQ=="}]""")
        }
    }

    @Test
    fun `non string primitive is rejected`() {
        assertFailsWith<SerializationException> { decode("42") }
        assertFailsWith<SerializationException> { decode("null") }
        assertFailsWith<SerializationException> { decode("{}") }
    }

    // ── 图片块 ──

    @Test
    fun `image url part keeps the nested image_url object`() {
        val encoded = encode(MessageContent.image("https://example.com/cat.jpg"))
        val block = json.parseToJsonElement(encoded).jsonArray[0].jsonObject
        assertEquals("image_url", block["type"]!!.jsonPrimitive.content)
        val imageUrl = block["image_url"]!!.jsonObject
        assertEquals("https://example.com/cat.jpg", imageUrl["url"]!!.jsonPrimitive.content)
        // detail 默认 auto：官方示例不写该字段，服务端默认即为 auto
        assertNull(imageUrl["detail"])
    }

    @Test
    fun `explicit detail is written and omitted when auto`() {
        val low = encode(MessageContent.image("https://example.com/a.png", ImageUrlDetail.Low))
        assertEquals("low", json.parseToJsonElement(low).jsonArray[0].jsonObject["image_url"]!!.jsonObject["detail"]!!.jsonPrimitive.content)

        val original = encode(MessageContent.image("https://example.com/a.png", ImageUrlDetail.Original))
        assertEquals("original", json.parseToJsonElement(original).jsonArray[0].jsonObject["image_url"]!!.jsonObject["detail"]!!.jsonPrimitive.content)

        val auto = encode(MessageContent.image("https://example.com/a.png", ImageUrlDetail.Auto))
        assertNull(json.parseToJsonElement(auto).jsonArray[0].jsonObject["image_url"]!!.jsonObject["detail"])
    }

    @Test
    fun `file_id image part serializes as a file block`() {
        // 官方 content array 里 `image_url` 块只接受 url；按 file_id 引用要写 `file` 块。
        // （实测：写成 `{"type":"image_url","image_url":{"file_id":...}}` 会被服务端以
        //  400 `missing field url` 拒绝）
        val encoded = encode(MessageContent.imageFile("file-api-abc"))
        val block = json.parseToJsonElement(encoded).jsonArray[0].jsonObject
        assertEquals("file", block["type"]!!.jsonPrimitive.content)
        assertEquals("file-api-abc", block["file_id"]!!.jsonPrimitive.content)
        assertNull(block["image_url"])
    }

    @Test
    fun `url based image part round trips exactly`() {
        val urlPart = ContentPart.ImagePart(imageUrl = "https://example.com/a.jpg", detail = ImageUrlDetail.Low)
        val encoded = encode(MessageContent.Parts(listOf(MessageContent.textPart("describe"), urlPart)))

        assertEquals(MessageContent.Parts(listOf(MessageContent.textPart("describe"), urlPart)), decode(encoded))
    }

    @Test
    fun `file_id based image part round trips as the equivalent file block`() {
        // file_id 形态的 wire format 就是 `file` 块，因此解码回来是等价的 FilePart
        // （两者对服务端含义相同：引用 Files API 里的同一个文件）
        val encoded = encode(MessageContent.of(ContentPart.ImagePart(fileId = "file-api-1")))

        assertEquals(MessageContent.of(ContentPart.FilePart(fileId = "file-api-1")), decode(encoded))
    }

    // ── 文件块 ──

    @Test
    fun `file part with file id serializes type and file_id only`() {
        val encoded = encode(MessageContent.of(ContentPart.FilePart(fileId = "file-api-xyz")))
        val block = json.parseToJsonElement(encoded).jsonArray[0].jsonObject
        assertEquals("file", block["type"]!!.jsonPrimitive.content)
        assertEquals("file-api-xyz", block["file_id"]!!.jsonPrimitive.content)
        assertNull(block["file_data"])
        assertNull(block["filename"])
    }

    @Test
    fun `file part with file data serializes file_data and filename`() {
        val encoded = encode(MessageContent.fileData("data:image/jpeg;base64,AAAA", "image.jpg"))
        val block = json.parseToJsonElement(encoded).jsonArray[0].jsonObject
        assertEquals("file", block["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAAA", block["file_data"]!!.jsonPrimitive.content)
        assertEquals("image.jpg", block["filename"]!!.jsonPrimitive.content)
    }

    @Test
    fun `file part round trips`() {
        val withId = MessageContent.of(ContentPart.FilePart(fileId = "file-api-1"))
        assertEquals(withId, decode(encode(withId)))

        val withData = MessageContent.fileData("data:image/png;base64,QQ==", "a.png")
        assertEquals(withData, decode(encode(withData)))
    }

    @Test
    fun `json null file_id falls back to file_data`() {
        // 字段存在但为 null 时不能再判定为 file_id 形态，否则会构造出非法组合
        val decoded = decode("""[{"type":"file","file_id":null,"file_data":"data:image/png;base64,QQ=="}]""")
        val part = assertIs<MessageContent.Parts>(decoded).parts[0]
        assertIs<ContentPart.FilePart>(part)
        assertEquals("data:image/png;base64,QQ==", part.fileData)
        assertNull(part.fileId)
    }

    // ── 非法输入 ──

    @Test
    fun `unknown content block type is rejected`() {
        assertFailsWith<SerializationException> {
            decode("""[{"type":"audio","audio_url":{"url":"https://example.com/a.mp3"}}]""")
        }
    }

    @Test
    fun `content block without type is rejected`() {
        assertFailsWith<SerializationException> { decode("""[{"text":"hi"}]""") }
    }

    @Test
    fun `image block without image_url object is rejected`() {
        assertFailsWith<SerializationException> { decode("""[{"type":"image_url"}]""") }
    }

    @Test
    fun `image block with both url and file_id is rejected by the model`() {
        // KSerializer 契约：解析失败一律是 SerializationException（模型的 IllegalArgumentException
        // 会在序列化器边界被翻译），这样 `catch (SerializationException)` 才能兜住所有坏 JSON
        assertFailsWith<SerializationException> {
            decode("""[{"type":"image_url","image_url":{"url":"https://a/b.jpg","file_id":"file-api-1"}}]""")
        }
    }

    @Test
    fun `image block with neither url nor file_id is rejected`() {
        assertFailsWith<SerializationException> {
            decode("""[{"type":"image_url","image_url":{"detail":"low"}}]""")
        }
    }

    @Test
    fun `non string url is rejected as a serialization error`() {
        assertFailsWith<SerializationException> {
            decode("""[{"type":"image_url","image_url":{"url":{"nested":1}}}]""")
        }
    }

    @Test
    fun `non object type field is rejected as a serialization error`() {
        assertFailsWith<SerializationException> { decode("""[{"type":{"a":1}}]""") }
    }

    @Test
    fun `file block with neither file_id nor file_data is rejected`() {
        assertFailsWith<SerializationException> { decode("""[{"type":"file","filename":"a.jpg"}]""") }
    }

    @Test
    fun `file block with non string file_id is rejected`() {
        assertFailsWith<SerializationException> { decode("""[{"type":"file","file_id":42}]""") }
    }

    // ── base64 辅助 ──

    @Test
    fun `imageDataUrl encodes bytes as a base64 data url`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val url = MessageContent.imageDataUrl("image/png", bytes)
        assertTrue(url.startsWith("data:image/png;base64,"), "前缀应为 data URL：$url")
        assertContentEquals(bytes, Base64.Default.decode(url.substringAfter(",")))
    }

    @Test
    fun `dataUrl keeps the given mime type verbatim`() {
        assertEquals("data:image/jpeg;base64,", MessageContent.dataUrl("image/jpeg", byteArrayOf()))
    }

    // ── 与 Message 的组合 ──

    @Test
    fun `message content with parts serializes the array in place`() {
        val message = Message(
            role = Role.User,
            content = MessageContent.Parts(
                listOf(
                    MessageContent.textPart("what is in this image?"),
                    MessageContent.image("https://example.com/cat.jpg").parts.single(),
                ),
            ),
        )
        val obj = json.parseToJsonElement(json.encodeToString(serializer<Message>(), message)).jsonObject
        val blocks = obj["content"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `message content text still serializes as a plain string`() {
        val message = Message(Role.User, MessageContent.of("hi"))
        val obj = json.parseToJsonElement(json.encodeToString(serializer<Message>(), message)).jsonObject
        assertEquals("hi", obj["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `message content null keeps the field absent`() {
        val message = Message(Role.Assistance, null)
        // 库的 Json 配置（DeepseekJson）为 explicitNulls = false：content 为 null 时该字段不写出
        val obj = json.parseToJsonElement(DeepseekJson.encodeToString(Message.serializer(), message)).jsonObject
        assertFalse(obj.containsKey("content"), "content=null 时不应写出该字段：$obj")
    }

    @Test
    fun `null content is still decodable`() {
        // 省略 null 字段只影响编码：历史上可能有 `content: null` 的记录，解码必须照常
        val decoded = DeepseekJson.decodeFromString(Message.serializer(), """{"role":"assistant","content":null}""")
        assertEquals(Role.Assistance, decoded.role)
        assertNull(decoded.content)
    }

    @Test
    fun `optional null fields are omitted from the request shape`() {
        val message = Message(Role.Tool, MessageContent.of("{}"), toolCallId = "call_1")
        val obj = json.parseToJsonElement(DeepseekJson.encodeToString(Message.serializer(), message)).jsonObject
        assertFalse(obj.containsKey("tool_calls"), "tool_calls 为 null 时不应写出：$obj")
        assertFalse(obj.containsKey("reasoning_content"), "reasoning_content 为 null 时不应写出：$obj")
        assertFalse(obj.containsKey("name"), "name 为 null 时不应写出：$obj")
    }
}
