package io.github.hatoyuze.deepseek.protocol.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Ktor HttpClient 的构建参数。
 *
 * 修改 [DeepseekHttpClientPool.config] 后，池会关闭旧客户端并按新配置重建；
 * 长 SSE 流依赖 connect/socket 超时兜底，不设置固定总请求超时。直接修改池中已有
 * 的 config 对象不会触发重建，请通过 `pool { config { } }` 或整体替换 [config] 应用新参数。
 *
 * @property connectTimeoutMillis 建立连接的超时毫秒数，默认 30 秒
 * @property socketTimeoutMillis 单次 socket 读超时毫秒数，`null` 表示不限制，默认 30 秒
 * @property maxRetries 服务端错误与可选超时场景下的最大重试次数，默认 5
 * @property retryOnTimeout 是否在请求超时时重试，默认 `true`
 */
public data class DeepseekHttpClientConfig(
    public var connectTimeoutMillis: Long = 30_000,
    public var socketTimeoutMillis: Long? = 30_000,
    public var maxRetries: Int = 5,
    public var retryOnTimeout: Boolean = true,
)

/**
 * 创建 [HttpClient] 的工厂。
 *
 * 默认工厂 [Default] 会安装 SSE、请求重试与超时插件；自定义工厂由调用方自行管理插件。
 */
public fun interface DeepseekHttpClientFactory {
    /**
     * 根据 [config] 创建 [HttpClient]。
     *
     * @param config 当前池使用的 [DeepseekHttpClientConfig]
     * @return 创建完成的 [HttpClient]
     */
    public fun create(config: DeepseekHttpClientConfig): HttpClient

    public companion object {
        /** 默认工厂：安装 SSE、HttpRequestRetry 与 HttpTimeout 插件。 */
        public val Default: DeepseekHttpClientFactory = DeepseekHttpClientFactory { config ->
            HttpClient {
                install(SSE) {
                    maxReconnectionAttempts = 0
                }
                install(HttpRequestRetry) {
                    retryOnServerErrors(maxRetries = config.maxRetries)
                    exponentialDelay()
                    if (config.retryOnTimeout) {
                        retryOnExceptionIf { _, cause ->
                            cause is HttpRequestTimeoutException
                        }
                    }
                }
                install(HttpTimeout) {
                    // 不设置 requestTimeoutMillis：长 SSE 流可能超过固定总时长。
                    connectTimeoutMillis = config.connectTimeoutMillis
                    socketTimeoutMillis = config.socketTimeoutMillis
                }
            }
        }
    }
}

/**
 * 按 baseUrl 缓存并共享 Ktor HttpClient 的池。
 *
 * 同一池内相同 baseUrl 复用同一个客户端与连接池；鉴权通过每请求的 `Authorization`
 * 头注入，因此共享客户端可服务不同 API Key。替换 [config] 或 [factory] 会关闭旧客户端。
 *
 * [Global] 为进程级共享池；需要独立配置时创建新的池实例，并在构建客户端时通过
 * `pool { }` DSL 指定。
 *
 * @param config 初始构建参数
 * @param factory 客户端工厂，默认使用 [DeepseekHttpClientFactory.Default]
 */
public class DeepseekHttpClientPool(
    config: DeepseekHttpClientConfig = DeepseekHttpClientConfig(),
    factory: DeepseekHttpClientFactory = DeepseekHttpClientFactory.Default,
) {
    /** 当前构建参数；替换后关闭并清空已缓存客户端。 */
    public var config: DeepseekHttpClientConfig = config
        set(value) {
            field = value
            invalidate()
        }

    /** 当前客户端工厂；替换后关闭并清空已缓存客户端。 */
    public var factory: DeepseekHttpClientFactory = factory
        set(value) {
            field = value
            invalidate()
        }

    private val clients = mutableMapOf<String, HttpClient>()

    @Volatile
    private var clientsSnapshot: Map<String, HttpClient> = emptyMap()

    /** 失效代际：每次 [close] 递增，用于识别创建期间发生的失效。 */
    @Volatile
    private var generation: Long = 0L

    /** 是否发生过尚未被锁内逻辑清理的失效（[close] 不触碰 [clients] map）。 */
    @Volatile
    private var invalidated: Boolean = false

    private val lock = Mutex()

    /**
     * 获取（或创建）指定 baseUrl 的共享客户端。
     *
     * 快照读取后二次校验 [generation]，且创建完成后再次校验，保证 [close]/
     * 配置替换返回后，本方法不会再返回 close 之前创建的客户端。
     */
    internal suspend fun client(baseUrl: String): HttpClient {
        while (true) {
            val gen = generation
            clientsSnapshot[baseUrl]?.let { client ->
                if (gen == generation) return client
            }
            val created = lock.withLock {
                if (invalidated) {
                    clients.clear()
                    invalidated = false
                }
                if (gen != generation) return@withLock null
                clients[baseUrl]?.let { return@withLock it }
                val fresh = factory.create(config)
                if (generation != gen || invalidated) {
                    clients.remove(baseUrl)
                    fresh.close()
                    return@withLock null
                }
                clients[baseUrl] = fresh
                clientsSnapshot = clients.toMap()
                fresh
            }
            if (created != null) return created
        }
    }

    /**
     * 关闭池内所有客户端并清空缓存。
     *
     * 调用后池仍可继续使用，下次请求会按当前 [config] 与 [factory] 重新创建；
     * 与并发中的客户端创建之间不保证严格串行，但 close 返回后不会再向外提供
     * close 之前创建的客户端。
     */
    public fun close() {
        generation++
        invalidated = true
        val snapshot = clientsSnapshot.values.toList()
        clientsSnapshot = emptyMap()
        snapshot.forEach { it.close() }
    }

    private fun invalidate() {
        close()
    }

    public companion object {
        /** 进程级共享池，默认由所有 [io.github.hatoyuze.deepseek.protocol.api.Deepseek] 实例共用。 */
        public val Global: DeepseekHttpClientPool = DeepseekHttpClientPool()
    }
}

/**
 * 更新池的构建参数。
 *
 * 通过复制当前配置再应用 [block] 完成修改，避免直接改动共享的配置实例。
 *
 * Example:
 * ```kotlin
 * deepseek("sk-xxx") {
 *     pool {
 *         config {
 *             maxRetries = 2
 *             socketTimeoutMillis = null
 *         }
 *     }
 * }
 * ```
 *
 * @param block 配置修改 lambda，receiver 为新的 [DeepseekHttpClientConfig]
 */
public fun DeepseekHttpClientPool.config(block: DeepseekHttpClientConfig.() -> Unit) {
    config = config.copy().apply(block)
}
