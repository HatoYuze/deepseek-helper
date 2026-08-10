package com.github.hatoyuze.tool.serializer

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * 参数序列化器，负责 JSON 字符串与类型 `T` 之间的双向转换。
 *
 * 框架不绑定具体序列化库，用户可提供自己的实现。
 */
public interface ArgumentSerializer<T : Any> {
    /** 从 JSON 字符串反序列化 */
    public suspend fun deserialize(rawJson: String): T

    /** 序列化为 JSON 字符串 */
    public suspend fun serialize(instance: T): String

    public companion object {
        /**
         * 基于 kotlinx.serialization 创建序列化器，适用于标记了 `@Serializable` 的类。
         */
        public inline fun <reified T : Any> kotlinx(
            json: Json = Json,
        ): ArgumentSerializer<T> {
            return KotlinxArgumentSerializer(
                serializer = serializer<T>(),
                deserializer = serializer<T>(),
                json = json,
            )
        }

        /** 显式指定 `KSerializer` 的工厂方法 */
        public fun <T : Any> from(
            serializer: KSerializer<T>,
            json: Json = Json,
        ): ArgumentSerializer<T> {
            return KotlinxArgumentSerializer(
                serializer = serializer,
                deserializer = serializer,
                json = json,
            )
        }
    }
}

/** 基于 kotlinx.serialization 的 [ArgumentSerializer] 实现 */
public open class KotlinxArgumentSerializer<T : Any>(
    private val serializer: KSerializer<T>,
    private val deserializer: DeserializationStrategy<T>,
    private val json: Json,
) : ArgumentSerializer<T> {
    override suspend fun deserialize(rawJson: String): T =
        json.decodeFromString(deserializer, rawJson)

    override suspend fun serialize(instance: T): String =
        json.encodeToString(serializer, instance)
}

/**
 * 参数反序列化失败的异常，包含原始 JSON 和错误信息。
 *
 * 框架会据此生成对模型友好的错误 [ToolResult]。
 */
public class ArgumentDeserializationException(
    val rawJson: String,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause)
