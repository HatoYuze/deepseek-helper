package io.github.hatoyuze.deepseek.protocol.api.dsl

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.ImageUrlDetail
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.openFileSource

/**
 * 声明式构建一段对话历史（`List<Message>`）。
 *
 * ```kotlin
 * val messages = buildDeepseekMessages {
 *     Role.System says "You are a helpful assistant"
 *
 *     val image = imageOf("photos/cat.jpg")      // 本地文件：库内读成 base64 data URL
 *     Role.User says image + "这张图片里有什么？"
 *
 *     Role.Assistant says "这是一只橘猫，正躺在窗台上。"
 * }
 * ds.replaceHistory(messages)   // 或 statelessDeepseek 的 chatStream(messages)
 * ```
 *
 * ## 语义
 *
 * - `role says content` 追加一条消息，并**返回该消息**（可 `val` 接住继续加工）
 * - `content` 可以是 [String]（纯文本）或任意 [MessageContent]（含图片的内容块）
 * - [MessageContent] 之间用 `+` 组合：`imageOf(file) + "描述"`、`imageOf(a) + imageOf(b)`
 * - 顺序即数组顺序；不做任何隐式插入（system 提示词请显式写第一行）
 *
 * ## 线程模型
 *
 * 整个 DSL 是同步的、纯内存操作，唯一的 IO 是 [imageOf] 读取本地文件 —— 它发生在**调用方
 * 线程**上（本库不为调用方选调度器）。大图或在 UI 线程上构建时请自行 `withContext(Dispatchers.IO)`。
 *
 * 图片读取失败抛 [io.github.hatoyuze.deepseek.protocol.api.entity.FileSourceReadException]；
 * URL scheme 不受支持、或内联字节超过 32 MiB 时抛 [IllegalArgumentException]
 * （与 [MessageContent] 的构造期校验一致）。
 *
 * @param block 构建块，receiver 为 [DeepseekMessagesBuilder]
 * @return 构建完成的对话历史（不可变列表，可直接交给 `replaceHistory` / `chatStream(messages)`）
 */
public fun buildDeepseekMessages(block: DeepseekMessagesBuilder.() -> Unit): List<Message> =
    DeepseekMessagesBuilder().apply(block).build()

/**
 * [buildDeepseekMessages] 的构建器。
 *
 * 只在 DSL 块内可用；`role says content` 语法由 [says] 提供。
 */
public class DeepseekMessagesBuilder {
    private val messages = mutableListOf<Message>()

    /** 追加一条消息（[says] 的实现入口；也可以直接调用） */
    public fun add(message: Message) {
        messages += message
    }

    /** 返回当前已构建的历史（防御性拷贝，之后对构建器的修改不影响它） */
    public fun build(): List<Message> = messages.toList()

    /**
     * `Role.X says content`：追加一条消息并返回它。
     *
     * ```kotlin
     * Role.User says "你好"
     * Role.User says imageOf("cat.jpg") + "这是什么？"
     * ```
     */
    public infix fun Role.says(content: MessageContent): Message =
        Message(role = this, content = content).also { add(it) }

    /** `Role.X says "文本"`：等价于 [says] 的 [MessageContent] 重载 */
    public infix fun Role.says(text: String): Message = says(MessageContent.of(text))
}

/**
 * 把一张图片放进消息内容，返回可直接参与 `+` 组合的内容块。
 *
 * ```kotlin
 * Role.User says imageOf("photos/cat.jpg") + "这张图片里有什么？"
 * Role.User says imageOf("https://example.com/cat.jpg", ImageUrlDetail.Low) + "描述一下"
 * Role.User says imageOf(jpegBytes) + "这是什么格式？"
 * ```
 *
 * 三种来源的判定顺序（[source] 为 [Any]，因此故意做得保守且可预测）：
 * 1. [ByteArray]：直接作为图片字节内联（用魔数判断 MIME，识别不出时按 `image/jpeg`）
 * 2. [FileSource]（含 `FileSource.Path` / `FileSource.Bytes`）：读取后内联
 * 3. [String]：以 `http://` / `https://` / `data:` 开头视为**外链或已编码的 data URL**（原样透传），
 *    否则视为**本地文件路径**（读取后内联）
 *
 * 其他类型抛 [IllegalArgumentException]（含清晰提示），不静默失败。
 *
 * @param source 图片来源：字节数组 / [FileSource] / URL 字符串 / 本地路径字符串
 * @param detail 细节级别，默认 [ImageUrlDetail.Auto]；外链形态下由服务端使用
 * @return 只含一个图片块的内容，可直接 `+` 文本或另一张图，也可单独作为一条消息的内容
 */
public fun imageOf(source: Any, detail: ImageUrlDetail = ImageUrlDetail.Auto): MessageContent.Parts =
    MessageContent.Parts(listOf(imagePartOf(source, detail)))

/** [imageOf] 的显式来源重载：避免把常见类型交给 `Any` 的运行时判定 */
public fun imageOf(source: ByteArray): MessageContent.Parts =
    MessageContent.Parts(listOf(imagePartOf(source)))

/** [imageOf] 的显式来源重载：URL / data URL / 本地路径 */
public fun imageOf(source: String): MessageContent.Parts =
    MessageContent.Parts(listOf(imagePartOf(source)))

/**
 * [imageOf] 的显式来源重载：本库的图片来源抽象（`FileSource.Path` / `FileSource.Bytes`）。
 *
 * 需要 `use {}` 语义（例如读取后立即释放）时用这个重载，库不会替你关闭它。
 */
public fun imageOf(source: FileSource): MessageContent.Parts =
    MessageContent.Parts(listOf(imagePartOf(source)))

