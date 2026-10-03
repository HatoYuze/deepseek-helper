package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * 线上用例：确定性复现上游工具通道关闭时的内部语法泄漏，并验证库把它挡在正文之外。
 *
 * 触发条件来自公开 issue [deepseek-ai/DeepSeek-V3#1678](https://github.com/deepseek-ai/DeepSeek-V3/issues/1678)：
 * 历史里带着 `assistant.tool_calls` 与 `role=tool`，而本次请求**不发送 `tools`**（这里故意不装配
 * `ToolCallHost`）——服务端不会把模型的原生工具调用语法转成结构化 `tool_calls`，而是当作正文下发。
 *
 * 断言只落在「库的输出」上，因此对上游行为变化是稳健的：**无论上游这次是泄漏还是正常用文字回答，
 * 正文里都不能出现内部语法**。未配置密钥时跳过（CI 无密钥也能通过）。
 */
class DsmlLeakLiveTest {

    @Before
    fun requireApiKey() {
        assumeTrue(
            "未配置 DEEPSEEK_API_KEY（环境变量或 -Ddeepseek.api.key），跳过线上泄漏用例",
            resolveApiKey().isNotBlank(),
        )
    }

    private val apiKey: String by lazy { resolveApiKey() }

    @Test
    fun `history with tool turns but no tools never surfaces internal markup`() = runBlocking<Unit> {
        withTimeout(3.minutes) {
            val ds = deepseek(apiKey) {
                model { flash() }
                config {
                    // 关掉思考：本用例只关心工具通道关闭时的下发形态，不想让推理吃掉生成预算
                    thinkingMode = ThinkingMode.Disabled
                    maxTokens = 512
                    temperature = 0.0
                }
            }
            // 故意不装配 toolHost ⇒ tools=null ⇒ 命中上游确定性触发条件
            ds.replaceHistory(
                listOf(
                    Message(Role.System, MessageContent.of("You are a helpful assistant with tool access.")),
                    Message(Role.User, MessageContent.of("Look up parcel TRK-4471-A with package_tracker_lookup.")),
                    Message(
                        Role.Assistant,
                        content = null,
                        toolCalls = listOf(
                            ToolCall(
                                id = "seed-1",
                                name = "package_tracker_lookup",
                                arguments = """{"tracking_number":"TRK-4471-A"}""",
                            ),
                        ),
                    ),
                    Message(Role.Tool, MessageContent.of("status=delivered"), toolCallId = "seed-1"),
                    Message(
                        Role.User,
                        MessageContent.of(
                            "Now also look up parcel TRK-9902-C the same way. " +
                                "You must call the same tool; do not answer from memory.",
                        ),
                    ),
                ),
            )

            val chunks = ds.continueStream().toList()

            val content = chunks.filterIsInstance<ChatChunk.ContentDelta>().joinToString("") { it.content }
            assertFalse(content.contains("DSML"), "内部工具调用语法绝不能进正文，实际：$content")
            assertTrue(
                chunks.filterIsInstance<ChatChunk.ToolCallRequest>().isEmpty(),
                "没有装配 toolHost 时不得执行任何调用",
            )
            assertTrue(
                // 历史里本来就有一条 seed 的 assistant(tool_calls)；这里断言"没有新写进去的"
                ds.messages.count { it.role == Role.Assistant && it.toolCalls != null } == 1,
                "被剔除的调用不能写进历史，实际历史：${ds.messages.map { it.role }}",
            )
        }
    }

    private fun resolveApiKey(): String =
        System.getenv("DEEPSEEK_API_KEY") ?: System.getProperty("deepseek.api.key") ?: ""
}
