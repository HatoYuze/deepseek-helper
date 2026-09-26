package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.impl.defaultUploadFilename
import io.github.hatoyuze.deepseek.protocol.api.impl.requireValidFilename
import io.github.hatoyuze.deepseek.protocol.api.impl.sanitizedForLog
import io.github.hatoyuze.deepseek.protocol.net.redactForHook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 面向日志的脱敏规则（请求 hook 与上传摘要共用）。
 *
 * 这些函数是安全边界：hook 收到的一切都会进应用日志，因此这里逐条钉死
 * 「什么必须被替换、什么必须原样保留、什么必须被拒绝」。
 *
 * 说明：不做端到端 hook 断言是因为仓库已知限制 —— Ktor 的 SSE 插件在 MockEngine 上
 * 不投递解析后的事件（见 `BaseUrlTest` 注释），而 chat 请求恰好全走 SSE；因此这里直接
 * 覆盖脱敏函数本身，端到端那一层由 `FilesApiTest`（multipart，非 SSE）覆盖。
 */
class HookRedactionTest {

    // ── 请求体脱敏 ──

    @Test
    fun `data url payload is replaced in place`() {
        val body = """{"messages":[{"content":[{"type":"image_url",""" +
            """"image_url":{"url":"data:image/png;base64,${"A".repeat(3000)}"}}]}]}"""
        val redacted = redactForHook(body)

        assertFalse(redacted.contains("A".repeat(100)), "base64 载荷必须被替换")
        assertTrue(redacted.contains("data:image/png;base64,<redacted"), "应保留 mime 与占位标记：$redacted")
        assertTrue(redacted.contains("\"type\":\"image_url\""), "结构必须保留：${redacted.take(200)}")
    }

    @Test
    fun `short opaque values survive but long ones are redacted`() {
        val shortId = "file-api-0123456789abcdef"
        val token = "B".repeat(2500)
        val redacted = redactForHook("""{"file_id":"$shortId","data":"$token"}""")

        assertTrue(redacted.contains(shortId), "正常长度的 id 不该被改写：$redacted")
        assertFalse(redacted.contains(token), "超长不透明载荷必须被替换：${redacted.take(200)}")
    }

    @Test
    fun `plain text body is untouched`() {
        val body = """{"messages":[{"role":"user","content":"你好"}]}"""
        assertEquals(body, redactForHook(body))
    }

    @Test
    fun `very long body is truncated with a marker`() {
        // 用带空白的重复片段：不会先被「无空白长段」规则吃掉，从而单独验证长度上限
        val body = "hello world ".repeat(1_000)

        val redacted = redactForHook(body)

        assertTrue(redacted.length < body.length, "超长请求体必须被截断：${redacted.length} vs ${body.length}")
        assertTrue(redacted.contains("truncated"), "截断必须留下标记：${redacted.takeLast(80)}")
    }

    @Test
    fun `long but documented length urls survive redaction`() {
        // 官方允许外部图片 URL 到 8192 字符。取 3000 字符：远长于「不透明载荷」阈值、
        // 又短于请求体上限，因此必须原样出现，不能被替换。
        val url = "https://example.com/" + "a".repeat(3000) + ".jpg"
        val body = """{"messages":[{"content":[{"type":"image_url","image_url":{"url":"$url"}}]}]}"""

        val redacted = redactForHook(body)

        assertTrue(redacted.contains(url), "合法 URL 必须原样保留：${redacted.take(120)}…")
        assertFalse(redacted.contains("redacted"), "合法请求体不该出现脱敏占位")
    }

    @Test
    fun `body over the limit is truncated but reports why`() {
        val url = "https://example.com/" + "a".repeat(8000) + ".jpg"
        val body = """{"messages":[{"content":[{"type":"image_url","image_url":{"url":"$url"}}]}]}"""

        val redacted = redactForHook(body)

        assertFalse(redacted.contains("<redacted"), "超长 URL 不是载荷，不该被替换成脱敏占位")
        assertTrue(redacted.contains("<truncated"), "整体超长应被截断并说明：${redacted.takeLast(60)}")
    }

