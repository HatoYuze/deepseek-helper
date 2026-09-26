package io.github.hatoyuze.deepseek.protocol.api.entity

import java.io.File
import java.io.IOException

/**
 * JVM 平台的文件读取实现。
 *
 * 读取本身在调用方线程完成（`readBytes()` 非 suspend），这是刻意为之：[FileSource] 的
 * 契约是「调用方自己决定在哪个线程/调度器上读」，库不在内部偷偷切换调度器。
 */
internal actual fun readFileBytes(path: String): ByteArray =
    try {
        File(path).readBytes()
    } catch (e: IOException) {
        throw FileSourceReadException(path, e)
    } catch (e: SecurityException) {
        throw FileSourceReadException(path, e)
    }
