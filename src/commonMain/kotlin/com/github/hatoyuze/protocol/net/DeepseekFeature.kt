package com.github.hatoyuze.protocol.net

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.util.*

/**
 * DeepSeek API 鉴权插件配置。
 *
 * ```kotlin
 * install(DeepseekFeature) {
 *     apiKey = "sk-..."
 * }
 * ```
 */
public class DeepseekFeatureConfig {
    /** DeepSeek API 密钥 */
    public var apiKey: String = ""
}

/**
 * Ktor 插件，自动为每个请求添加 `Authorization: Bearer <apiKey>` 头。
 *
 * @see DeepseekFeatureConfig
 */
public class DeepseekFeature(val config: DeepseekFeatureConfig) {
    public companion object : HttpClientPlugin<DeepseekFeatureConfig, DeepseekFeature> {
        override val key: AttributeKey<DeepseekFeature> = AttributeKey("DeepseekFeature")

        override fun prepare(block: DeepseekFeatureConfig.() -> Unit): DeepseekFeature {
            val config = DeepseekFeatureConfig().apply(block)
            require(config.apiKey.isNotBlank()) {
                "DeepseekFeature: apiKey must not be blank. Please provide a valid API key via install(DeepseekFeature) { apiKey = \"sk-...\" }"
            }
            return DeepseekFeature(config)
        }

        override fun install(plugin: DeepseekFeature, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Before) {
                context.headers.append("Authorization", "Bearer ${plugin.config.apiKey}")
            }
        }
    }
}