    @Test
    fun `percent encoded data url keeps its own prefix`() {
        val body = "data:text/plain,Hello%20World%20" + "X".repeat(3000)

        val redacted = redactForHook(body)

        assertFalse(redacted.contains("X".repeat(100)), "载荷必须被替换")
        assertTrue(redacted.startsWith("data:text/plain,<redacted"), "占位符应保留原始前缀（不得谎称 ;base64）：$redacted")
    }

    @Test
    fun `redaction is linear in body size`() {
        // 回归护栏：这条路径在每次请求前同步执行，曾经的 `[^\\s]{2048,}` 正则在 200 KB
        // 体量下要 800ms+（二次复杂度）。用 3000 字符的段（高于阈值，会真正触发替换）
        // 重复到 200 KB，给一个宽松但有意义的上限。
        val body = ("a".repeat(3000) + " ").repeat(70) // 约 210 KB，段落长度 3000（触发替换）

        val started = kotlin.time.TimeSource.Monotonic.markNow()
        redactForHook(body)
        val elapsed = started.elapsedNow()

        assertTrue(elapsed.inWholeMilliseconds < 200, "脱敏必须是 O(n)：200 KB 用了 ${elapsed}")
    }

    @Test
    fun `a single long opaque run is redacted rather than truncated`() {
        val redacted = redactForHook("x".repeat(20_000))

        assertFalse(redacted.contains("x".repeat(100)), "长段必须被替换")
        assertTrue(redacted.contains("redacted 20000 chars"), "应报告被替换的长度：$redacted")
    }

    @Test
    fun `empty body stays empty`() {
        assertEquals("", redactForHook(""))
    }

    // ── 上传摘要脱敏 ──

    @Test
    fun `log sanitizer flattens control characters`() {
        val flattened = "a\r\nb\tc\u0000d".sanitizedForLog()

        assertFalse(flattened.contains('\n'), "换行必须被抹平（否则可伪造日志行）")
        assertFalse(flattened.contains('\r'))
        assertFalse(flattened.contains('\t'))
        assertEquals("a  b c d", flattened)
        assertTrue(flattened.length <= 129, "需限长：${flattened.length}")
    }

    // ── 默认文件名推导（Windows 回归） ──

    @Test
    fun `default filename handles unix and windows separators`() {
        assertEquals("cat.jpg", defaultUploadFilename("photos/cat.jpg"))
        assertEquals("cat.jpg", defaultUploadFilename("cat.jpg"))
        assertEquals("cat.jpg", defaultUploadFilename("photos/nested/cat.jpg"))
        // Windows（带盘符）：绝不能把整条路径（含用户名与目录结构）当成文件名发给服务端
        assertEquals("cat.jpg", defaultUploadFilename("""C:\Users\alice\Pictures\cat.jpg"""))
        assertEquals("cat.jpg", defaultUploadFilename("""C:\cat.jpg"""))
        // POSIX 上 `\\` 是合法文件名字符：没有盘符时不能当分隔符
        assertEquals("""photo\cat.jpg""", defaultUploadFilename("""photo\cat.jpg"""))
        assertNull(defaultUploadFilename(""), "空路径没有默认名")
        assertNull(defaultUploadFilename("photos/"), "以分隔符结尾时没有默认名")
    }

    // ── 文件名校验 ──

    @Test
    fun `valid filenames pass`() {
        requireValidFilename("cat.jpg")
        requireValidFilename("带中文的名字.png")
        requireValidFilename("a".repeat(512))
        requireValidFilename("weird name (1)!.webp")
    }

    @Test
    fun `dangerous filenames are rejected`() {
        val rejected = listOf(
            "a\r\nX-Injected: 1",
            "a\nb",
            "a\u0000b",
            "quote\".png",
            """back\slash.png""",
            "dir/child.png",
            "   ",
            "a".repeat(513),
        )
        rejected.forEach { filename ->
            assertFailsWith<IllegalArgumentException>("应拒绝：${filename.take(20)}") {
                requireValidFilename(filename)
            }
        }
    }

    @Test
    fun `rejection message does not echo the injected payload`() {
        val injection = "a\r\nContent-Type: text/plain"
        val failure = assertFailsWith<IllegalArgumentException> { requireValidFilename(injection) }

        assertFalse(failure.message!!.contains("Content-Type"), "异常消息不得回显被注入的内容：${failure.message}")
    }
}
