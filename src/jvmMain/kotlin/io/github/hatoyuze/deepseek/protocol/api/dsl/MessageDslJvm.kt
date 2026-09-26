package io.github.hatoyuze.deepseek.protocol.api.dsl

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.FileSource
import io.github.hatoyuze.deepseek.protocol.api.entity.ImageUrlDetail
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.openFileSource
import java.io.File
import java.io.InputStream
import java.net.URI

/**
 * JVM / Android 上的 [imageOf] 扩展：直接接受平台类型，不必先转成 [FileSource]。
 *
 * ```kotlin
 * Role.User says imageOf(File("cat.jpg")) + "这是什么？"
 * Role.User says imageOf(URI("https://example.com/cat.jpg")) + "描述一下"
 * Role.User says imageOf(inputStream) + "看图说话"
 * ```
 *
 * 注意：这是平台源集里的扩展（`jvmMain` / `androidMain`），KMP 公共代码里请用
 * [imageOf] 的 `ByteArray` / [FileSource] / `String` 重载。读取同样是同步的，
 * 在 UI 线程上请自行 `withContext(Dispatchers.IO)`。
 */

/** [imageOf] 的 `File` 重载（JVM / Android） */
public fun imageOf(file: File, detail: ImageUrlDetail = ImageUrlDetail.Auto): MessageContent.Parts =
    imageOf(file.path, detail)

/**
 * [imageOf] 的 `java.net.URI` 重载（JVM / Android）。
 *
 * `http(s)` URI 原样作为外链；`file:` URI 读取对应文件并内联；其他 scheme 抛
 * [IllegalArgumentException]（官方只支持 `http(s)` 与 data URL）。
 */
public fun imageOf(uri: URI, detail: ImageUrlDetail = ImageUrlDetail.Auto): MessageContent.Parts =
    MessageContent.Parts(listOf(imagePartOf(uri, detail)))

/** [imagePartOf] 的 `File` 重载：读取并内联为 base64 data URL */
public fun imagePartOf(file: File, detail: ImageUrlDetail = ImageUrlDetail.Auto): ContentPart.ImagePart =
    imagePartOf(file.path, detail)

/** [imagePartOf] 的 `URI` 重载 */
public fun imagePartOf(uri: URI, detail: ImageUrlDetail = ImageUrlDetail.Auto): ContentPart.ImagePart {
    val raw = uri.toString()
    val scheme = uri.scheme?.lowercase()
    return when (scheme) {
        "http", "https" -> ContentPart.ImagePart(imageUrl = raw, detail = detail)
        "data" -> ContentPart.ImagePart(imageUrl = raw, detail = detail)
        "file" -> imagePartOf(File(uri), detail)
        else -> throw IllegalArgumentException(
            "不支持的图片 URI scheme：${uri.scheme}；只支持 http(s) / data / file（本地文件请用 imageOf(File(...))）",
        )
    }
}

/**
 * [imageOf] 的 `InputStream` 重载（JVM / Android）：读完后**不关闭**传入的流，
 * 由调用方负责（与 [FileSource] 的契约一致）。
 */
public fun imageOf(stream: InputStream, detail: ImageUrlDetail = ImageUrlDetail.Auto): MessageContent.Parts =
    MessageContent.Parts(listOf(imagePartOf(stream, detail)))

/** [imagePartOf] 的 `InputStream` 重载：读取全部字节后内联 */
public fun imagePartOf(stream: InputStream, detail: ImageUrlDetail = ImageUrlDetail.Auto): ContentPart.ImagePart =
    imagePartOf(stream.readBytes(), detail)
