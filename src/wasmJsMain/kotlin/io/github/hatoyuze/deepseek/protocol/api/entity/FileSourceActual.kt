package io.github.hatoyuze.deepseek.protocol.api.entity

/**
 * Wasm 平台没有本地文件系统路径可读，因此 [FileSource.Path] 在此平台不受支持：
 * 请先把字节读出来，再用 [FileSource.Bytes]。
 */
internal actual fun readFileBytes(path: String): ByteArray =
    throw UnsupportedOperationException(
        "JS/Wasm 平台不支持 FileSource.Path($path)：请先把文件读成字节，再使用 FileSource.Bytes(bytes)",
    )
