package io.github.hatoyuze.deepseek.protocol.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DeepseekHttpClientPoolTest {

    @Test
    fun `same baseUrl shares the same HttpClient`() = runTest {
        val a = DeepseekHttpClientPool.client("https://api.deepseek.com")
        val b = DeepseekHttpClientPool.client("https://api.deepseek.com")
        val c = DeepseekHttpClientPool.client("https://api.deepseek.com/beta")

        assertSame(a, b)
        assertNotSame(a, c)
    }
}
