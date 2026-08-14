package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Usage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalDeepseekApi::class)
class FimChunkTest {

    @Test
    fun `FimChunk subtypes share the Chunk ancestor`() {
        val delta: Chunk = FimChunk.TextDelta("hello")
        val done: Chunk = FimChunk.Done(Usage(1, 2, 3))

        assertIs<FimChunk>(delta)
        assertIs<FimChunk>(done)
        assertEquals("hello", (delta as FimChunk.TextDelta).text)
        assertEquals(3L, (done as FimChunk.Done).usage.totalTokens)
    }

    @Test
    fun `collectFimResponse aggregates text usage and finishReason`() = runTest {
        val flow = flowOf(
            FimChunk.TextDelta("Hello"),
            FimChunk.TextDelta(" world"),
            FimChunk.Done(Usage(10, 20, 30), "stop"),
        )

        val response = flow.collectFimResponse()

        assertEquals("Hello world", response.text)
        assertEquals(Usage(10, 20, 30), response.usage)
        assertEquals("stop", response.finishReason)
    }

    @Test
    fun `collectFimResponse falls back to zero usage without Done`() = runTest {
        val response = flowOf(FimChunk.TextDelta("only text")).collectFimResponse()

        assertEquals("only text", response.text)
        assertEquals(Usage(0, 0, 0), response.usage)
        assertEquals(null, response.finishReason)
    }
}
