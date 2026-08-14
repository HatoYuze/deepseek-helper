package io.github.hatoyuze.deepseek.protocol.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JVM 重压测试：真实并行调度 + 放大竞态窗口。
 * commonTest 中只用串行调度器，无法覆盖 close/创建交错，因此放在 jvmTest。
 */
class DeepseekHttpClientPoolStressTest {

    private val url = "https://api.deepseek.com"

    private class AtomicCountingFactory(
        private val createDelayMillis: Long = 0,
    ) : DeepseekHttpClientFactory {
        val created = AtomicInteger()
        val createdClients = java.util.Collections.synchronizedList(mutableListOf<HttpClient>())
        val createdConfigs = java.util.Collections.synchronizedList(mutableListOf<DeepseekHttpClientConfig>())

        override fun create(config: DeepseekHttpClientConfig): HttpClient {
            if (createDelayMillis > 0) Thread.sleep(createDelayMillis)
            val client = HttpClient(MockEngine { respondOk() })
            created.incrementAndGet()
            createdClients += client
            createdConfigs += config
            return client
        }
    }

    @Test
    fun `high contention never duplicates a client per baseUrl`() = runBlocking {
        val factory = AtomicCountingFactory()
        val pool = DeepseekHttpClientPool(factory = factory)

        repeat(20) {
            val clients = (1..128).map { async(Dispatchers.Default) { pool.client(url) } }.awaitAll()
            assertTrue(clients.all { it === clients.first() }, "同一 baseUrl 高并发下只应有一个客户端")
        }
        assertEquals(1, factory.created.get(), "无 close 时全程只创建一次")
    }

    @Test
    fun `close churn under real parallelism never throws and only serves fresh clients afterwards`() =
        runBlocking {
            val factory = AtomicCountingFactory(createDelayMillis = 2)
            val pool = DeepseekHttpClientPool(factory = factory)

            repeat(50) {
                val jobs = (1..32).map { async(Dispatchers.Default) { pool.client(url) } }
                delay(1)
                val createdBeforeClose = factory.createdClients.size
                pool.close()

                // close() 返回时仍未完成的调用，返回的必须是 close 之后创建的客户端
                val inFlightClients = jobs.filter { !it.isCompleted }.map { it.await() }
                inFlightClients.forEach { client ->
                    assertTrue(
                        factory.createdClients.indexOf(client) >= createdBeforeClose,
                        "close 后完成的调用不应返回 close 之前创建的客户端",
                    )
                    withTimeout(5_000) { client.get(url) }
                }

                val after = pool.client(url)
                assertTrue(
                    factory.createdClients.indexOf(after) >= createdBeforeClose,
                    "close 后调用 client() 不应返回旧客户端",
                )
                withTimeout(5_000) { after.get(url) }
            }
            assertTrue(factory.created.get() >= 50, "每轮 close 后至少应重建一次客户端")
        }

    @Test
    fun `config replacement during in-flight creation is honored`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val factory = AtomicCountingFactory()
        val pool = DeepseekHttpClientPool(
            factory = DeepseekHttpClientFactory { config ->
                factory.createdConfigs += config
                if (factory.createdConfigs.size == 1) {
                    runBlocking {
                        started.complete(Unit)
                        release.await()
                    }
                }
                val client = HttpClient(MockEngine { respondOk() })
                factory.createdClients += client
                factory.created.incrementAndGet()
                client
            },
        )

        val createJob = async(Dispatchers.Default) { pool.client(url) }
        started.await()
        pool.config = DeepseekHttpClientConfig(maxRetries = 7)
        release.complete(Unit)

        val first = withTimeout(10_000) { createJob.await() }
        val second = pool.client(url)

        assertSame(first, second, "close 后创建的新客户端应成为唯一缓存条目")
        val usedConfig = factory.createdConfigs[factory.createdClients.indexOf(second)]
        assertEquals(7, usedConfig.maxRetries, "close 后缓存的客户端必须使用新配置创建")
        withTimeout(5_000) { second.get(url) }
        Unit
    }
}
