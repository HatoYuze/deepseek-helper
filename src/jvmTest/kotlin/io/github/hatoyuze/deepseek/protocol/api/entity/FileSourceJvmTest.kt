package io.github.hatoyuze.deepseek.protocol.api.entity

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JVM 平台上 [FileSource.Path] 的真实文件读取。
 *
 * 这是 expect/actual 的 actual 侧契约：路径来源必须能读到真实字节，读取失败必须抛
 * [FileSourceReadException]（而不是泄漏 `java.io.IOException`，让跨平台代码也能统一处理），
 * 且 `use {}` 在异常路径上同样会释放。
 */
class FileSourceJvmTest {

    @Test
    fun `openFileSource reads the bytes of a real file`() {
        val file = Files.createTempFile("deepseek-helper", ".bin")
        try {
            val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x7F, -1)
            Files.write(file, bytes)

            val read = openFileSource(file.toString()).use { it.readBytes() }

            assertContentEquals(bytes, read)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `readBytes can be called repeatedly`() {
        val file = Files.createTempFile("deepseek-helper", ".txt")
        try {
            Files.write(file, "hello".toByteArray())
            val source = openFileSource(file.toString())

            assertEquals("hello", source.readBytes().decodeToString())
            assertEquals("hello", source.readBytes().decodeToString())
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `missing file raises FileSourceReadException`() {
        val missing = Files.createTempDirectory("deepseek-helper").resolve("nope.png")

        val failure = assertFailsWith<FileSourceReadException> {
            openFileSource(missing.toString()).readBytes()
        }
        assertTrue(failure.path.endsWith("nope.png"), "异常应带上路径：${failure.path}")
        assertTrue(failure.cause != null, "应保留底层原因，便于排查")
        // 消息里不应出现文件内容（无内容可泄漏，但路径是允许记录的）
        assertTrue(failure.message!!.contains("FileSource.Bytes"), "应提示替代方案：${failure.message}")
    }

    @Test
    fun `directory path raises FileSourceReadException`() {
        val dir = Files.createTempDirectory("deepseek-helper")

        assertFailsWith<FileSourceReadException> { openFileSource(dir.toString()).readBytes() }
    }

    @Test
    fun `use closes the source even when the block throws`() {
        var closed = false
        val source = object : FileSource {
            override fun readBytes(): ByteArray = throw IllegalStateException("boom")
            override fun close() {
                closed = true
            }
        }

        assertFailsWith<IllegalStateException> { source.use { it.readBytes() } }

        assertTrue(closed, "use {} 必须在异常路径上释放资源")
    }
}
