package io.github.hatoyuze.deepseek.toolcall.registry

import io.github.hatoyuze.deepseek.toolcall.serializer.PropertyDefSerializer
import io.github.hatoyuze.deepseek.toolcall.executor.PropertyDef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具的完整定义，映射 DeepSeek API 中 `tools[].function` 对象。
 *
 * @param name 工具名称
 * @param description 工具描述，会传递给模型
 * @param strict 是否启用严格 schema 校验
 * @param parameters JSON Schema 参数定义
 * @param defs 可复用的 `$def` 引用定义
 */
@Serializable
public data class ToolDefinition(
    val name: String,
    val description: String,
    @SerialName("strict") val strict: Boolean = false,
    val parameters: JsonElement,
    @SerialName("\$def") val defs: Map<String, JsonElement>? = null,
) {
    /** 转换为 API 格式的 function 对象 */
    public fun toFunctionElement(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("strict", JsonPrimitive(strict))
            put("parameters", parameters)
            if (defs != null) put("\$def", buildJsonObject {
                defs.forEach { (k, v) -> put(k, v) }
            })
        })
    }

    public companion object {
        /** 从 [PropertyDef.ObjectDef] 树创建 [ToolDefinition]，自动序列化 parameters */
        public fun from(
            name: String,
            description: String,
            strict: Boolean = false,
            parameters: PropertyDef.ObjectDef,
            defs: Map<String, PropertyDef>? = null,
        ): ToolDefinition {
            val paramsJson = PropertyDefSerializer.serializeToElement(parameters)
            val defsJson = defs?.mapValues { (_, v) -> PropertyDefSerializer.serializeToElement(v) }
            return ToolDefinition(name, description, strict, paramsJson, defsJson)
        }
    }
}
