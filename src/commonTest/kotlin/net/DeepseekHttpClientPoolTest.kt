package com.github.hatoyuze.protocol.net

import org.junit.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class DeepseekHttpClientPoolTest {

    @Test
    fun `same baseUrl shares the same HttpClient`() {
        val a = DeepseekHttpClientPool.client("https://api.deepseek.com")
        val b = DeepseekHttpClientPool.client("https://api.deepseek.com")
        val c = DeepseekHttpClientPool.client("https://api.deepseek.com/beta")

        assertSame(a, b)
        assertNotSame(a, c)
    }
}