/**
 * 引用一张**已经通过 Files API 上传**的图片（用它的 `file_id`）。
 *
 * ```kotlin
 * val uploaded = ds.files().upload("cat.jpg", "image/jpeg")
 * Role.User says imageFileOf(uploaded.id) + "这张图里有什么？"
 * ```
 *
 * 为什么不复用 [imageOf] 的字符串重载：那里的字符串表示 **URL / 本地路径**，
 * 而 `file-api-...` 既不是 URL 也不是路径，塞进去会去读一个不存在的本地文件。
 *
 * @param fileId Files API 返回的 `file-api-...` 标识
 */
public fun imageFileOf(fileId: String): MessageContent.Parts {
    require(fileId.isNotBlank()) { "fileId 不能为空白字符串" }
    return MessageContent.Parts(listOf(ContentPart.ImagePart(fileId = fileId)))
}

/**
 * 把任意来源解析成一个图片内容块。
 *
 * 与 [imageOf] 的区别只是「返回块本身」还是「返回只含该块的内容」，需要手动插入到
 * [MessageContent.Parts] 列表时用这个。
 */
public fun imagePartOf(source: Any, detail: ImageUrlDetail = ImageUrlDetail.Auto): ContentPart.ImagePart =
    when (source) {
        is ByteArray -> imagePartOf(source, detail)
        is FileSource -> imagePartOf(source, detail)
        is String -> imagePartOf(source, detail)
        else -> throw IllegalArgumentException(
            "不支持的图片来源：${source::class.simpleName}；" +
                "请传 ByteArray、FileSource、URL/路径字符串，" +
                "或平台扩展（JVM/Android：File、InputStream、java.net.URI）",
        )
    }

/** [imagePartOf] 的字节重载：内联为 base64 data URL，MIME 由魔数判断 */
public fun imagePartOf(source: ByteArray, detail: ImageUrlDetail = ImageUrlDetail.Auto): ContentPart.ImagePart {
    require(source.isNotEmpty()) { "图片字节不能为空" }
    val mime = sniffImageMime(source)
    return ContentPart.ImagePart(imageUrl = MessageContent.imageDataUrl(mime, source), detail = detail)
}

/**
 * [imagePartOf] 的字符串重载：`http(s)` / `data:` 视为 URL 原样透传，其余视为本地路径并内联。
 */
public fun imagePartOf(source: String, detail: ImageUrlDetail = ImageUrlDetail.Auto): ContentPart.ImagePart {
    require(source.isNotBlank()) { "图片地址不能为空白字符串" }
    val lower = source.lowercase()
    val isRemote = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("data:")
    if (isRemote) return ContentPart.ImagePart(imageUrl = source, detail = detail)

    val bytes = openFileSource(source).use { it.readBytes() }
    return imagePartOf(bytes, detail) // 本地路径内联：MIME 同样由内容判断
}

/** [imagePartOf] 的 [FileSource] 重载：库不关闭传入的 source，由调用方负责 */
public fun imagePartOf(source: FileSource, detail: ImageUrlDetail = ImageUrlDetail.Auto): ContentPart.ImagePart =
    imagePartOf(source.readBytes(), detail)

/**
 * `content + content`：把两段内容拼接成一段（顺序即拼接顺序）。
 *
 * ```kotlin
 * imageOf("cat.jpg") + "这是什么？"        // 图片 + 文本
 * imageOf("a.jpg") + imageOf("b.jpg")      // 两张图
 * ```
 *
 * 只提供「内容 + 内容」这一侧：`"文本" + imageOf(...)` 这样的写法会被 Kotlin 解析到
 * stdlib 的 `String.plus(Any?)`（成员函数优先于扩展函数），得到的是一个字符串而不是内容块 ——
 * 与其提供一个永远不会被调用的扩展，不如让这种写法直接编译失败，改用
 * `MessageContent.textPart("文本") + imageOf(...)`。
 */
public operator fun MessageContent.plus(other: MessageContent): MessageContent.Parts =
    MessageContent.Parts(partsOf(this) + partsOf(other))

/** `content + "文本"`：追加一个文本块 */
public operator fun MessageContent.plus(text: String): MessageContent.Parts =
    MessageContent.Parts(partsOf(this) + ContentPart.TextPart(text))

/** `块 + 内容`：`MessageContent.textPart("先看图：") + imageOf(...)` */
public operator fun ContentPart.plus(content: MessageContent): MessageContent.Parts =
    MessageContent.Parts(listOf(this) + partsOf(content))

/** `块 + "文本"`：`ContentPart.TextPart("a") + "b"` */
public operator fun ContentPart.plus(text: String): MessageContent.Parts =
    MessageContent.Parts(listOf(this, ContentPart.TextPart(text)))

/** 取内容的块列表（纯文本视为单个文本块） */
internal fun partsOf(content: MessageContent): List<ContentPart> = when (content) {
    is MessageContent.Text -> listOf(ContentPart.TextPart(content.text))
    is MessageContent.Parts -> content.parts
}

/**
 * 用图片魔数判断 MIME —— 官方按**文件内容**判断格式（不看文件名或声明的类型），
 * 这里保持一致，避免把 PNG 标成 `image/jpeg`。
 *
 * 识别不出时返回 `image/jpeg`（服务端仍会按内容自行判定，这个值只是提示）。
 */
internal fun sniffImageMime(bytes: ByteArray): String = when {
    bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
        "image/jpeg"

    bytes.size >= 8 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() &&
        bytes[3] == 'G'.code.toByte() -> "image/png"

    bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() -> "image/gif"

    // RIFF....WEBP
    bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> "image/webp"

    else -> "image/jpeg"
}
