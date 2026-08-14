package io.github.hatoyuze.deepseek.protocol.net

import io.github.hatoyuze.deepseek.protocol.api.CountingHttpClientFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeepseekHttpClientPoolConcurrencyTest {

    private val url = "https://api.deepseek.com"

    @Test
    fun `concurrent client calls for the same baseUrl create exactly one HttpClient`() = runTest {
        val factory = CountingHttpClientFactory()
        val pool = DeepseekHttpClientPool(factory = factory)

        val clients = (1..64).map { async { pool.client(url) } }.awaitAll()

        assertTrue(clients.all { it === clients.first() }, "同一 baseUrl 的所有并发调用应返回同一实例")
        assertEquals(1, factory.created, "并发调用只应创建一次客户端")
    }

    @Test
    fun `concurrent calls across distinct baseUrls create exactly one client per url`() = runTest {
        val factory = CountingHttpClientFactory()
        val pool = DeepseekHttpClientPool(factory = factory)
        val urls = (1..16).map { "https://api.deepseek.com/v$it" }

        val byUrl = urls.map { url -> (1..8).map { async { pool.client(url) } }.awaitAll() }

        byUrl.forEach { clients ->
            assertTrue(clients.all { it === clients.first() }, "同一 baseUrl 的并发调用应返回同一实例")
        }
        for (i in urls.indices) {
            for (j in i + 1 until urls.size) {
                assertNotSame(byUrl[i].first(), byUrl[j].first(), "不同 baseUrl 不应共享客户端")
            }
        }
        assertEquals(16, factory.created, "每个 baseUrl 只应创建一次")
    }

    @Test
    fun `failed factory create is not cached and later call retries`() = runTest {
        val factory = CountingHttpClientFactory(failFirst = 3)
        val pool = DeepseekHttpClientPool(factory = factory)

        repeat(3) {
            assertFailsWith<IllegalStateException>("失败的创建不应被缓存") { pool.client(url) }
        }

        val a = pool.client(url)
        val b = pool.client(url)

        assertSame(a, b, "成功创建后应被缓存复用")
        assertEquals(1, factory.created, "三次失败后第四次创建成功且只创建一次")
    }

    @Test
    fun `config replacement racing client calls leaves a usable cache`() = runTest {
        val factory = CountingHttpClientFactory()
        val pool = DeepseekHttpClientPool(factory = factory)

        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        repeat(20) {
            jobs += launch { pool.client(url) }
        }
        repeat(5) { i ->
            pool.config = DeepseekHttpClientConfig(maxRetries = i + 1)
        }
        jobs += launch { pool.client(url) }
        withTimeout(10_000) { jobs.joinAll() }

        val final = pool.client(url)
        assertTrue(
            jobs.all { it.isCompleted && !it.isCancelled },
            "配置替换与并发 client() 交错不应让任何调用挂起",
        )
        assertTrue(factory.created == 1, "配置替换后应只重建一个客户端")
    }

    @Test
    fun `close with concurrent client calls does not deadlock and pool stays usable`() = runTest {
        val factory = CountingHttpClientFactory()
        val pool = DeepseekHttpClientPool(factory = factory)
        val before = pool.client(url)

        pool.close()
        val clients = (1..32).map { async { pool.client(url) } }.awaitAll()

        assertTrue(clients.all { it !== before }, "close 后不应再返回旧客户端")
        assertTrue(clients.all { it === clients.first() }, "close 后并发调用应共享同一个新客户端")
        assertEquals(2, factory.created, "before 与 close 后各应创建一次")
    }

    @Test
    fun `factory replacement invalidates cached clients`() = runTest {
        val firstFactory = CountingHttpClientFactory()
        val secondFactory = CountingHttpClientFactory()
        val pool = DeepseekHttpClientPool(factory = firstFactory)

        val a = pool.client(url)
        pool.factory = secondFactory
        val b = pool.client(url)

        assertNotSame(a, b, "替换工厂后应重建客户端")
        assertEquals(1, firstFactory.created)
        assertEquals(1, secondFactory.created)
    }

    @Test
    fun `two pools stay independent under concurrent use`() = runTest {
        val factoryA = CountingHttpClientFactory()
        val factoryB = CountingHttpClientFactory()
        val poolA = DeepseekHttpClientPool(factory = factoryA)
        val poolB = DeepseekHttpClientPool(factory = factoryB)

        val clientsA = (1..16).map { async { poolA.client(url) } }.awaitAll()
        val clientsB = (1..16).map { async { poolB.client(url) } }.awaitAll()

        assertTrue(clientsA.all { it === clientsA.first() })
        assertTrue(clientsB.all { it === clientsB.first() })
        assertNotSame(clientsA.first(), clientsB.first(), "不同池不应共享客户端")
        assertEquals(1, factoryA.created)
        assertEquals(1, factoryB.created)
    }
}
