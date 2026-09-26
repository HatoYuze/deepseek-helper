package io.github.hatoyuze.deepseek.protocol.api.entity

/**
 * 上传到 Files API 的图片来源。
 *
 * 本类型唯一的存在理由是**资源纪律**：字节已经在内存里（[Bytes]）与需要从文件系统读取
 * （[Path]）是两种不同的资源持有方式，混在一个 `ByteArray` 参数里会让调用方无法表达
 * 「读完就释放」。读取完成后实现**不再持有**任何资源，因此：
 *
 * ```kotlin
 * // 路径来源：用 use {} 保证异常/取消时释放（Path 不在构造期打开文件）
 * val uploaded = openFileSource("cat.jpg").use { source ->
 *     ds.files().upload(source, "image/jpeg")
 * }
 * ```
 *
 * 平台差异：只有支持文件系统的平台提供 [Path] 的读取实现，其余平台在 `readBytes()` 时抛
 * [UnsupportedOperationException]（此时请改用 [Bytes]）。
 *
 * @see DeepseekFiles.upload
 */
public interface FileSource : AutoCloseable {
    /**
     * 读取全部字节。
     *
     * 可重复调用（每次重新读取）；返回值由调用方持有，实现不再引用任何底层资源。
     *
     * @throws FileSourceReadException 读取失败（不存在、无权限等）
     * @throws UnsupportedOperationException 当前平台不支持该来源（例如 JS/Wasm 上的 [Path]）
     */
    public fun readBytes(): ByteArray

    /** 字节已在内存中的来源 */
    public class Bytes(private val bytes: ByteArray) : FileSource {
        override fun readBytes(): ByteArray = bytes

        /** 无底层资源可释放 */
        override fun close(): Unit = Unit
    }

    /**
     * 文件系统路径来源，构造时不打开文件（`readBytes()` 时才读取）。
     *
     * @property path 平台相关的文件路径（JVM / Android / Native 传本地路径；JS / Wasm 不支持）
     */
    public class Path(public val path: String) : FileSource {
        override fun readBytes(): ByteArray = readFileBytes(path)

        /** 无底层资源可释放（每次读取都在 `readBytes()` 内自行关闭句柄） */
        override fun close(): Unit = Unit

        override fun toString(): String = "FileSource.Path($path)"
        // 故意不实现 equals/hashCode：路径字符串相等并不代表指向同一个文件（相对路径、不同工作目录）
    }
}

/**
 * 读取文件失败。
 *
 * 单独的类型（而不是直接抛平台异常）让调用方在跨平台代码里能统一处理「本地文件不可读」，
 * 而不必区分 JVM 的 `IOException`、Native 的 okio 异常等。
 *
 * @property path 读取失败的路径（本地路径，**不包含文件内容**，可安全记录进日志）
 */
public class FileSourceReadException(
    public val path: String,
    cause: Throwable? = null,
) : Exception(
    "读取文件失败：$path；若当前平台没有文件系统，请改用 FileSource.Bytes(bytes)",
    cause,
)

/**
 * 打开一个路径来源。
 *
 * 只构造 [FileSource.Path]，不会立刻访问文件系统（读取失败在 `readBytes()` 时以
 * [FileSourceReadException] 抛出）。
 *
 * @param path 平台相关的文件路径
 */
public fun openFileSource(path: String): FileSource = FileSource.Path(path)

/** 平台相关的文件读取实现；不支持文件系统的平台抛 [UnsupportedOperationException] */
internal expect fun readFileBytes(path: String): ByteArray
