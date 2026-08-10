package com.github.hatoyuze.protocol.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 按 baseUrl 共享的 Ktor HttpClient 池。
 *
 * 同一 baseUrl 复用同一个客户端与连接池，避免每个 [com.github.hatoyuze.protocol.api.Deepseek]
 * 实例都创建独立客户端；鉴权通过每请求的 `Authorization` 头注入，因此共享客户端可服务不同 API Key。
 */
internal object DeepseekHttpClientPool {
    private val clients = mutableMapOf<String, HttpClient>()
    private val lock = Mutex()

    /** 获取（或创建）指定 baseUrl 的共享客户端 */
    fun client(baseUrl: String): HttpClient = runBlocking {
        lock.withLock {
            clients.getOrPut(baseUrl) {
                HttpClient {
                    install(Logging) {
                        level = LogLevel.NONE
                    }
                    install(ContentNegotiation) {
                        json(Json { ignoreUnknownKeys = true })
                    }
                    install(SSE) {
                        maxReconnectionAttempts = 0
                    }
                    install(HttpRequestRetry) {
                        retryOnServerErrors(maxRetries = 5)
                        exponentialDelay()
                        retryOnExceptionIf { _, cause ->
                            cause is HttpRequestTimeoutException
                        }
                    }
                    install(HttpTimeout) {
                        // 不设 requestTimeoutMillis：长 SSE 流可能超过固定总时长；
                        // 由 connect/socket 超时兜底。
                        connectTimeoutMillis = 30_000
                        socketTimeoutMillis = 30_000
                    }
                }
            }
        }
    }
}
