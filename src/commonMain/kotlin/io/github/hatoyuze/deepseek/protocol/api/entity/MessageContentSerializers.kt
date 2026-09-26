package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * [MessageContent] 的 wire format 序列化器。
 *
 * 官方的 `content` 有两种形态，且**没有**统一的判别字段：
 * - 纯文本 → JSON 字符串，例如 `"你好"`
 * - 内容块 → JSON 数组，例如 `[{"type":"image_url","image_url":{"url":"..."}}]`
 *
 * 因此这里按 JSON 的**形态**分派：解析时由 JSON 元素类型决定目标子类，
 * [MessageContent.Text] 编码为 JSON 字符串（含空串），[MessageContent.Parts] 编码为数组。
 *
 * 该序列化器是 `internal`：它是库内部实现细节，只通过 [MessageContent] 上的
 * `@Serializable(with = ...)` 被 kotlinx.serialization 使用。
 */
internal object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MessageContent")

    private val textSerializer = String.serializer()
    private val partsSerializer: KSerializer<List<ContentPart>> = ListSerializer(ContentPartSerializer)

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("MessageContent 只能序列化为 JSON")
        when (value) {
            is MessageContent.Text -> jsonEncoder.encodeSerializableValue(textSerializer, value.text)
            is MessageContent.Parts -> jsonEncoder.encodeSerializableValue(partsSerializer, value.parts)
        }
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("MessageContent 只能从 JSON 反序列化")
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> {
                if (!element.isString) {
                    throw SerializationException("消息 content 的字符串形态必须是 JSON 字符串，实际为 ${element.content}")
                }
                MessageContent.Text(element.content)
            }

            is JsonArray -> try {
                MessageContent.Parts(jsonDecoder.json.decodeFromJsonElement(partsSerializer, element))
            } catch (e: IllegalArgumentException) {
                // 空数组会被 Parts 的不变量拒绝：同样属于「坏 JSON」，不能以 IllegalArgumentException 逃逸
                throw SerializationException("消息 content 的内容块数组非法：${e.message}", e)
            }

            else -> throw SerializationException(
                "消息 content 必须是字符串或内容块数组，实际为 ${element::class.simpleName}",
            )
        }
    }
}

/**
 * [ContentPart] 的多态序列化器：按 `type` 判别值分派到各块的专属序列化器。
 *
 * 判别值 `image_url` / `file` 与默认多态机制（要求判别值等于子类 `@SerialName`）语义不合，
 * 因此这里显式映射；未知 `type` 抛 [SerializationException]（早失败，而不是产出半可用对象）。
 */
internal object ContentPartSerializer : KSerializer<ContentPart> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ContentPart")

    private val textSerializer = ContentPart.TextPart.serializer()
    private val imageSerializer = ImagePartSerializer
    private val fileSerializer = FilePartSerializer

    override fun serialize(encoder: Encoder, value: ContentPart) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ContentPart 只能序列化为 JSON")
        when (value) {
            is ContentPart.TextPart -> jsonEncoder.encodeJsonElement(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("text"),
                        "text" to JsonPrimitive(value.text),
                    ),
                ),
            )

            is ContentPart.ImagePart -> jsonEncoder.encodeSerializableValue(imageSerializer, value)
            is ContentPart.FilePart -> jsonEncoder.encodeSerializableValue(fileSerializer, value)
        }
    }

    override fun deserialize(decoder: Decoder): ContentPart {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ContentPart 只能从 JSON 反序列化")
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("内容块必须是 JSON 对象，实际为 ${element::class.simpleName}")
        val typeElement = obj["type"]
        if (typeElement !is JsonPrimitive || !typeElement.isString) {
            // 非字符串的 type（对象/数字/null）也是坏 JSON，不能让它以 IllegalArgumentException 逃逸
            throw SerializationException(
                "内容块的 type 必须是 JSON 字符串，实际为 ${typeElement?.let { it::class.simpleName } ?: "缺失"}",
            )
        }
        val type = typeElement.content
        // KSerializer 的契约是「要么成功，要么抛 SerializationException」：内容块的不变量
        // （url 与 file_id 互斥等）与 jsonPrimitive 的强转都会抛 IllegalArgumentException，
        // 这里统一翻译成 SerializationException，调用方 `catch (SerializationException)` 才有效
        return try {
            when (type) {
                // 判别值 `type` 不属于 TextPart 的字段：拆掉判别键再交给数据类序列化器
                "text" -> jsonDecoder.json.decodeFromJsonElement(
                    textSerializer,
                    JsonObject(obj.filterKeys { it != "type" }),
                )

                "image_url" -> jsonDecoder.json.decodeFromJsonElement(imageSerializer, obj)
                "file" -> jsonDecoder.json.decodeFromJsonElement(fileSerializer, obj)
                else -> throw SerializationException("未知的内容块 type: $type")
            }
        } catch (e: SerializationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw SerializationException("内容块（type=$type）非法：${e.message}", e)
        }
    }
}

/**
 * [ContentPart.ImagePart] 的 wire format 序列化器。
 *
 * 两种来源对应**两种不同的内容块类型**（官方 create-chat-completion 的 content array：
 * `image_url` 块只有 `url`+`detail`，`file` 块只有 `file_id`/`file_data`）：
 * - [ContentPart.ImagePart.imageUrl] → `{"type":"image_url","image_url":{"url":...,"detail":...}}`
 * - [ContentPart.ImagePart.fileId] → `{"type":"file","file_id":"file-api-..."}`
 *
 * 后者一度被错写成 `{"type":"image_url","image_url":{"file_id":...}}`，真实接口会直接拒绝：
 * `missing field url`（2026-09 实测，DeepSeek 返回 400 invalid_request_error）。
 * 判别值 `image_url` 同时是承载对象的名字，因此 `url`/`detail` 必须嵌在 `image_url` 字段内，
 * 无法用默认的多态序列化表达；`detail` 为默认值 [ImageUrlDetail.Auto] 时省略。
 */
