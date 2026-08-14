package io.github.hatoyuze.deepseek.protocol.net

import io.ktor.http.*
import kotlin.concurrent.Volatile

/**
 * 全局 [HttpHook] 注册中心。
 *
 * 底层网络层发出的每个请求与响应都会遍历此处注册的钩子，
 * 常用于调试、日志等横切关注点。采用写时复制（copy-on-write）快照：
 * 遍历始终读取不可变快照，add/remove 通过替换快照完成，
 * 在 JVM/Native 多线程与 JS 单线程下都无需阻塞锁。
 *
 * 线程模型：`add`/`remove`/`forEach`/`isEmpty` 可被任意线程安全地并发调用，
 * 无需外部加锁；遍历期间注册的钩子不会影响本次遍历（基于不可变快照）。
 */
public object HttpHookRegistry {
    @Volatile
    private var hooksSnapshot: List<HttpHook> = emptyList()

    public fun add(hook: HttpHook) {
        hooksSnapshot = hooksSnapshot + hook
    }

    public fun remove(hook: HttpHook) {
        hooksSnapshot = hooksSnapshot - hook
    }

    public fun isEmpty(): Boolean = hooksSnapshot.isEmpty()

    public fun forEach(action: (HttpHook) -> Unit) {
        hooksSnapshot.forEach(action)
    }
}

internal fun collectHeaders(h: Headers): Map<String, String> {
    val m = mutableMapOf<String, String>()
    h.forEach { n, vs -> m[n] = vs.joinToString(", ") }
    return m
}
