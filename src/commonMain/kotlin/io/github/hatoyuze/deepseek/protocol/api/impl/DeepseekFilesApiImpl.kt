package io.github.hatoyuze.deepseek.protocol.api.impl

import io.github.hatoyuze.deepseek.protocol.api.DeepseekFiles
import io.github.hatoyuze.deepseek.protocol.api.entity.DeepseekFile
import io.github.hatoyuze.deepseek.protocol.api.entity.FileDeletion
import io.github.hatoyuze.deepseek.protocol.api.entity.FileList
import io.github.hatoyuze.deepseek.protocol.api.entity.FileOrder
import io.github.hatoyuze.deepseek.protocol.api.entity.FilePurpose
import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.UploadOptions
import io.github.hatoyuze.deepseek.protocol.api.entity.openFileSource
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.net.MultipartFilePart
import io.github.hatoyuze.deepseek.protocol.net.Network
import io.github.hatoyuze.deepseek.protocol.net.collectHeaders
import io.github.hatoyuze.deepseek.protocol.net.HttpHookRegistry
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart

/**
 * Files API 的网络实现。
 *
 * ## 线程模型
 *
 * 无可变共享状态：每次调用只依赖构造期注入的 [Network] 与调用参数，因此同一个实例可以
 * 安全并发使用；`suspend` 边界继承调用方的调度上下文，库不切换调度器。取消随调用方
 * 协程传播（Ktor 会中止上传/下载）。
 *
 * ## 上传配额（官方文档）
 *
 * 单文件 ≤ 64 MiB、文件名 ≤ 512 字符、单用户 25 GiB / 10000 个文件、有效期 1 小时到 30 天。
 * 库侧只校验**本地能判定**的约束（有效期区间、分页 limit、必填标识），其余交给服务端 4xx
 * 并经 [checkHttpStatus] 转成带语义的异常。
 */
internal class DeepseekFilesApiImpl(
    apiKey: String,
    pool: DeepseekHttpClientPool,
    baseUrl: String = DEFAULT_BASE_URL,
) : DeepseekFiles {

    private val net = Network(baseUrl, apiKey, pool)

    override suspend fun upload(
        source: FileSource,
        mimeType: String,
        filename: String?,
        options: UploadOptions,
    ): DeepseekFile {
        filename?.let { requireValidFilename(it) }
        // 字节读取发生在调用方上下文（FileSource 契约：调用方决定在哪个调度器上读）
        val bytes = source.readBytes()
        val fields = buildList {
            add("purpose" to FilePurpose.UserData.wireName)
            options.expiresAfterSeconds?.let {
                // 官方的点号命名（非嵌套对象）：expires_after[anchor] / expires_after[seconds]
                add("expires_after[anchor]" to "created_at")
                add("expires_after[seconds]" to it.toString())
            }
        }
        val part = MultipartFilePart(
            name = "file",
            bytes = bytes,
            filename = filename,
            contentType = mimeType.toContentTypeOrNull(),
        )
        // hook 只看到摘要：文件名可以进日志；字节与本地路径不行，一律不进这个字符串
        val summary = "multipart/form-data (file=${filename?.sanitizedForLog() ?: "<unnamed>"}, " +
            "${bytes.size} bytes, purpose=user_data)"

        val response = net.executeMultipart(
            url = "/files",
            method = HttpMethod.Post.value,
            formFields = fields,
            files = listOf(part),
            bodySummary = summary,
        )
        checkHttpStatus(response)
        return json.decodeFromString(response.bodyAsText())
    }

    override suspend fun upload(
        path: String,
        mimeType: String,
        filename: String?,
        options: UploadOptions,
    ): DeepseekFile = openFileSource(path).use { source ->
        upload(source, mimeType, filename ?: defaultUploadFilename(path), options)
    }

    override suspend fun retrieve(fileId: String): DeepseekFile =
        net.call("/files/${fileId.requireNotBlank("fileId").encodeURLPathPart()}")

    override suspend fun list(
        after: String?,
        limit: Int?,
        order: FileOrder,
        purpose: FilePurpose,
    ): FileList {
        val effectiveLimit = limit?.also {
            require(it in DeepseekFiles.MIN_LIST_LIMIT..DeepseekFiles.MAX_LIST_LIMIT) {
                "limit 必须在 ${DeepseekFiles.MIN_LIST_LIMIT}..${DeepseekFiles.MAX_LIST_LIMIT} 之间，实际为 $it"
            }
        }
        val cursor = after?.also {
            require(it.isNotBlank()) { "after 游标不能为空白字符串（不翻页请传 null）" }
        }
        val query = buildList {
            cursor?.let { add("after=" + it.encodeURLParameter()) }
            effectiveLimit?.let { add("limit=$it") }
            add("order=" + order.wireName)
            add("purpose=" + purpose.wireName)
        }.joinToString("&")
        return net.call("/files?$query")
    }

    override suspend fun delete(fileId: String): FileDeletion =
        net.call("/files/${fileId.requireNotBlank("fileId").encodeURLPathPart()}", HttpMethod.Delete)
}

