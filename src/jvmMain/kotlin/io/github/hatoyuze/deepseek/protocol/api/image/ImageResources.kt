package io.github.hatoyuze.deepseek.protocol.api.image

import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.openFileSource
import java.io.File
import java.net.URL

/**
 * 从 classpath 资源加载图片字节 / [FileSource]。
 *
 * 用途：本地文件、打包进 jar 的资源（`src/jvmTest/resources`、`src/jvmMain/resources`）里的图片，
 * 可以直接喂给 `imageOf(...)` 或 `Deepseek.files().upload(...)`，无需先复制到磁盘。
 *
 * ```kotlin
 * // 直接内联进消息
 * Role.User says imageOf(imageBytesOfResource("/sample.jpg")) + "这张图片里有什么？"
 *
 * // 上传到 Files API（记得 use {} 释放）
 * fileSourceOfResource("/sample.jpg").use { ds.files().upload(it, "image/jpeg", "sample.jpg") }
 * ```
 *
 * 读取失败（资源不存在 / 不是文件系统资源）抛 [IllegalArgumentException]；
 * 读取同步发生在调用方线程，大图请自行切到 IO 调度器。
 */
public fun imageResourceUrl(resourcePath: String): URL {
    val normalized = resourcePath.trimStart('/')
    return object {}.javaClass.getResource(resourcePath)
        ?: object {}.javaClass.classLoader?.getResource(normalized)
        ?: throw IllegalArgumentException("classpath 资源不存在：$resourcePath")
}

/** 读取 classpath 图片资源的全部字节 */
public fun imageBytesOfResource(resourcePath: String): ByteArray =
    imageResourceUrl(resourcePath).openStream().use { it.readBytes() }

/**
 * 把 classpath 图片资源包成 [FileSource.Path]（`use {}` 由调用方负责）。
 *
 * @throws IllegalArgumentException 资源不存在，或不是文件系统资源（例如被打进某些 jar 形态）
 */
public fun fileSourceOfResource(resourcePath: String): FileSource {
    val url = imageResourceUrl(resourcePath)
    val file = runCatching { File(url.toURI()) }.getOrElse {
        throw IllegalArgumentException("资源 $resourcePath 不是文件系统资源，请改用 imageBytesOfResource(...)", it)
    }
    return openFileSource(file.path)
}
