package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserializes completion tokens details into flat reasoningTokens`() {
        val raw = """
            {"prompt_tokens":10,"completion_tokens":20,"total_tokens":30,
             "prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":5,
             "completion_tokens_details":{"reasoning_tokens":8}}
        """.trimIndent()

        val usage = json.decodeFromString<Usage>(raw)

        assertEquals(10L, usage.promptTokens)
        assertEquals(5L, usage.promptCacheHitTokens)
        assertEquals(5L, usage.promptCacheMissTokens)
        assertEquals(8L, usage.reasoningTokens)
    }

    @Test
    fun `serializes flat reasoningTokens into nested completion tokens details`() {
        val usage = Usage(
            promptTokens = 1,
            completionTokens = 2,
            totalTokens = 3,
            promptCacheHitTokens = 4,
            promptCacheMissTokens = 5,
            reasoningTokens = 9,
        )

        val obj = json.parseToJsonElement(json.encodeToString(Usage.serializer(), usage)).jsonObject

        assertEquals(9L, obj["completion_tokens_details"]!!.jsonObject["reasoning_tokens"]!!.jsonPrimitive.long)
        assertEquals(4L, obj["prompt_cache_hit_tokens"]!!.jsonPrimitive.long)
        assertEquals(5L, obj["prompt_cache_miss_tokens"]!!.jsonPrimitive.long)
    }

    @Test
    fun `round trip preserves cache and reasoning fields`() {
        val original = Usage(10, 20, 30, promptCacheHitTokens = 5, promptCacheMissTokens = 5, reasoningTokens = 8)

        val restored = json.decodeFromString<Usage>(json.encodeToString(Usage.serializer(), original))

        assertEquals(original, restored)
    }
}
