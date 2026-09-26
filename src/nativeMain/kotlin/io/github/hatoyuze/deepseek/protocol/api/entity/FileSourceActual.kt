package io.github.hatoyuze.deepseek.protocol.api.entity

/**
 * Native（iOS / macOS / Linux / Windows）平台暂不支持按路径读取文件。
 *
 * 原因是一次实打实的工具链约束：Kotlin/Native 的 metadata/commonizer 会拒绝在 actual 声明
 * 所在的文件里使用平台数值类型（`ftell` 的 `long`、`fread` 的 `size_t`），报
 * "numbers with different bit widths in least two actual platforms"。
 * 要绕开它只能把文件读取塞进另一个非 actual 文件，代价（平台 API 泄漏进 commonMain 的可见面、
 * 额外的依赖声明）高于收益；而 Native 侧读文件本来就得走平台 API（NSData / NSFileManager）。
 *
 * 因此 Native 上请自行读取字节后交给 [FileSource.Bytes]：
 *
 * ```kotlin
 * val bytes = NSData.dataWithContentsOfFile(path)?.toByteArray() ?: error("unreadable")
 * ds.files().upload(FileSource.Bytes(bytes), "image/jpeg", "cat.jpg")
 * ```
 */
internal actual fun readFileBytes(path: String): ByteArray =
    throw UnsupportedOperationException(
        "Native 平台暂不支持 FileSource.Path($path)：请自行读取字节后改用 FileSource.Bytes(bytes)",
    )
