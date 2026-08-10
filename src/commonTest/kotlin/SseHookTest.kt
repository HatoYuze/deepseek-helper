import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.github.hatoyuze.protocol.api.ChatChunk
import com.github.hatoyuze.protocol.api.SseHook
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SseHookTest {

    @Test
    fun `receives each emitted chunk`() = runBlocking {
        val received = mutableListOf<ChatChunk>()
        val hook = SseHook { chunk -> received.add(chunk) }

        // 模拟 SSE 流中的 chunk 回调
        hook.onChunk(ChatChunk.ContentDelta("Hi"))
        hook.onChunk(ChatChunk.ContentDelta(" there"))
        hook.onChunk(ChatChunk.Done(5, 10, 15))

        assertEquals(3, received.size)
        assertIs<ChatChunk.ContentDelta>(received[0])
        assertIs<ChatChunk.ContentDelta>(received[1])
        assertIs<ChatChunk.Done>(received[2])
        assertEquals("Hi", (received[0] as ChatChunk.ContentDelta).content)
        assertEquals(15L, (received[2] as ChatChunk.Done).totalTokens)
    }

}
