package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * 一条消息的内容。
 *
 * 对话补全（`/chat/completions`）与 Responses（`/responses`）都允许 `content` 有两种形态：
 * 纯文本字符串，或内容块（content part）数组 —— 后者才能携带图片。本类型就是这两者的
 * 密封建模，wire format 与官方一致：
 *
 * ```jsonc
 * // Text
 * "这张图片里有什么？"
 * // Parts
 * [
 *   {"type": "text", "text": "这张图片里有什么？"},
 *   {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}},
 *   {"type": "file", "file_id": "file-api-xxxxxxxxxxxxxxxx"}
 * ]
 * ```
 *
 * ## 构造
 *
 * 纯文本有隐式转换，历史代码不必改动：
 *
 * ```kotlin
 * val plain = Message(Role.User, MessageContent.of("你好"))          // 等价于 MessageContent.Text("你好")
 * val text = MessageContent.of("你好")            // 显式写法
 * ```
 *
 * 带图片时用工厂函数构造内容块列表：
 *
 * ```kotlin
 * val ask = MessageContent.of(
 *     MessageContent.textPart("这张图片里有什么？"),
 *     MessageContent.imageDataUrl("image/jpeg", jpegBytes), // base64 内联（≤ 32 MiB）
 * )
 * ds.chatStream(ask.parts).collect { ... }
 * ```
 *
 * ## 读取
 *
 * 用 [asText] 取纯文本，不要用强制转换（内容块消息可能没有文本块）：
 *
 * ```kotlin
 * val question = message.content?.asText()
 * ```
 *
 * ## 限制（官方文档，库不代为校验）
 *
 * - 图片只能出现在 **user** 消息中；system / assistant 消息携带图片会被服务端以 `400` 拒绝。
 * - 支持格式：JPEG、PNG、GIF、WebP（由文件内容判定，不看文件名/声明的 MIME）。
 * - 单图上限：base64 内联或外部 URL ≤ 32 MiB；Files API `file_id` ≤ 64 MiB。
 * - 请求体上限 48 MiB（内联的 base64 图片计入其中），单请求最多 600 张图片。
 * - 外部图片 URL 最长 8192 字符，且需在 60 秒内可被服务端下载完成。
 * - 体积较大或在多个请求中复用同一张图片时，优先用 Files API（见 `ChatClient.files`）：
 *   `file_id` 不受 32 MiB 单图限制，也不会在每轮请求里重复内联编码。
 *
 * @see ContentPart
 * @see Message
 */
@Serializable(with = MessageContentSerializer::class)
public sealed interface MessageContent {
    /** 纯文本内容，wire format 为 JSON 字符串 */
    public data class Text(public val text: String) : MessageContent

    /**
     * 内容块列表，wire format 为 JSON 数组（可携带图片）。
     *
     * 内容块自身按引用持有（与 `Message` 的浅拷贝语义一致）；**列表**不拷贝，
     * 因此构造之后不要再修改传入的那个可变列表 —— 需要隔离时用 [MessageContent.of]，
     * 它会做一次防御性拷贝。
     */
    public data class Parts(public val parts: List<ContentPart>) : MessageContent {
        init {
            require(parts.isNotEmpty()) {
                "MessageContent.Parts 至少需要一个内容块；纯文本消息请使用 MessageContent.Text"
            }
        }
    }

    /**
     * 取出纯文本内容；内容块中没有文本块时返回 `null`。
     *
     * 多个文本块会按顺序拼接（库不做任何规范化，保留调用方的原始文本）。
     */
    public fun asText(): String? = when (this) {
        is Text -> text
        is Parts -> {
            // 单趟扫描、不建中间集合：本方法在每次请求的每条消息上都会被调用
            // （findUserMessageIndex / Responses 的 input 转换），这里避免多余的列表分配
            var single: String? = null
            val joined = StringBuilder()
            for (part in parts) {
                if (part !is ContentPart.TextPart) continue
                if (single == null && joined.isEmpty()) {
                    single = part.text
                    continue
                }
                if (joined.isEmpty()) {
                    joined.append(single)
                    single = null
                }
                joined.append(part.text)
            }
            (if (joined.isNotEmpty()) joined.toString() else single)?.takeIf { it.isNotEmpty() }
        }
    }