internal object ImagePartSerializer : KSerializer<ContentPart.ImagePart> {
    override val descriptor: SerialDescriptor = ContentPart.ImagePart.serializer().descriptor

    private val detailSerializer = ImageUrlDetail.serializer()

    override fun serialize(encoder: Encoder, value: ContentPart.ImagePart) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("ImagePart 只能序列化为 JSON")
        val url = value.imageUrl
        val element = if (url != null) {
            val fields = mutableMapOf<String, JsonElement>("url" to JsonPrimitive(url))
            if (value.detail != ImageUrlDetail.Auto) {
                fields["detail"] = jsonEncoder.json.encodeToJsonElement(detailSerializer, value.detail)
            }
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("image_url"),
                    "image_url" to JsonObject(fields),
                ),
            )
        } else {
            // file_id 形态走 `file` 块：`image_url` 块不接受 file_id（`detail` 在此形态下无意义）
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive("file"),
                    "file_id" to JsonPrimitive(value.fileId.orEmpty()),
                ),
            )
        }
        jsonEncoder.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): ContentPart.ImagePart {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("ImagePart 只能从 JSON 反序列化")
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("image_url 内容块必须是 JSON 对象，实际为 ${element::class.simpleName}")
        val typeElement = obj["type"]
        val type = (typeElement as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != "image_url") {
            throw SerializationException("image_url 内容块的 type 必须是 \"image_url\"，实际为 ${type ?: "非字符串"} ")
        }
        val imageUrl = obj["image_url"] as? JsonObject
            ?: throw SerializationException("image_url 内容块缺少 image_url 对象")
        return try {
            val url = optionalString(imageUrl, "url")
            // 兼容历史/服务端可能带上的 file_id 写法；正规路径是 `file` 块（FilePartSerializer）
            val fileId = optionalString(imageUrl, "file_id")
            val detail = imageUrl["detail"]?.let {
                jsonDecoder.json.decodeFromJsonElement(detailSerializer, it)
            } ?: ImageUrlDetail.Auto
            // 官方规定 url / file_id 互斥且必须二选一；ImagePart 的构造期校验会拒绝非法组合
            ContentPart.ImagePart(imageUrl = url, fileId = fileId, detail = detail)
        } catch (e: SerializationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw SerializationException("image_url 内容块非法：${e.message}", e)
        }
    }
}

/** 取可选字符串字段：字段缺失或值为 JSON null 时返回 `null`；非字符串抛 [SerializationException] */
private fun optionalString(obj: JsonObject, key: String): String? {
    val element = obj[key] ?: return null
    if (element is JsonNull) return null
    if (element !is JsonPrimitive || !element.isString) {
        throw SerializationException("image_url 内容块的 $key 必须是 JSON 字符串")
    }
    return element.content
}

/**
 * [ContentPart.FilePart] 的 wire format 序列化器：
 * `{"type":"file","file_id":...}` 或 `{"type":"file","file_data":...,"filename":...}`。
 */
internal object FilePartSerializer : KSerializer<ContentPart.FilePart> {
    override val descriptor: SerialDescriptor = ContentPart.FilePart.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ContentPart.FilePart) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("FilePart 只能序列化为 JSON")
        val fields = mutableMapOf<String, JsonElement>("type" to JsonPrimitive("file"))
        val fileId = value.fileId
        val fileData = value.fileData
        when {
            fileId != null -> fields["file_id"] = JsonPrimitive(fileId)
            fileData != null -> {
                fields["file_data"] = JsonPrimitive(fileData)
                // filename 仅在 file_data 形态下有效
                value.filename?.let { fields["filename"] = JsonPrimitive(it) }
            }

            else -> throw SerializationException("FilePart 必须提供 fileId 或 fileData")
        }
        jsonEncoder.encodeJsonElement(JsonObject(fields))
    }

    override fun deserialize(decoder: Decoder): ContentPart.FilePart {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("FilePart 只能从 JSON 反序列化")
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("file 内容块必须是 JSON 对象，实际为 ${element::class.simpleName}")
        val typeElement = obj["type"]
        val type = (typeElement as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != "file") {
            throw SerializationException("file 内容块的 type 必须是 \"file\"，实际为 ${type ?: "非字符串"}")
        }
        val fileId = optString(obj, "file_id")
        val fileData = optString(obj, "file_data")
        if (fileId == null && fileData == null) {
            throw SerializationException("file 内容块必须提供 file_id 或 file_data")
        }
        return try {
            ContentPart.FilePart(
                fileId = fileId,
                fileData = fileData,
                filename = optString(obj, "filename"),
            )
        } catch (e: IllegalArgumentException) {
            throw SerializationException("file 内容块非法：${e.message}", e)
        }
    }

    /** 取可选字符串字段：字段缺失或值为 JSON null 时返回 `null`（而非抛类型错误） */
    private fun optString(obj: JsonObject, key: String): String? {
        val element = obj[key] ?: return null
        if (element is JsonNull) return null
        if (element !is JsonPrimitive || !element.isString) {
            throw SerializationException("file 内容块的 $key 必须是 JSON 字符串")
        }
        return element.content
    }
}
