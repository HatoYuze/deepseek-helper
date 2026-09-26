package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Files API 中的一个文件对象。
 *
 * 通过 `POST /files` 上传图片后得到，之后可在对话补全请求里用它的 [id] 引用
 * （见 [ContentPart.FilePart] / [ContentPart.ImagePart]）：
 *
 * ```kotlin
 * val uploaded = ds.files().upload("cat.jpg", mimeType = "image/jpeg")
 * ds.chatStream(MessageContent.imageFile(uploaded.id).parts).collect { ... }
 * ```
 *
 * @property id 文件标识符，形如 `file-api-...`，可在对话补全请求中引用
 * @property obj 对象类型，固定为 `file`
 * @property bytes 文件大小（字节）
 * @property createdAt 创建时刻的 Unix 时间戳（秒）
 * @property filename 文件名
 * @property purpose 文件用途，目前仅 `user_data`
 * @property expiresAt 过期时刻的 Unix 时间戳（秒）；**上传时设置了有效期才会出现**，永久有效时为 `null`
 *
 * @see FilePurpose
 * @see UploadOptions
 */
@Serializable
public data class DeepseekFile(
    public val id: String,
    @SerialName("object") public val obj: String = "file",
    public val bytes: Long,
    @SerialName("created_at") public val createdAt: Long,
    public val filename: String,
    public val purpose: String = FilePurpose.UserData.wireName,
    @SerialName("expires_at") public val expiresAt: Long? = null,
)

/**
 * 文件列表，使用游标分页（对应 `GET /files`）。
 *
 * @property obj 对象类型，固定为 `list`
 * @property data 当前页的文件对象
 * @property firstId 本页第一个文件的 ID，可用作分页游标
 * @property lastId 本页最后一个文件的 ID，可用作分页游标
 * @property hasMore 是否还有更多文件
 */
@Serializable
public data class FileList(
    @SerialName("object") public val obj: String = "list",
    public val data: List<DeepseekFile>,
    @SerialName("first_id") public val firstId: String? = null,
    @SerialName("last_id") public val lastId: String? = null,
    @SerialName("has_more") public val hasMore: Boolean = false,
)

/**
 * 文件删除结果（对应 `DELETE /files/:file_id`）。
 *
 * @property id 被删除文件的 ID
 * @property obj 对象类型，固定为 `file`
 * @property deleted 是否删除成功
 */
@Serializable
public data class FileDeletion(
    public val id: String,
    @SerialName("object") public val obj: String = "file",
    public val deleted: Boolean,
)

/**
 * 文件用途。
 *
 * 官方目前只支持 `user_data` 一种取值，上传时必须指定。
 */
public enum class FilePurpose(public val wireName: String) {
    /** 用户数据（图片），唯一受支持的用途 */
    UserData("user_data"),
}

/**
 * 列出文件时的排序方式（对应 `GET /files` 的 `order` 参数）。
 */
public enum class FileOrder(public val wireName: String) {
    /** 按创建时间升序（服务端默认值） */
    Asc("asc"),

    /** 按创建时间降序 */
    Desc("desc"),
}

/**
 * 上传文件的可选参数。
 *
 * ```kotlin
 * // 1 小时后过期
 * ds.files().upload("cat.jpg", options = UploadOptions(expiresAfterSeconds = 3600))
 * // 不传过期参数 → 永久有效
 * ds.files().upload("cat.jpg")
 * ```
 *
 * @property expiresAfterSeconds 文件有效期（秒），取值区间 `3600`（1 小时）到 `2592000`（30 天）；
 *   `null` 表示永久有效（不发送 `expires_after` 字段）。越界在构造期 fail-fast
 *
 * @throws IllegalArgumentException [expiresAfterSeconds] 越界时
 */
public data class UploadOptions(
    public val expiresAfterSeconds: Int? = null,
) {
    init {
        val seconds = expiresAfterSeconds
        require(seconds == null || seconds in MIN_EXPIRY_SECONDS..MAX_EXPIRY_SECONDS) {
            "expiresAfterSeconds 必须在 $MIN_EXPIRY_SECONDS..$MAX_EXPIRY_SECONDS 之间（1 小时到 30 天），实际为 $seconds"
        }
    }

    public companion object {
        /** 最短有效期：1 小时 */
        public const val MIN_EXPIRY_SECONDS: Int = 3600

        /** 最长有效期：30 天 */
        public const val MAX_EXPIRY_SECONDS: Int = 2_592_000
    }
}
