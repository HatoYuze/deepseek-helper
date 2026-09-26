package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.DeepseekFile
import io.github.hatoyuze.deepseek.protocol.api.entity.FileDeletion
import io.github.hatoyuze.deepseek.protocol.api.entity.FileList
import io.github.hatoyuze.deepseek.protocol.api.entity.FileOrder
import io.github.hatoyuze.deepseek.protocol.api.entity.FilePurpose
import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.UploadOptions

/**
 * Files API：上传图片一次，之后在请求里用 `file_id` 反复引用。
 *
 * 通过 [ChatClient.files] 取得实例（[Deepseek] / [StatelessDeepseek] 都用同一个实现）：
 *
 * ```kotlin
 * val ds = Deepseek("sk-xxx")
 * val uploaded = ds.files().upload("cat.jpg", "image/jpeg")   // file-api-xxxxxxxxxxxxxxxx
 *
 * // 之后任何一次请求都可以引用它，不需要重复上传
 * ds.chatStream(MessageContent.imageFile(uploaded.id).parts).collect { ... }
 * ```
 *
 * ## 与内联图片的分工
 *
 * | | 内联（base64 / 外链） | Files API |
 * |---|---|---|
 * | 体积上限 | 单图 32 MiB，请求体 48 MiB | 单图 64 MiB |
 * | 复用 | 每次请求都要重新传 | 上传一次，多请求复用 |
 * | 存储配额 | — | 单用户 25 GiB / 10000 个文件 |
 *
 * ## 线程模型与取消
 *
 * 所有方法都是 `suspend`，取消随调用方协程传播（底层 Ktor 请求会被中止）；实现不持有
 * 可变共享状态，因此同一个实例可以被并发调用。[upload] 的重载会 `use {}` 关闭 [FileSource]，
 * 取消或异常时同样保证释放。
 *
 * 两点务必注意：
 * - **`cancelStream()` 不影响文件操作**：它取消的是对话流（[Deepseek.chatStream] 等）。
 *   要中止上传/查询，请取消调用这些方法的那个协程
 * - **读取本地文件是同步的、不可中断的**：[FileSource.readBytes] 是非 suspend 成员，
 *   在 [upload] 里于**调用方上下文**执行。也就是说，取消要等到读完之后才会被观察到，
 *   而且 `viewModelScope.launch { ds.files().upload(path, "image/jpeg") }` 会在主线程上
 *   同步读取整个文件（最大 64 MiB），足以造成卡顿甚至 ANR。请显式切到 IO 调度器：
 *
 *   ```kotlin
 *   viewModelScope.launch {
 *       val uploaded = withContext(Dispatchers.IO) {
 *           ds.files().upload(path, "image/jpeg")
 *       }
 *   }
 *   ```
 *
 *   并发上传时每个请求都会在堆上持有一份完整图片字节，请用 `Semaphore` 之类的原语自行限流：
 *   库不限制并发数，也不替调用方选择调度器。
 *
 * @see ChatClient.files
 * @see DeepseekFile
 */
public interface DeepseekFiles {
    /**
     * 上传一个文件（`POST /files`，`multipart/form-data`）。
     *
     * @param source 图片来源；调用方负责在合适的时机关闭（或用 `use {}`）。本方法**不会**关闭它
     * @param mimeType 图片 MIME 类型，需形如 `image/png`；仅作为 multipart 的 `Content-Type`
     *   提示，服务端按**文件内容**判定真实格式，因此这里不做内容校验
     * @param filename 文件名（≤ 512 字符）；为 `null` 时不发送 `filename` 表单字段，
     *   由服务端决定（可能显示为默认名）
     * @param options 上传选项（有效期）；默认永久有效
     * @return 已存储的文件对象，其 [DeepseekFile.id] 可用于后续请求
     *
     * @throws IllegalArgumentException 文件名违反约束时（长度 / 控制字符 / 引号 / 反斜杠 /
     *   路径分隔符，见类文档）
     */
    public suspend fun upload(
        source: FileSource,
        mimeType: String,
        filename: String? = null,
        options: UploadOptions = UploadOptions(),
    ): DeepseekFile

    /**
     * 从本地路径上传，等价于 `openFileSource(path).use { upload(it, mimeType, filename ?: 路径文件名, options) }`。
     *
     * 默认文件名取路径的最后一段（例如 `photos/cat.jpg` → `cat.jpg`）；读取失败抛
     * [io.github.hatoyuze.deepseek.protocol.api.entity.FileSourceReadException]。
     * JS/Wasm 平台不支持路径来源，请改用 [upload] 与 `FileSource.Bytes`。
     *
     * @param path 平台相关的本地路径
     * @param mimeType 图片 MIME 类型，需形如 `image/png`
     * @param filename 覆盖默认文件名；为 `null` 时使用路径文件名
     * @param options 上传选项（有效期）
     */
    public suspend fun upload(
        path: String,
        mimeType: String,
        filename: String? = null,
        options: UploadOptions = UploadOptions(),
    ): DeepseekFile

    /**
     * 查询单个文件信息（`GET /files/:file_id`）。
     *
     * @param fileId 文件标识符，形如 `file-api-...`
     */
    public suspend fun retrieve(fileId: String): DeepseekFile

    /**
     * 列出文件（`GET /files`，游标分页）。
     *
     * ```kotlin
     * var page = ds.files().list(limit = 100)
     * while (page.hasMore) {
     *     println(page.data.map { it.filename })
     *     page = ds.files().list(after = page.lastId, limit = 100)
     * }
     * ```
     *
     * @param after 分页游标：返回排在该 `file_id` 之后的文件；`null` 表示从头开始
     * @param limit 返回数量，取值 `1..1000`（服务端默认 `1000`）；越界在调用期 fail-fast
     * @param order 按创建时间排序，默认升序
     * @param purpose 只返回指定用途的文件，目前仅支持 `user_data`
     * @throws IllegalArgumentException [limit] 越界或 [after] 为空白字符串时
     */
    public suspend fun list(
        after: String? = null,
        limit: Int? = null,
        order: FileOrder = FileOrder.Asc,
        purpose: FilePurpose = FilePurpose.UserData,
    ): FileList

    /**
     * 删除一个文件（`DELETE /files/:file_id`）。
     *
     * @param fileId 文件标识符，形如 `file-api-...`
     */
    public suspend fun delete(fileId: String): FileDeletion

    public companion object {
        /** `limit` 的下限（服务端约束） */
        public const val MIN_LIST_LIMIT: Int = 1

        /** `limit` 的上限（服务端约束） */
        public const val MAX_LIST_LIMIT: Int = 1000
    }
}
