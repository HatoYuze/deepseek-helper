package io.github.hatoyuze.deepseek

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 守住 jsTest 的 console 静默：漏了它，CI 的 `:jsNodeTest` 会随机崩在 Gradle 的输出存储上
 * （原因见 [installJsTestConsoleSilencer] 的 KDoc 与 gradle/gradle#36751）。
 */
class JsTestConsoleSilencerTest {

    @Test
    fun `js test process silences library console output`() {
        assertTrue(
            jsTestConsoleSilencerInstalled,
            "测试 bundle 载入时必须已经静默 console（否则收尾日志会触发 Gradle 的已知崩溃）",
        )
        assertTrue(isJsTestConsoleSilenced(), "console 未被静默")
    }
}
