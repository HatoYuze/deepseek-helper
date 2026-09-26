package io.github.hatoyuze.deepseek.protocol.api

import kotlinx.serialization.json.Json

/**
 * 库内部使用的 JSON 配置，也是「与库一致地编解码请求/响应」的公开入口。
 *
 * ```kotlin
 * // 自己构造请求体或落盘历史时，用同一份配置才能得到与库完全一致的形状
 * val body = DeepseekJson.encodeToString(Message.serializer(), message)
 * ```
 *
 * 配置含义：
 * - [Json.ignoreUnknownKeys]：服务端新增字段不会让反序列化失败（前向兼容）
 * - [Json.explicitNulls] = `false`：值为 `null` 的字段整条不写出。
 *   `Message` 的 `toolCallId` / `toolCalls` / `reasoningContent` / `name` 都是按角色可选的
 *   字段，`null` 对服务端没有信息量；这与 0.3.x 实际发出的请求形状一致。解码不受影响 ——
 *   所有可选字段都有默认值，字段缺失即取默认值
 */
public val DeepseekJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
