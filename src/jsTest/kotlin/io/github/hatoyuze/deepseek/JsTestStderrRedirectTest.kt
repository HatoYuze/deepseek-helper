package io.github.hatoyuze.deepseek

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守住 jsTest 的 stderr 重定向：漏了它，CI 的 `:jsNodeTest` 会随机崩在 Gradle 的输出存储上
 * （原因见 [installJsTestStderrRedirect] 的 KDoc 与 gradle/gradle#36751）。
 */
class JsTestStderrRedirectTest {

    @Test
    fun `js test process writes diagnostics to stdout`() {
        assertTrue(
            jsTestStderrRedirectInstalled,
            "测试 bundle 载入时必须已经安装重定向（否则日志会落在 stderr 上触发 Gradle 的已知崩溃）",
        )
        assertTrue(
            isJsTestStderrRedirected(),
            "console.error 必须接到 stdout，实际仍未重定向",
        )
    }
}
