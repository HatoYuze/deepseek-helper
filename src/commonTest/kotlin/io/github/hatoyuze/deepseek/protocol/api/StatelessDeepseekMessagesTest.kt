package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.toolcall.dsl.parametersOf
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.pipeline.ToolCallHost
import io.github.hatoyuze.deepseek.toolcall.registry.ToolRegistry
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent

/**
 * [StatelessDeepseek.chatStream] 的完整 messages 重载：请求内容与「实例不留状态」语义固化。
 */
class StatelessDeepseekMessagesTest {

    private val system = Message(Role.System, MessageContent.of("sys"))

    private fun stateless(
        seen: MutableList<List<Message>>,
        prompt: String? = "sys",
    ): StatelessDeepseek = StatelessDeepseek(
        "test-key",
        testCore(
            singleSession = false,
            backend = GatedBackend { messages ->
                seen += messages
                flowOf(ChatChunk.Done(1, 1, 2, "stop"))
            },
            prompt = prompt,
        ),
    )

    @Test
    fun `chatStream with messages sends the construction prompt plus the given list verbatim`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = stateless(seen)
        val messages = listOf(
            Message(Role.User, MessageContent.of("u1")),
            Message(Role.Assistant, MessageContent.of("a1")),
            Message(Role.User, MessageContent.of("u2")),
        )

        val chunks = ds.chatStream(messages).toList()

        assertEquals(listOf(system) + messages, seen.single(), "请求体应为 prompt + 传入列表，且不追加 user")
        assertEquals(1, chunks.count { it is ChatChunk.Done })
    }

    @Test
    fun `chatStream with messages sends exactly the list when no prompt is set`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = stateless(seen, prompt = null)
        val messages = listOf(Message(Role.System, MessageContent.of("from database")), Message(Role.User, MessageContent.of("u1")))

        ds.chatStream(messages).toList()

        assertEquals(messages, seen.single())
    }

    @Test
    fun `chatStream with messages leaves no instance state across consecutive calls`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = stateless(seen)
        val first = listOf(Message(Role.User, MessageContent.of("first")))
        val second = listOf(
            Message(Role.User, MessageContent.of("second-1")),
            Message(Role.Assistant, MessageContent.of("second-2")),
            Message(Role.User, MessageContent.of("second-3")),
        )

        ds.chatStream(first).toList()
        ds.chatStream(second).toList()
        ds.chatStream(first).toList()

        assertEquals(
            listOf(listOf(system) + first, listOf(system) + second, listOf(system) + first),
            seen,
            "连续调用应各自独立，实例不得累积历史",
        )
    }

    @Test
    fun `chatStream with messages can be collected repeatedly with the same request body`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = stateless(seen)
        val messages = listOf(Message(Role.User, MessageContent.of("u1")))

        val response = ds.chatStream(messages)
        response.toList()
        response.toList()

        assertEquals(2, seen.size, "重复收集同一个 Flow 应各发一次请求")
        assertEquals(listOf(system, Message(Role.User, MessageContent.of("u1"))), seen[0])
        assertEquals(seen[0], seen[1], "重复收集不得复用上一轮的请求缓冲（Flow 是冷的）")
    }

    @Test
    fun `chatStream with messages snapshots the caller list at call time and never mutates it`() = runTest {
        val seen = mutableListOf<List<Message>>()
        val ds = stateless(seen)
        val messages = mutableListOf(Message(Role.User, MessageContent.of("u1")))

        val response = ds.chatStream(messages)
        messages.add(Message(Role.User, MessageContent.of("late")))

        response.toList()

        assertEquals(listOf(system, Message(Role.User, MessageContent.of("u1"))), seen.single(), "调用后再改列表不应影响本次请求")
        assertEquals(listOf(Message(Role.User, MessageContent.of("u1")), Message(Role.User, MessageContent.of("late"))), messages, "库不得修改调用方列表")
    }

    @Test
    fun `chatStream with messages runs the tool loop inside a request-local buffer`() = runTest {
        val seen = mutableListOf<List<Message>>()
        var calls = 0
        val ds = StatelessDeepseek(
            "test-key",
            testCore(
                singleSession = false,
                backend = GatedBackend { messages ->
                    seen += messages
                    calls++
                    if (calls == 1) {
                        flow {
                            emit(ChatChunk.ToolCallRequest(ToolCall("call_1", "ping", "{}")))
                            emit(ChatChunk.Done(1, 1, 2, "tool_calls"))
                        }
                    } else {
                        flow {
                            emit(ChatChunk.ContentDelta("pong"))
                            emit(ChatChunk.Done(2, 2, 4, "stop"))
                        }
                    }
                },
                prompt = "sys",
            ),
        )
        ds.toolHost = pingHost()

        ds.chatStream(listOf(Message(Role.User, MessageContent.of("u1")))).toList()

        assertEquals(2, seen.size, "工具调用应触发第二轮请求")
        val second = seen[1]
        assertEquals(listOf(system, Message(Role.User, MessageContent.of("u1"))), second.take(2))
        assertEquals(Role.Assistant, second[2].role)
        assertNotNull(second[2].toolCalls, "第二轮请求应带上 assistant 的 tool_calls")
        assertEquals(Role.Tool, second[3].role)

        // 工具循环的中间消息只存在于本次请求的局部缓冲里
        ds.chatStream(listOf(Message(Role.User, MessageContent.of("u2")))).toList()

        assertEquals(listOf(system, Message(Role.User, MessageContent.of("u2"))), seen[2], "后续调用不得带上上一轮的工具消息")
    }

    private fun pingHost(): ToolCallHost {
        val host = ToolCallHost(ToolRegistry())
        host.register("ping", "Ping the assistant", schema = parametersOf { }) { _, _ ->
            """{"reply":"pong"}"""
        }
        return host
    }
}
