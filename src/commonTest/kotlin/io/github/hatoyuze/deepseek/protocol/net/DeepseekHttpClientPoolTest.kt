package io.github.hatoyuze.deepseek.protocol.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DeepseekHttpClientPoolTest {

    @Test
    fun `same pool shares the same HttpClient per baseUrl`() = runTest {
        val pool = DeepseekHttpClientPool()
        val a = pool.client("https://api.deepseek.com")
        val b = pool.client("https://api.deepseek.com")
        val c = pool.client("https://api.deepseek.com/beta")

        assertSame(a, b)
        assertNotSame(a, c)
    }

    @Test
    fun `different config creates separate clients`() = runTest {
        val default = DeepseekHttpClientPool()
        val custom = DeepseekHttpClientPool(DeepseekHttpClientConfig(maxRetries = 2))

        assertNotSame(
            default.client("https://api.deepseek.com"),
            custom.client("https://api.deepseek.com"),
        )
    }

    @Test
    fun `config replacement invalidates cached client`() = runTest {
        val pool = DeepseekHttpClientPool()
        val first = pool.client("https://api.deepseek.com")

        pool.config = pool.config.copy(maxRetries = 2)
        val second = pool.client("https://api.deepseek.com")

        assertNotSame(first, second)
    }

    @Test
    fun `close clears cache and recreates client on next use`() = runTest {
        val pool = DeepseekHttpClientPool()
        val first = pool.client("https://api.deepseek.com")

        pool.close()
        val second = pool.client("https://api.deepseek.com")

        assertNotSame(first, second)
    }

    @Test
    fun `global pool is shared across instances`() = runTest {
        assertSame(
            DeepseekHttpClientPool.Global.client("https://api.deepseek.com"),
            DeepseekHttpClientPool.Global.client("https://api.deepseek.com"),
        )
    }
}
