package io.github.hatoyuze.deepseek.protocol.api.impl

import io.github.hatoyuze.deepseek.protocol.api.ChatChunk
import io.github.hatoyuze.deepseek.protocol.api.ChatConfig
import io.github.hatoyuze.deepseek.protocol.api.entity.ContentPart
import io.github.hatoyuze.deepseek.protocol.api.entity.Message
import io.github.hatoyuze.deepseek.protocol.api.entity.MessageContent
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import io.github.hatoyuze.deepseek.protocol.api.entity.Role
import io.github.hatoyuze.deepseek.protocol.api.entity.UserBalance
import io.github.hatoyuze.deepseek.protocol.net.DeepseekHttpClientPool
import io.github.hatoyuze.deepseek.protocol.net.HttpHookRegistry
import io.github.hatoyuze.deepseek.protocol.net.Network
import io.github.hatoyuze.deepseek.protocol.net.collectHeaders
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.client.plugins.sse.SSEClientException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import io.github.hatoyuze.deepseek.protocol.api.DeepseekJson
import kotlinx.serialization.json.Json


/**
 * 官方 DeepSeek API 服务地址。
 *
 * 客户端未显式指定 baseUrl 时，chat / models / balance / FIM 请求都发送到该地址；
 * 各后端实现（STANDARD / RESPONSES / FIM）以它作为 baseUrl 参数的默认值。
 */
internal const val DEFAULT_BASE_URL = "https://api.deepseek.com"

/**
 * 发起对话补全请求前的统一校验入口。
 *
 * 两个后端（STANDARD / RESPONSES）都必须在**装配请求体、发起网络请求之前**调用它：
 * 校验失败要变成调用方立刻可见的异常，而不是一次注定 400 的往返。
 *
 * @throws IllegalArgumentException 非 user 消息携带图片内容块时
 */
internal fun List<Message>.requireCompletionsInputAllowed(): List<Message> {
    requireImagesAllowed()
    return this
}

internal interface DeepseekApiBackend {
    suspend fun models(): List<Model>

    suspend fun userBalance(): UserBalance

    suspend fun completions(
        messages: List<Message>,
        model: Model,
        config: ChatConfig,
        tools: List<ToolDefinition>? = null,
    ): Flow<ChatChunk>
}

/**
 * 校验一批消息里没有把图片放在不允许的角色上。
 *
 * 官方限制：图片（`image_url` / `file` / `input_image` 内容块）**只能出现在 user 消息中**，
 * system 或 assistant 消息携带图片会返回 `400`。两个后端在装配请求体前都调用本方法，
 * 把「必定失败的请求」变成调用方立刻可见的 [IllegalArgumentException]。
 *
 * @throws IllegalArgumentException 非 user 消息携带图片内容块时
 */
internal fun List<Message>.requireImagesAllowed() {
    forEach { it.requireImagesAllowed() }
}

/**
 * 校验单条消息里没有把图片放在不允许的角色上（见 [requireImagesAllowed]）。
 *
 * @throws IllegalArgumentException 非 user 消息携带图片内容块时
 */
internal fun Message.requireImagesAllowed() {
    if (role == Role.User) return
    require(content.allowsImages()) {
        "图片只能出现在 user 消息中（当前 role=${role.name.lowercase()}）；" +
            "system / assistant / tool 消息携带图片会被服务端以 400 拒绝"
    }
}

/**
 * 内容里是否没有图片块（图片只能出现在 user 消息中）。
 *
 * 供 `streamLoop` 在把内容当作 user 消息前自检，避免为了校验而先造一条临时 [Message]。
 */
internal fun MessageContent?.allowsImages(): Boolean {
    val parts = (this as? MessageContent.Parts)?.parts ?: return true
    return parts.none { it is ContentPart.ImagePart || it is ContentPart.FilePart }
}


internal abstract class DeepseekApiBase(
    apiKey: String,
    baseUrl: String,
    pool: DeepseekHttpClientPool,
) : DeepseekApiBackend {

    protected val net = Network(baseUrl, apiKey, pool)

    override suspend fun models(): List<Model> {
        @Serializable
        data class Response(val data: List<Model>)
        return net.call<Response>("/models").data
    }

    override suspend fun userBalance(): UserBalance = net.call("/user/balance")
}

/**
 * 进程级 SSE 解析错误调试槽。
 *
 * 仅保存最近一次解析失败的原始片段（last-writer-wins）；非线程安全，
 * 仅供本地调试，不构成任何 API 契约。
 */
internal object DeepseekSseErrors {
    var lastSseError: String? = null
}


internal inline fun <reified T> Network.sseStream(
    action: String,
    bodyJson: String,
    json: Json,
    method: HttpMethod = HttpMethod.Post,
): Flow<T> {
    val hostUrl = this.host
    val methodStr = method.value.uppercase()
    val fullUrl = hostUrl + action
    return callbackFlow {
        try {
            executeSSE(action, requestBody = bodyJson, {
                this.method = method
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }, onResponse = { response ->
                checkHttpStatus(response)
                // onResponse hook is fired by executeSSE after the stream ends (with accumulated SSE body)
            }) { event ->
                val data = event.data ?: return@executeSSE
                if (data == "[DONE]") {
                    close()
                    return@executeSSE
                }
                try {
                    val decoded = json.decodeFromString<T>(data)
                    send(decoded)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Store for debugging — tool call deltas with missing fields end up here
                    DeepseekSseErrors.lastSseError = "SSE: ${e.message} — ${data.take(200)}"
                }
            }
        } catch (e: SSEClientException) {
            // Ktor SSE 在 onResponse 之前就会对非 200 抛异常。
            // 通过一次非流式请求获取完整错误信息，复用 checkHttpStatus 处理。
            val self = this@sseStream
            val response = self.execute(action, method = methodStr, requestBody = bodyJson) {
                this.method = method
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            }
            val body = try { response.bodyAsText() } catch (_: Exception) { null }
            HttpHookRegistry.forEach { hook ->
                hook.onResponse(methodStr, fullUrl, response.status.value, collectHeaders(response.headers), body)
            }
            checkHttpStatus(response)
        }
        // 服务端正常结束流时（如 Responses API 以 response.completed /
        // response.incomplete / response.failed 事件结束，且没有 data: [DONE]），
        // 显式关闭回调流，否则 awaitClose 会永久挂起、外层流永远不会结束。
        close()
        awaitClose()
    }
}

internal suspend inline fun <reified T> Network.call(
    action: String,
    method: HttpMethod = HttpMethod.Get,
): T {
    val methodStr = method.value.uppercase()
    val fullUrl = this.host + action
    val response = execute(action, method = methodStr) {
        this.method = method
    }

    checkHttpStatus(response)

    val body = response.bodyAsText()
    HttpHookRegistry.forEach { hook ->
        hook.onResponse(methodStr, fullUrl, response.status.value, collectHeaders(response.headers), body)
    }
    if (body.isEmpty() || body == "null") {
        throw IllegalStateException("Response returns nothing")
    }

    return json.decodeFromString(body)
}

/**
 * 库内部使用的 JSON 配置（公开别名为 [io.github.hatoyuze.deepseek.protocol.api.DeepseekJson]，
 * 便于调用方与库保持完全一致的编解码形状）。
 */
internal val json: Json = DeepseekJson
