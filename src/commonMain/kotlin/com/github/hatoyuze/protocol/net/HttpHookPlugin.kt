package com.github.hatoyuze.protocol.net

import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 全局 [HttpHook] 注册中心。
 *
 * 底层网络层发出的每个请求与响应都会遍历此处注册的钩子，
 * 常用于调试、日志等横切关注点；增删与遍历均线程安全。
 */
object HttpHookRegistry {
    private val hooks = mutableListOf<HttpHook>()
    private val lock = Mutex()

    fun add(hook: HttpHook) {
        runBlocking { lock.withLock { hooks.add(hook) } }
    }

    fun remove(hook: HttpHook) {
        runBlocking { lock.withLock { hooks.remove(hook) } }
    }

    fun forEach(action: (HttpHook) -> Unit) {
        val snapshot = runBlocking { lock.withLock { hooks.toList() } }
        snapshot.forEach(action)
    }
}

fun collectHeaders(h: Headers): Map<String, String> {
    val m = mutableMapOf<String, String>()
    h.forEach { n, vs -> m[n] = vs.joinToString(", ") }
    return m
}
