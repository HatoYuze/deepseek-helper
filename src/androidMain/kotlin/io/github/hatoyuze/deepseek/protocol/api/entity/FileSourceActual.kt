package io.github.hatoyuze.deepseek.protocol.api.entity

import java.io.File
import java.io.IOException

/**
 * Android 平台的文件读取实现（与 JVM 一致，`java.io.File`）。
 *
 * 注意：这里读的是**应用进程可直接访问的路径**（应用私有目录等普通路径）。需要读取
 * `content://` URI 或共享存储时，请先用平台 API 读出字节，再交给 [FileSource.Bytes]。
 */
internal actual fun readFileBytes(path: String): ByteArray =
    try {
        File(path).readBytes()
    } catch (e: IOException) {
        throw FileSourceReadException(path, e)
    } catch (e: SecurityException) {
        throw FileSourceReadException(path, e)
    }
