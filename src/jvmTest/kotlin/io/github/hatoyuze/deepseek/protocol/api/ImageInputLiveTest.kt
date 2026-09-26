package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.ThinkingMode
import io.github.hatoyuze.deepseek.protocol.api.dsl.buildDeepseekMessages
import io.github.hatoyuze.deepseek.protocol.api.dsl.imageFileOf
import io.github.hatoyuze.deepseek.protocol.api.dsl.plus
import io.github.hatoyuze.deepseek.protocol.api.dsl.imageOf
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.openFileSource
import io.github.hatoyuze.deepseek.protocol.api.image.fileSourceOfResource
import io.github.hatoyuze.deepseek.protocol.api.image.imageBytesOfResource
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * 真实读取 `sample.jpg` 并**真的调用模型**的图片输入集成测试。
 *
 * 与 [SampleImageInputTest]（离线、CI 必跑）的分工：
 * - 这里验证的是「模型确实看见了这张图」，因此必须出网、必须有 key，缺失时整体跳过
 * - 离线那一层验证「字节、编码、请求体、脱敏」这些可以在本地判定的部分
 *
 * ## 运行方式
 *
 * ```bash
 * export DEEPSEEK_API_KEY=sk-...
 * ./gradlew jvmTest --tests '*ImageInputLiveTest*'
 * # 或： ./gradlew jvmTest --tests '*ImageInputLiveTest*' -Ddeepseek.api.key=sk-...
 * ```
 *
 * ## 为什么 maxTokens 要放宽
 *
 * 读图与纯文本对话的 token 消耗结构不同：
 * - 输入侧一张 960×788 的图会被换算成图像 token（官方：单图上限 1024 token），
 *   与文本 token 一起计入 `prompt_tokens`；
 * - 输出侧模型通常会**先描述再回答**，比「用一句话介绍 Kotlin」这类问答长得多，
 *   开思考模式（`reasoning_effort`）时还要额外消耗推理 token。
 *
 * 因此这里统一用 [MAX_TOKENS_FOR_VISION]（4096）而不是其它用例的 128/1024：
 * token 上限过小会得到 `finish_reason=length` 的半截回复，看起来像「模型没看懂图」。
 */
class ImageInputLiveTest {

    companion object {
        /**
         * 图片输入专用 token 上限：给「图像 token + 较长的描述 + 可能的思考」留足空间。
         *
         * 对比其它用例：纯文本问答用 128～1024 就够，读图按 4096 起步更稳。
         */
        private const val MAX_TOKENS_FOR_VISION = 4096

        /** 图像 + 思考 + 描述通常比纯文本慢，超时放宽到 3 分钟 */
        private val IMAGE_TIMEOUT = 180.seconds

        fun resolveApiKey(): String =
            System.getenv("DEEPSEEK_API_KEY") ?: System.getProperty("deepseek.api.key") ?: ""
    }

    /**
     * 未配置密钥时跳过（而不是失败），保证 CI 无密钥也能通过。
     *
     * 用**实例级** `@Before` 而不是 `companion object` 里的 `@JvmStatic @BeforeClass`：
     * 后者会让 Gradle 的 JUnit4 扫描直接丢弃整个测试类（表现为
     * `No tests found for given includes`），`CliClient.kt` 的线上用例正是这样一直没跑起来。
     */
    @Before
    fun requireApiKey() {
        assumeTrue(
            "未配置 DEEPSEEK_API_KEY（环境变量或 -Ddeepseek.api.key），跳过线上图片输入测试",
            resolveApiKey().isNotBlank(),
        )
    }

    private val apiKey: String by lazy { resolveApiKey() }

    /** 夹具：`src/jvmTest/resources/sample.jpg`（随测试 classpath 提供，不放在项目根目录） */
    private val sampleResource = "/sample.jpg"

    private fun sampleBytes(): ByteArray = imageBytesOfResource(sampleResource)

    // ── 方式① base64 内联 ──

