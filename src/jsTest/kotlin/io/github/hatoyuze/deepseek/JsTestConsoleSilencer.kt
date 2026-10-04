package io.github.hatoyuze.deepseek

/**
 * Kotlin/JS 测试进程的 console 静默（**仅测试源码集，不进发布产物**）。
 *
 * ## 为什么需要它
 *
 * Gradle 8.x 的测试输出存储只要收到「测试进程写出的普通文本」而当前没有打开的用例区域，就会抛
 * `NullPointerException: ... "testCaseRegions" is null`，随后整个 JS 测试任务以
 * `Could not write XML test results for …` 崩掉。上游同类缺陷见
 * [gradle/gradle#36751](https://github.com/gradle/gradle/issues/36751)（标注 `a:regression`，
 * 修复落在 Gradle 9.4），而本项目仍在 8.12。
 *
 * 触发窗口很小但真实存在：本库的 `Logger` 会在**一条流收尾时**打汇总日志（`本轮响应检测到内联工具
 * 调用信封…`），那一刻恰好压在用例边界上；CI（负载更重的 runner）稳定复现，本机复现不到。
 *
 * ## 做法与边界
 *
 * 只把 `console` 上的方法换成空实现，**不碰 `process.stdout.write`**——Kotlin 测试适配器的
 * TeamCity 服务消息走的是后者，因此测试结果、失败与超时依然如实上报（这一点由
 * `JsTestConsoleSilencerTest` 与一次「故意失败」的本地演练共同守住）。生产代码、`jsMain` 的
 * `console.error` 语义与发布产物都不受影响；本机调试 JS 用例时若需要日志，把这里临时注掉即可。
 *
 * ## 何时可以删掉
 *
 * Gradle 升级到 ≥ 9.4（或该修复进入所用版本）之后，本文件与 [JsTestConsoleSilencerTest] 可一起删除。
 */
internal fun installJsTestConsoleSilencer(): Unit = js(
    "(function () { " +
        "if (typeof console === 'undefined') { return; } " +
        "var noop = function () {}; " +
        "console.log = noop; console.info = noop; console.warn = noop; console.error = noop; " +
        "console.__dshTestSilenced = true; " +
        "})()",
)

/** `console` 是否已静默（供测试断言）。 */
internal fun isJsTestConsoleSilenced(): Boolean = js("console.__dshTestSilenced === true")

/**
 * 顶层初始化：测试 bundle 一载入就生效，早于任何用例。
 *
 * 刻意用顶层 `val` 而不是在某个用例里调用——要防的正是「用例边界上的输出」这一刻。
 */
internal val jsTestConsoleSilencerInstalled: Boolean = run {
    installJsTestConsoleSilencer()
    isJsTestConsoleSilenced()
}