    public companion object {
        /**
         * 把字符串转换为 [Text]，供 `Message` 的 `content` 参数隐式使用。
         *
         * 有这个转换，`Message(Role.User, MessageContent.of("你好"))` 这类既有代码无需改动即可继续编译。
         */
        public operator fun invoke(text: String): Text = Text(text)

        /** 纯文本内容 */
        public fun of(text: String): Text = Text(text)

        /** 内容块列表 */
        public fun of(vararg parts: ContentPart): Parts = Parts(parts.toList())

        /**
         * 内容块列表。
         *
         * 会做一次防御性拷贝（内容块本身按引用持有）：传入的列表之后被改动不会影响本实例。
         */
        public fun of(parts: List<ContentPart>): Parts = Parts(parts.toList())

        /** 单个文本块 */
        public fun textPart(text: String): ContentPart.TextPart = ContentPart.TextPart(text)

        /**
         * 引用一张图片。
         *
         * @param url 图片的 `http(s)` URL（官方上限 8192 字符）或 base64 data URL；
         *   其他 scheme（含 `file:`）会被拒绝 —— 服务端只接受这两种形态，
         *   本地文件请先读成字节走 [imageDataUrl]，或上传后用 [imageFile]
         * @param detail 细节级别，默认 [ImageUrlDetail.Auto]
         * @throws IllegalArgumentException [url] 为空白、scheme 不受支持，或 http(s) URL 超过
         *   [MAX_IMAGE_URL_LENGTH] 字符时
         */
        public fun image(url: String, detail: ImageUrlDetail = ImageUrlDetail.Auto): Parts =
            Parts(listOf(ContentPart.ImagePart(imageUrl = requireValidImageUrl(url), detail = detail)))

        /**
         * 引用一张已经通过 Files API 上传的图片，一次上传可在多次请求中复用。
         *
         * `detail` 在 `file_id` 形态下被服务端忽略，因此本工厂不提供该参数。
         *
         * @param fileId Files API 返回的 `file-api-...` 标识
         */
        public fun imageFile(fileId: String): Parts =
            Parts(listOf(ContentPart.ImagePart(fileId = fileId)))

        /**
         * 以内联 base64 data URL 携带图片（等价于 [ContentPart.FilePart] 的 `file_data` 形态）。
         *
         * 内联数据计入 48 MiB 请求体上限，体量较大时请改用 [imageFile]。
         */
        public fun fileData(dataUrl: String, filename: String? = null): Parts =
            Parts(listOf(ContentPart.FilePart(fileData = dataUrl, filename = filename)))

        /**
         * 把图片字节编码为 base64 data URL（`data:<mime>;base64,<...>`）。
         *
         * 纯 common 实现（`kotlin.io.encoding.Base64`），各平台都可用；`mime` 需形如
         * `image/png`。返回的字符串可直接交给 [image]。
         *
         * **内存提示**：编码后的字符串约为原字节的 4/3，且内联数据会留在对话历史里，
         * 在工具调用循环中会被**重复序列化进每一轮请求**（一轮可能上千倍于原图的内存抖动）。
         * 因此：大图、或需要在多轮请求中复用的图，请用 `ChatClient.files()` 上传后以
         * [imageFile] 引用；确实要内联时，请在请求之间复用同一个 [MessageContent] 实例，
         * 不要在循环里反复调用本函数。
         *
         * @throws IllegalArgumentException [bytes] 超过 [MAX_INLINE_IMAGE_BYTES] 时（早失败，
         *   而不是把一次注定 400 的请求发出去）
         */
        public fun imageDataUrl(mime: String, bytes: ByteArray): String = dataUrl(mime, bytes)

        /**
         * 把任意字节编码为 base64 data URL（`data:<mime>;base64,<...>`）。
         *
         * @throws IllegalArgumentException [bytes] 超过 [MAX_INLINE_IMAGE_BYTES] 时
         */
        public fun dataUrl(mime: String, bytes: ByteArray): String {
            require(bytes.size <= MAX_INLINE_IMAGE_BYTES) {
                "内联图片不能超过 ${MAX_INLINE_IMAGE_BYTES / (1024 * 1024)} MiB（官方单图上限），" +
                    "实际为 ${bytes.size} 字节；更大或需要复用的图片请用 files().upload(...) + imageFile(fileId)"
            }
            return "data:$mime;base64,${Base64.Default.encode(bytes)}"
        }

        /** 内联图片字节上限：32 MiB（官方对 base64 / 外部 URL 单图的限制） */
        public const val MAX_INLINE_IMAGE_BYTES: Int = 32 * 1024 * 1024

        /** 外部图片 URL 长度上限：8192 字符（官方限制） */
        public const val MAX_IMAGE_URL_LENGTH: Int = 8192
    }
}

/**
 * 图片的处理细节级别，对应官方 `image_url.detail`（[ContentPart.ImagePart]）。
 *
 * | 取值 | 行为 |
 * |---|---|
 * | [Low] | 推理前缩放到 512×512，更快、更省 token |
 * | [High] | 保留原图（为兼容性提供，等价于 [Original]） |
 * | [Original] | 保留原图 |
 * | [Auto] | 自动选择，当前等价于 [Original] |
 *
 * 通过 Files API 的 `file_id` 引用图片时该字段被服务端忽略。
 */
@Serializable
public enum class ImageUrlDetail {
    @SerialName("low") Low,
    @SerialName("high") High,
    @SerialName("original") Original,
    @SerialName("auto") Auto,
}

