package com.github.hatoyuze.tool.serializer

import com.github.hatoyuze.tool.executor.Formats
import com.github.hatoyuze.tool.executor.PropertyDef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 将 [PropertyDef] 子树递归序列化为 DeepSeek 兼容的 JSON Schema。
 *
 * 支持所有 [PropertyDef] 子类型：StringDef、IntegerDef、DoubleDef、BooleanDef、
 * EnumDef、ArrayDef、ObjectDef、AnyOfDef、RefDef。
 */
public object PropertyDefSerializer : KSerializer<PropertyDef> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("PropertyDef")

    override fun serialize(encoder: Encoder, value: PropertyDef) {
        val element = serializeToElement(value)
        encoder.encodeSerializableValue(JsonElement.serializer(), element)
    }

    override fun deserialize(decoder: Decoder): PropertyDef =
        error("PropertyDef deserialization is not supported")

    /** 将 [PropertyDef] 序列化为 [JsonElement]，供外部组装 */
    public fun serializeToElement(value: PropertyDef): JsonElement = when (value) {
        is PropertyDef.StringDef -> buildJsonObject {
            put("type", "string")
            if (value.pattern != null) put("pattern", value.pattern)
            if (value.format != null) put("format", formatToString(value.format))
        }

        is PropertyDef.IntegerDef -> buildJsonObject {
            put("type", "integer")
            putNumberOpt(this, "minimum", value.minimum)
            putNumberOpt(this, "maximum", value.maximum)
            putNumberOpt(this, "exclusiveMinimum", value.exclusiveMinimum)
            putNumberOpt(this, "exclusiveMaximum", value.exclusiveMaximum)
            putNumberOpt(this, "multipleOf", value.multipleOf)
            putNumberOpt(this, "default", value.default)
            putNumberOpt(this, "const", value.const)
        }

        is PropertyDef.DoubleDef -> buildJsonObject {
            put("type", "number")
            putNumberOpt(this, "minimum", value.minimum)
            putNumberOpt(this, "maximum", value.maximum)
            putNumberOpt(this, "exclusiveMinimum", value.exclusiveMinimum)
            putNumberOpt(this, "exclusiveMaximum", value.exclusiveMaximum)
            putNumberOpt(this, "multipleOf", value.multipleOf)
            putNumberOpt(this, "default", value.default)
            putNumberOpt(this, "const", value.const)
        }

        is PropertyDef.BooleanDef -> buildJsonObject {
            put("type", "boolean")
        }

        is PropertyDef.EnumDef -> buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                value.values.forEach { add(JsonPrimitive(it)) }
            })
        }

        is PropertyDef.ArrayDef -> buildJsonObject {
            put("type", "array")
            val itemsJson = serializeToElement(value.items.schema)
            val itemsWithDesc = if (value.items.description != null) {
                JsonObject((itemsJson as JsonObject).toMutableMap().apply {
                            put("description", JsonPrimitive(value.items.description))
                })
            } else itemsJson
            put("items", itemsWithDesc)
        }

        is PropertyDef.ObjectDef -> buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                value.properties.forEach { (name, param) ->
                    val schemaJson = serializeToElement(param.schema) as JsonObject
                    val merged = if (param.description != null) {
                        JsonObject(schemaJson.toMutableMap().apply {
                            put("description", JsonPrimitive(param.description))
                        })
                    } else schemaJson
                    put(name, merged)
                }
            })
            if (value.required.isNotEmpty()) {
                put("required", buildJsonArray {
                    value.required.forEach { add(JsonPrimitive(it)) }
                })
            }
            put("additionalProperties", JsonPrimitive(value.additionalProperties))
        }

        is PropertyDef.AnyOfDef -> buildJsonObject {
            put("anyOf", buildJsonArray {
                value.options.forEach { add(serializeToElement(it)) }
            })
        }

        is PropertyDef.RefDef -> buildJsonObject {
            put("\$ref", value.ref)
        }
    }

    private fun formatToString(format: Formats): String = when (format) {
        Formats.email -> "email"
        Formats.hostname -> "hostname"
        Formats.ipv4 -> "ipv4"
        Formats.ipv6 -> "ipv6"
        Formats.uuid -> "uuid"
    }

    private fun putNumberOpt(
        obj: kotlinx.serialization.json.JsonObjectBuilder,
        key: String,
        value: Number?,
    ) {
        when (value) {
            null -> return
            is Int -> obj.put(key, JsonPrimitive(value))
            is Long -> obj.put(key, JsonPrimitive(value))
            is Double -> obj.put(key, JsonPrimitive(value))
            is Float -> obj.put(key, JsonPrimitive(value))
            else -> obj.put(key, JsonPrimitive(value.toDouble()))
        }
    }
}
