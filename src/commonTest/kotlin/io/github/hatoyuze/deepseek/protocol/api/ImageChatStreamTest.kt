package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientFactory
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 客户端层的图片输入：`chatStream` 的内容形态重载、历史写入、以及角色限制的 fail-fast。
 *
 * 关注的不是 wire format（那由 [MessageContentSerializationTest] 覆盖），而是
 * 「哪种重载把什么写进了历史、什么时候会立刻失败」。
 */
class ImageChatStreamTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun textOf(message: Message): String? = message.content?.asText()

    @Test
    fun `string overload records plain text in history`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = statefulDeepseek(GatedBackend { messages ->
            seen += messages
            kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1))
        })

        ds.chatStream("hello").toList()

        val user = ds.messages.last { it.role == Role.User }
        assertEquals("hello", textOf(user))
        assertEquals("hello", textOf(seen.single().last()))
    }

    @Test
    fun `message content overload records content blocks in history`() = runTest {
        val ds = statefulDeepseek(GatedBackend { kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1)) })
        val content = MessageContent.Parts(
            listOf(
                MessageContent.textPart("what is this?"),
                MessageContent.imageFile("file-api-1").parts.single(),
            ),
        )

        ds.chatStream(content).toList()

        val user = ds.messages.last { it.role == Role.User }
        assertEquals(content, user.content, "历史里的 user 消息内容应与传入内容块一致")
        assertEquals("what is this?", textOf(user), "asText 只取文本块")
    }

    @Test
    fun `parts overload records content blocks in history`() = runTest {
        val ds = statefulDeepseek(GatedBackend { kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1)) })
        val parts: List<ContentPart> = listOf(
            MessageContent.textPart("看图"),
            ContentPart.ImagePart(imageUrl = "https://example.com/cat.jpg"),
        )

        ds.chatStream(parts).toList()

        val user = ds.messages.last { it.role == Role.User }
        assertEquals(MessageContent.Parts(parts), user.content)
    }

    @Test
    fun `stateless string overload keeps the same contract`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = StatelessDeepseek(
            "test-key",
            core = testCore(
                singleSession = false,
                backend = GatedBackend { messages ->
                    seen += messages
                    kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1))
                },
                prompt = "sys",
            ),
        )

        ds.chatStream("hi").toList()

        assertEquals("sys", textOf(seen.single().first()))
        assertEquals("hi", textOf(seen.single().last()))
    }

    @Test
    fun `stateless content overload sends image blocks`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = StatelessDeepseek(
            "test-key",
            core = testCore(
                singleSession = false,
                backend = GatedBackend { messages ->
                    seen += messages
                    kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1))
                },
            ),
        )

        ds.chatStream(MessageContent.imageFile("file-api-1")).toList()

        val user = seen.single().last()
        assertIs<MessageContent.Parts>(user.content)
    }

    @Test
    fun `stateless message list overload accepts messages with images`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = StatelessDeepseek(
            "test-key",
            core = testCore(
                singleSession = false,
                backend = GatedBackend { messages ->
                    seen += messages
                    kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1))
                },
                prompt = null,
            ),
        )
        val history = listOf(
            Message(Role.User, MessageContent.of("first")),
            Message(Role.Assistant, MessageContent.of("ok")),
            Message(
                Role.User,
                MessageContent.Parts(
                    listOf(MessageContent.textPart("and this?"), MessageContent.imageFile("file-api-2").parts.single()),
                ),
            ),
        )

        ds.chatStream(history).toList()

        assertEquals(history, seen.single())
    }

    /**
     * 图片只能出现在 user 消息里：把带图消息塞进历史后，必须在**发出请求之前**失败。
     *
     * 用真实后端 + MockEngine 断言「一次 HTTP 请求都没有发出」——只断言异常类型的话，
     * 一个「先发请求再校验」的实现也能蒙混过关。
     */
    @Test
    fun `non user message with images fails before hitting the network`() = runTest {
        val requests = mutableListOf<String>()
        val pool = DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory {
                HttpClient(
                    MockEngine { request ->
                        requests.add(request.url.toString())
                        respond(
                            content = "",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                        )
                    },
                )
            },
        )
        val ds = StatelessDeepseek("sk-test", sharingPool = pool)
        val bad = listOf(
            Message(Role.User, MessageContent.of("fine")),
            Message(Role.Assistant, MessageContent.imageFile("file-api-1")),
        )

        assertFailsWith<IllegalArgumentException> { ds.chatStream(bad).toList() }

        assertEquals(0, requests.size, "非法角色不应发出任何 HTTP 请求：$requests")
    }

    @Test
    fun `findUserMessageIndex matches text inside content blocks`() = runTest {
        val ds = statefulDeepseek(GatedBackend { kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1)) })
        ds.replaceHistory(
            listOf(
                Message(
                    Role.User,
                    MessageContent.Parts(
                        listOf(MessageContent.textPart("看图"), MessageContent.imageFile("file-api-1").parts.single()),
                    ),
                ),
                Message(Role.Assistant, MessageContent.of("好的")),
            ),
        )

        assertEquals(0, ds.findUserMessageIndex("看图"))
        assertEquals(-1, ds.findUserMessageIndex("不存在的文本"), "非文本内容不应误命中")
    }

    @Test
    fun `content blocks survive a serialization round trip inside the request body`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = statefulDeepseek(GatedBackend { messages ->
            seen += messages
            kotlinx.coroutines.flow.flowOf(ChatChunk.Done(1, 1, 1))
        })

        ds.chatStream(
            MessageContent.of(
                MessageContent.textPart("hi"),
                ContentPart.ImagePart(imageUrl = "data:image/png;base64,QQ=="),
            ),
        ).toList()

        val encoded = json.encodeToString(
            kotlinx.serialization.serializer<List<Message>>(),
            seen.single(),
        )
        val blocks = json.parseToJsonElement(encoded).jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,QQ==",
            blocks[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }
}
