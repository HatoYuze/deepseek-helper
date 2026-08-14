package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.impl.DeepseekApiBackend
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeepseekCoreStreamTest {

    private class FailingBackend : DeepseekApiBackend {
        override suspend fun models(): List<Model> = emptyList()
        override suspend fun userBalance(): UserBalance = UserBalance(true, emptyList())
        override suspend fun completions(
            messages: List<Message>,
            model: Model,
            config: ChatConfig,
            tools: List<ToolDefinition>?,
        ): Flow<ChatChunk> = flow { throw IllegalStateException("boom") }
    }

    private class TwoDoneBackend : DeepseekApiBackend {
        override suspend fun models(): List<Model> = emptyList()
        override suspend fun userBalance(): UserBalance = UserBalance(true, emptyList())
        override suspend fun completions(
            messages: List<Message>,
            model: Model,
            config: ChatConfig,
            tools: List<ToolDefinition>?,
        ): Flow<ChatChunk> = flow {
            emit(ChatChunk.ContentDelta("hi"))
            emit(ChatChunk.Done(1, 1, 2, "tool_calls"))
            emit(ChatChunk.ContentDelta(" world"))
            emit(ChatChunk.Done(2, 2, 4, "stop"))
        }
    }

    @Test
    fun `streamLoop rolls back history on failure`() = runTest {
        val core = DeepseekCore(
            apiKey = "k",
            model = null,
            prompt = "sys",
            config = ChatConfig(),
            api = DeepseekApi.STANDARD,
            backend = FailingBackend(),
        )
        val history = mutableListOf<Message>().apply {
            core.systemPromptMessage?.let { add(it) }
        }

        assertFailsWith<IllegalStateException> {
            core.streamFlow { session ->
                streamLoop(core, history, "hello", null, session)
            }.collect { }
        }

        assertEquals(1, history.size, "失败后应回滚 user 消息，仅剩 system prompt")
        assertEquals(Role.System, history[0].role)
    }

    @Test
    fun `hook receives only outer-visible events with single final Done`() = runTest {
        val core = DeepseekCore(
            apiKey = "k",
            model = null,
            prompt = null,
            config = ChatConfig(),
            api = DeepseekApi.STANDARD,
            backend = TwoDoneBackend(),
        )
        val seen = mutableListOf<Chunk>()
        val flowSeen = mutableListOf<Chunk>()
        val history = mutableListOf<Message>()
        val hook = SseHook { seen.add(it) }

        core.streamFlow { session ->
            streamLoop(core, history, "hi", hook, session)
        }.collect { flowSeen.add(it) }

        assertEquals(seen, flowSeen, "hook 与 Flow 事件应完全一致")
        val dones = seen.filterIsInstance<ChatChunk.Done>()
        assertEquals(1, dones.size, "工具循环中的中间 Done 不应回调给 hook")
        assertEquals(3L, dones[0].promptTokens)
        assertEquals(3L, dones[0].completionTokens)
        assertEquals(6L, dones[0].totalTokens)
        assertTrue(history.isNotEmpty(), "成功后 assistant 回复应写入历史")
    }
}
