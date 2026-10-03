package io.github.hatoyuze.deepseek

/**
 * Kotlin/JS 测试进程的 stderr 重定向（**仅测试源码集，不进发布产物**）。
 *
 * ## 为什么需要它
 *
 * Gradle 8.x 的测试输出存储只要收到「测试进程在用例窗口之外写出的普通文本」就会抛异常
 * （Gradle 侧表现为 `NullPointerException: ... "testCaseRegions" is null`，随后整个 JS 测试任务
 * 以 `Could not write XML test results` 崩掉）。上游同类缺陷见
 * [gradle/gradle#36751](https://github.com/gradle/gradle/issues/36751)，修复落在 Gradle 9.4，
 * 而本项目仍用 8.12。
 *
 * 触发条件是**流的分裂**：Kotlin/JS 的测试进程把 `console.error` 写到 **stderr**，而承载
 * `##teamcity[...]` 服务消息的是 **stdout**——Gradle 用两条独立线程读它们，彼此没有次序保证；
 * 只要有一行诊断日志落在用例收尾窗口，就会被归属到已关闭的用例上，整个任务随机失败
 * （同仓库的 wasmJs/native 测试把日志写到 stdout，从未复现过）。
 *
 * 本库的 JS `Logger` 用 `console.error`（在浏览器 DevTools 里这是正确的 error 级别），因此**不改生产
 * 代码**，只在测试 bundle 里把 `console.error` 接到 stdout，让诊断输出回到与服务消息同一条有序流上。
 *
 * ## 何时可以删掉
 *
 * Gradle 升级到 ≥ 9.4（或该 issue 的修复进入所用版本）之后，本文件与
 * [JsTestStderrRedirectTest] 可以一起删除。
 */
internal fun installJsTestStderrRedirect(): Unit = js(
    "(function () { " +
        "if (typeof console !== 'undefined' && typeof console.log === 'function') { " +
        "console.error = console.log; " +
        "} " +
        "})()",
)

/** 当前 `console.error` 是否已接到 stdout（供测试断言）。 */
internal fun isJsTestStderrRedirected(): Boolean = js("console.error === console.log")

/**
 * 顶层初始化：测试 bundle 一载入就生效，早于任何用例。
 *
 * 刻意用顶层 `val` 而不是在某个用例里调用——要防的正是「在用例窗口之外输出」这一刻，
 * 等到第一个用例才装就晚了。
 */
internal val jsTestStderrRedirectInstalled: Boolean = run {
    installJsTestStderrRedirect()
    isJsTestStderrRedirected()
}
