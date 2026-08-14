package io.github.hatoyuze.deepseek.protocol.api.impl

import io.github.hatoyuze.deepseek.protocol.api.ChatConfig
import io.github.hatoyuze.deepseek.protocol.api.ExperimentalDeepseekApi
import io.github.hatoyuze.deepseek.protocol.api.FimChunk
import io.github.hatoyuze.deepseek.protocol.api.entity.FinishReason
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.entity.StopToken
import io.github.hatoyuze.deepseek.protocol.api.entity.Usage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalDeepseekApi::class)
class FimApiTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `buildFimRequest maps call args and chat config`() {
        val config = ChatConfig().apply {
            maxTokens = 128
            temperature = 0.7
            topP = 0.9
            stop = StopToken.Single("END")
            topLogprobs = 5
            includeUsage = false
        }

        val request = buildFimRequest(
            prompt = "def add(a, b):",
            suffix = "    return a + b",
            echo = true,
            model = Model.Pro,
            config = config,
        )
        val obj = json.parseToJsonElement(json.encodeToString(FimRequest.serializer(), request)).jsonObject

        assertEquals("deepseek-v4-pro", request.model)
        assertEquals("def add(a, b):", request.prompt)
        assertEquals("    return a + b", request.suffix)
        assertEquals(true, request.echo)
        assertEquals(128, request.maxTokens)
        assertEquals(0.7, request.temperature)
        assertEquals(0.9, request.topP)
        assertEquals(5, request.logprobs)
        assertNull(request.streamOptions)
        assertEquals("true", obj["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildFimRequest enables stream options when includeUsage is true`() {
        val request = buildFimRequest(
            prompt = "hello",
            suffix = null,
            echo = null,
            model = Model.Flash,
            config = ChatConfig(),
        )

        val options = request.streamOptions
        assertNotNull(options)
        assertEquals(true, options.includeUsage)
    }

    @Test
    fun `deserializes FIM text delta`() {
        val raw = """
            {"id":"cmpl-fim-1","object":"text_completion","created":1710000000,
             "model":"deepseek-v4-pro",
             "choices":[{"index":0,"text":"    return a + b","finish_reason":null,"logprobs":null}]}
        """.trimIndent()

        val chunk = json.decodeFromString<FimCompletionChunk>(raw)

        assertEquals("cmpl-fim-1", chunk.id)
        assertEquals(1, chunk.choices.size)
        assertEquals("    return a + b", chunk.choices[0].text)
        assertNull(chunk.choices[0].finishReason)
        assertNull(chunk.usage)
    }

    @Test
    fun `deserializes FIM final chunk with usage`() {
        val raw = """
            {"id":"cmpl-fim-2","object":"text_completion","created":1710000001,
             "model":"deepseek-v4-pro","choices":[],
             "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30,
                      "prompt_cache_hit_tokens":5,"prompt_cache_miss_tokens":5,
                      "completion_tokens_details":{"reasoning_tokens":8}}}
        """.trimIndent()

        val chunk = json.decodeFromString<FimCompletionChunk>(raw)

        assertEquals(10L, chunk.usage?.promptTokens)
        assertEquals(5L, chunk.usage?.promptCacheHitTokens)
        assertEquals(8L, chunk.usage?.reasoningTokens)
    }

    @Test
    fun `transform emits text deltas and a single Done with usage`() = runTest {
        val state = FimTransformState()
        val emitted = mutableListOf<FimChunk>()
        val emit: suspend (FimChunk) -> Unit = { emitted.add(it) }

        FimCompletionChunk(
            choices = listOf(FimCompletionChoice(index = 0, text = "Hello")),
        ).emitFimChunks(includeUsage = true, state = state, emit = emit)
        FimCompletionChunk(
            choices = listOf(FimCompletionChoice(index = 0, text = " world")),
            usage = Usage(10, 20, 30, reasoningTokens = 5),
        ).emitFimChunks(includeUsage = true, state = state, emit = emit)

        assertEquals(listOf("Hello", " world"), emitted.filterIsInstance<FimChunk.TextDelta>().map { it.text })
        val dones = emitted.filterIsInstance<FimChunk.Done>()
        assertEquals(1, dones.size)
        assertEquals(Usage(10, 20, 30, reasoningTokens = 5), dones[0].usage)
    }

    @Test
    fun `transform emits Done on finish chunk when includeUsage is false`() = runTest {
        val state = FimTransformState()
        val emitted = mutableListOf<FimChunk>()
        val emit: suspend (FimChunk) -> Unit = { emitted.add(it) }

        FimCompletionChunk(
            choices = listOf(FimCompletionChoice(index = 0, text = "", finishReason = FinishReason.STOP)),
        ).emitFimChunks(includeUsage = false, state = state, emit = emit)

        assertEquals(0, emitted.filterIsInstance<FimChunk.TextDelta>().size)
        val dones = emitted.filterIsInstance<FimChunk.Done>()
        assertEquals(1, dones.size)
        assertEquals(Usage(0, 0, 0), dones[0].usage)
        assertEquals("stop", dones[0].finishReason)
    }

    @Test
    fun `transform emits Done only once when finish and usage share a chunk`() = runTest {
        val state = FimTransformState()
        val emitted = mutableListOf<FimChunk>()
        val emit: suspend (FimChunk) -> Unit = { emitted.add(it) }

        FimCompletionChunk(
            choices = listOf(FimCompletionChoice(index = 0, text = "", finishReason = FinishReason.STOP)),
            usage = Usage(1, 2, 3),
        ).emitFimChunks(includeUsage = true, state = state, emit = emit)

        assertEquals(1, emitted.count { it is FimChunk.Done })
        assertEquals(Usage(1, 2, 3), emitted.filterIsInstance<FimChunk.Done>().single().usage)
    }
}
