import org.junit.Test
import com.github.hatoyuze.protocol.api.ChatChunk
import com.github.hatoyuze.tool.executor.ToolCall
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ChatChunkTest {

    @Test
    fun `ContentDelta construction`() {
        val chunk = ChatChunk.ContentDelta("Hello", "Let me think...")
        assertEquals("Hello", chunk.content)
        assertEquals("Let me think...", chunk.reasoningContent)
        assertIs<ChatChunk>(chunk)
    }

    @Test
    fun `ContentDelta with null reasoning`() {
        val chunk = ChatChunk.ContentDelta("Hi")
        assertEquals("Hi", chunk.content)
        assertNull(chunk.reasoningContent)
    }

    @Test
    fun `ToolCallRequest construction`() {
        val chunk = ChatChunk.ToolCallRequest(ToolCall("call_001", "get_weather", "{\"location\":\"HZ\"}"))
        assertEquals("call_001", chunk.call.id)
        assertEquals("get_weather", chunk.call.name)
        assertEquals("{\"location\":\"HZ\"}", chunk.call.arguments)
        assertIs<ChatChunk>(chunk)
    }

    @Test
    fun `Done construction`() {
        val chunk = ChatChunk.Done(10, 50, 60)
        assertEquals(10L, chunk.promptTokens)
        assertEquals(50L, chunk.completionTokens)
        assertEquals(60L, chunk.totalTokens)
        assertIs<ChatChunk>(chunk)
    }

    @Test
    fun `sealed hierarchy covers all subtypes`() {
        val content = ChatChunk.ContentDelta("x")
        val tool = ChatChunk.ToolCallRequest(ToolCall("id", "fn", "{}"))
        val done = ChatChunk.Done(0, 0, 0)

        // when 分支应覆盖所有子类（无 else 分支可编译通过）
        val results = listOf(content, tool, done).map { chunk ->
            when (chunk) {
                is ChatChunk.ContentDelta -> "content:${chunk.content}"
                is ChatChunk.ToolCallRequest -> "tool:${chunk.call.name}"
                is ChatChunk.ToolResultData -> "result:${chunk.content}"
                is ChatChunk.Done -> "done:${chunk.totalTokens}"
            }
        }
        assertEquals(3, results.size)
    }
}