/** 形如 `image/png` 的 MIME 类型字符串转 [ContentType]；无法解析时返回 `null`（不阻断上传） */
internal fun String.toContentTypeOrNull(): ContentType? =
    runCatching { ContentType.parse(this) }.getOrNull()

/** 校验必填标识参数并返回原值（fail-fast，避免拼出 `/files/` 这类无意义路由） */
internal fun String.requireNotBlank(name: String): String = also {
    require(it.isNotBlank()) { "$name 不能为空白字符串" }
}

/**
 * 文件名上限（官方文档）。
 */
internal const val MAX_FILENAME_LENGTH: Int = 512

/**
 * 校验文件名。
 *
 * 文件名会被写进 multipart 的 `Content-Disposition`（**恰好** header 值里），因此这里把
 * 文档上限与「控制字符 / 引号 / 反斜杠 / 路径分隔符」一并挡在库外：
 * - 控制字符与换行：CRLF 注入（header 注入 / 日志伪造）的原材料
 * - 引号与反斜杠：破坏 `filename="..."` 的引用结构
 * - 路径分隔符：把本地目录结构透给服务端（要传路径时请用 `upload(path, ...)` 的自动提取）
 *
 * @throws IllegalArgumentException 违反上述任一约束时
 */
internal fun requireValidFilename(filename: String) {
    require(filename.isNotBlank()) { "filename 不能为空白字符串" }
    require(filename.length <= MAX_FILENAME_LENGTH) {
        "filename 长度不能超过 $MAX_FILENAME_LENGTH 个字符，实际为 ${filename.length}"
    }
    // 白名单而非黑名单：只允许无需任何转义就能安全放进 multipart 参数与日志的字符。
    // 这样既挡住 CRLF 注入 / 引号破坏 / 路径分隔符外泄，也不必在拼接处做转义 ——
    // 而「转义没写对」正是这类注入最常见的成因。中文等非 ASCII 字母/数字照常放行。
    require(filename.all { it.isLetterOrDigit() || it in ALLOWED_FILENAME_PUNCTUATION }) {
        "filename 只能包含字母、数字、空格与 ${ALLOWED_FILENAME_PUNCTUATION.map { c -> "'$c'" }.joinToString(" ")}；" +
            // 不回显原值：它可能正是那段被注入的内容
            "（已拒绝，长度 ${filename.length}）"
    }
}

/** 文件名允许的标点（不含 `"`、`\`、`/`、控制字符等需要转义或会被解析为路径的字符） */
private const val ALLOWED_FILENAME_PUNCTUATION: String = " ._-()+[]!~@#&,;="


/**
 * 取路径的最后一段，同时兼容 `/` 与 Windows 的 `\`。
 *
 * 只用 `substringAfterLast('/')` 会让 Windows 路径原样成为文件名（顺带把用户名与目录结构
 * 透给服务端），因此两个分隔符都要处理。
 */
internal fun String.baseName(): String? {
    if ('/' in this) {
        // POSIX 形态（也可能是混合分隔符）：以 `/` 为准。`\\` 在 POSIX 上是合法文件名字符，
        // 这里不能再切一次，否则 `photo\cat.jpg` 这类真实文件名会被悄悄改掉。
        return substringAfterLast('/').takeIf { it.isNotEmpty() }
    }
    // 没有 `/`：只有看起来像 Windows 路径（盘符开头）时才把 `\\` 当分隔符
    val looksLikeWindowsPath = length >= 2 && this[1] == ':'
    val base = if (looksLikeWindowsPath) substringAfterLast('\\') else this
    return base.takeIf { it.isNotEmpty() }
}

/**
 * `upload(path, ...)` 未显式给文件名时推导出的默认文件名（见 [baseName]）。
 *
 * 单独抽出来是为了能被平台无关的测试直接覆盖：Windows 路径的分隔符处理很容易回归。
 */
internal fun defaultUploadFilename(path: String): String? = path.baseName()

/**
 * 把任意字符串变成适合放进日志的一行文本：换行/制表/控制字符替换为空格并限长。
 *
 * hook 收到的摘要按约定会被写进日志，因此这里不能直接拼原值。
 */
internal fun String.sanitizedForLog(): String {
    val flattened = map { if (it < ' ' || it == '\u007F') ' ' else it }.joinToString("")
    return if (flattened.length <= 128) flattened else flattened.take(128) + "…"
}