    @Test
    fun `inline base64 image is understood by the model`() = runBlocking<Unit> {
        withTimeout(IMAGE_TIMEOUT) {
            val ds = deepseek(apiKey) {
                model { flash() } // 官方文档：deepseek-flash 支持图像输入
                config {
                    maxTokens = MAX_TOKENS_FOR_VISION
                    thinkingMode = ThinkingMode.Disabled // 聚焦「看得见」，不额外消耗推理 token
                }
            }

            // 用新 DSL 组装（用户可读性优先）：imageOf 的 ByteArray 重载会按魔数判定 MIME 并内联
            val messages = buildDeepseekMessages {
                val image = imageOf(sampleBytes())
                Role.User says image + (
                    "用中文回答：这张图片里有什么？" +
                        "请列出你能看到的主要对象，并给出你估计的主色调。"
                    )
            }
            assertEquals(1, messages.size, "DSL 应产出恰好一条 user 消息")
            val userText = messages.single().content?.asText()
            assertTrue(message = "DSL 组装的文本块应可读回", actual = userText!!.startsWith("用中文回答"))

            val response = ds.chatStream(messages.single().content!!)
                .onContent { print(it) }
                .collectResponse()

            println("\n✅ 回复: ${response.content.take(200)}...")
            println("✅ 用量: prompt=${response.usage.promptTokens} completion=${response.usage.completionTokens}")

            assertTrue(message = "读图回复不应为空", actual = response.content.isNotBlank())
            assertTrue(message = "提示词 token 应 > 0（含图像 token）", actual = response.usage.promptTokens > 0)
            assertTrue(
                message = "带图的 prompt token 应显著高于纯文本（图像 token 计入其中），实际 ${response.usage.promptTokens}",
                actual = response.usage.promptTokens > 100,
            )
        }
    }

    // ── 方式③ Files API 上传后按 file_id 复用 ──

    @Test
    fun `uploaded image can be reused through its file id`() = runBlocking<Unit> {
        withTimeout(IMAGE_TIMEOUT) {
            val ds = deepseek(apiKey) {
                model { flash() }
                config {
                    maxTokens = MAX_TOKENS_FOR_VISION
                    thinkingMode = ThinkingMode.Disabled
                }
            }
            val files = ds.files()

            // 上传真实文件：走 FileSource.Path 的平台实现 + multipart
            val uploaded = fileSourceOfResource(sampleResource).use { source ->
                files.upload(source, mimeType = "image/jpeg", filename = "sample.jpg")
            }
            println("✅ 已上传: ${uploaded.id} (${uploaded.bytes} 字节, ${uploaded.filename})")
            assertEquals(
                message = "服务端记录的大小应与本地夹具一致",
                expected = sampleBytes().size.toLong(),
                actual = uploaded.bytes,
            )

            try {
                // 校验一次「上传的内容 = 本地文件」：下载不了，但至少确认服务端记的大小/名字对得上
                val fetched = files.retrieve(uploaded.id)
                assertEquals(message = "取回的应是同一个文件", expected = uploaded.id, actual = fetched.id)
                assertEquals(message = "上传后取回的文件名应保持不变", expected = "sample.jpg", actual = fetched.filename)

                // 同一张图复用两次，验证 file_id 路径可用
                val ask = buildDeepseekMessages {
                    // file_id 形态：复用已上传的文件，不重复传字节（用具名工厂而不是 imageOf ——
                    // 字符串参数在 imageOf 里表示 URL/本地路径，file_id 要显式表达）
                    Role.User says imageFileOf(uploaded.id) + "这张图片的主体是什么？用一句话回答。"
                }.single()
                val answer = ds.chatStream(ask.content!!)
                    .onContent { print(it) }
                    .collectResponse()

                assertTrue(message = "按 file_id 引用的读图回复不应为空", actual = answer.content.isNotBlank())
                println("\n✅ file_id 读图回复: ${answer.content.take(120)}")

                val second = buildDeepseekMessages {
                    Role.User says imageFileOf(uploaded.id) + "再看一眼：这张图是横向的还是纵向的？"
                }.single()
                val secondAnswer = ds.chatStream(second.content!!).collectResponse()
                assertTrue(message = "第二次复用 file_id 也应有回复", actual = secondAnswer.content.isNotBlank())
            } finally {
                // 不留垃圾：删除上传的文件
                val deletion = files.delete(uploaded.id)
                assertTrue(message = "删除上传文件应成功", actual = deletion.deleted)
                println("✅ 已删除: ${deletion.id}")
            }
        }
    }
}