/**
 * 消息内容中的一个内容块。
 *
 * 官方目前定义三类块：文本、图片、文件（图片的第三种传入方式）。
 * 块级不变量在**构造期**校验（fail-fast），不会把非法组合留给服务端的 `400`。
 *
 * @see MessageContent
 */
@Serializable(with = ContentPartSerializer::class)
public sealed interface ContentPart {
    /** 文本块，wire format `{"type":"text","text":"..."}` */
    @Serializable
    public data class TextPart(public val text: String) : ContentPart

    /**
     * 图片块，wire format `{"type":"image_url","image_url":{"url":...,"detail":...}}`。
     *
     * [imageUrl] 与 [fileId] **互斥且必须二选一**（官方：都不传或都传均返回 `400`）：
     * - [imageUrl]：`http(s)` 链接（≤ 8192 字符）或 base64 data URL
     * - [fileId]：Files API 上传后返回的 `file-api-...` 标识
     *
     * @property imageUrl 图片 URL 或 base64 data URL；与 [fileId] 互斥
     * @property fileId Files API 文件标识；与 [imageUrl] 互斥
     * @property detail 细节级别，仅在 [imageUrl] 形态下生效
     */
    @Serializable
    public data class ImagePart(
        public val imageUrl: String? = null,
        public val fileId: String? = null,
        public val detail: ImageUrlDetail = ImageUrlDetail.Auto,
    ) : ContentPart {
        init {
            require(imageUrl != null || fileId != null) {
                "ImagePart 必须提供 imageUrl 或 fileId（官方：input_image must have image_url or file_id）"
            }
            require(imageUrl == null || fileId == null) {
                "ImagePart 的 imageUrl 与 fileId 互斥（官方：cannot have both image_url and file_id）"
            }
        }
    }

    /**
     * 文件块，wire format `{"type":"file","file_id":...}` 或
     * `{"type":"file","file_data":...,"filename":...}`。
     *
     * [fileId] 与 [fileData] **互斥且必须二选一**（官方：二者互斥，都不传返回 `400`）：
     * - [fileId]：Files API 上传后返回的标识（图片 ≤ 64 MiB）
     * - [fileData]：base64 data URL 内联（计入 48 MiB 请求体上限）
     *
     * @property fileId Files API 文件标识；与 [fileData] 互斥
     * @property fileData 内联的 base64 data URL；与 [fileId] 互斥
     * @property filename 可选文件名，**仅在 [fileData] 形态下有效**（服务端忽略 `file_id` 形态的该字段）
     */
    @Serializable
    public data class FilePart(
        public val fileId: String? = null,
        public val fileData: String? = null,
        public val filename: String? = null,
    ) : ContentPart {
        init {
            require(fileId != null || fileData != null) {
                "FilePart 必须提供 fileId 或 fileData（二者互斥，都不传会被服务端拒绝）"
            }
            require(fileId == null || fileData == null) {
                "FilePart 的 fileId 与 fileData 互斥"
            }
        }
    }
}

/**
 * 校验 `image_url` 形态的图片地址。
 *
 * 服务端只接受两种形态：可公开访问的 `http(s)` 链接，或 base64 data URL。本地路径、
 * `file://`、`content://` 之类既不会被服务端取到，也不该出现在请求里，因此在这里拒绝。
 *
 * @throws IllegalArgumentException 空白、scheme 不受支持，或 http(s) URL 超过
 *   [MessageContent.MAX_IMAGE_URL_LENGTH] 字符时
 */
internal fun requireValidImageUrl(url: String): String {
    require(url.isNotBlank()) { "imageUrl 不能为空白字符串" }
    val lower = url.lowercase()
    val dataUrl = lower.startsWith("data:")
    require(dataUrl || lower.startsWith("http://") || lower.startsWith("https://")) {
        "图片 URL 必须是 http(s) 链接或 data: URL（官方只支持这两种形态）；" +
            "本地文件请改用 MessageContent.imageDataUrl(...) 或 files().upload(...) + MessageContent.imageFile(fileId)"
    }
    require(dataUrl || url.length <= MessageContent.MAX_IMAGE_URL_LENGTH) {
        "外部图片 URL 不能超过 ${MessageContent.MAX_IMAGE_URL_LENGTH} 个字符（官方限制），实际为 ${url.length}；" +
            "超长链接请改用 base64 data URL 或 Files API"
    }
    return url
}

/**
 * 返回内容块的文本视图：`null` 表示「无文本内容」。
 *
 * - [ContentPart.TextPart] → 其文本
 * - 图片块 / 文件块 → `null`（它们没有可用的文本表示）
 */
public fun ContentPart.textOrNull(): String? = when (this) {
    is ContentPart.TextPart -> text
    is ContentPart.ImagePart, is ContentPart.FilePart -> null
}
