package io.github.hatoyuze.deepseek.toolcall.executor

import io.github.hatoyuze.deepseek.toolcall.executor.PropertyDef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** 由 [schema] 驱动的 [ParameterBag] 自定义序列化器 */
public class ParameterBagSerializer(
    private val schema: PropertyDef.ObjectDef,
) : KSerializer<ParameterBag> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ParameterBag") {
        for ((name, _) in schema.properties) {
            element(name, PrimitiveSerialDescriptor(name, PrimitiveKind.STRING))
        }
    }

    override fun serialize(encoder: Encoder, value: ParameterBag) {
        val composite = encoder.beginStructure(descriptor)
        for ((name, param) in schema.properties) {
            val idx = descriptor.getElementIndex(name)
            val v = value[name] ?: continue
            encodeValue(composite, descriptor, idx, param.schema, v)
        }
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ParameterBag {
        val bag = ParameterBag.empty()
        val composite = decoder.beginStructure(descriptor)
        while (true) {
            val idx = composite.decodeElementIndex(descriptor)
            if (idx == CompositeDecoder.DECODE_DONE) break
            val name = descriptor.getElementName(idx)
            val param = schema.properties[name]
            if (param != null) {
                bag[name] = decodeValue(composite, descriptor, idx, param.schema)
            }
        }
        composite.endStructure(descriptor)
        return bag
    }

    private fun encodeValue(
        composite: kotlinx.serialization.encoding.CompositeEncoder,
        desc: SerialDescriptor,
        idx: Int,
        def: PropertyDef,
        value: Any?,
    ) {
        if (value == null) return
        when (def) {
            is PropertyDef.StringDef, is PropertyDef.EnumDef ->
                composite.encodeStringElement(desc, idx, value as String)
            is PropertyDef.IntegerDef ->
                composite.encodeIntElement(desc, idx, (value as Number).toInt())
            is PropertyDef.DoubleDef ->
                composite.encodeDoubleElement(desc, idx, (value as Number).toDouble())
            is PropertyDef.BooleanDef ->
                composite.encodeBooleanElement(desc, idx, value as Boolean)
            is PropertyDef.ArrayDef -> {
                @Suppress("UNCHECKED_CAST")
                val items = value as List<Any?>
                val itemsDef = def.items.schema
                val itemsSerializer = elementSerializer(itemsDef)
                @Suppress("UNCHECKED_CAST")
                val listSerializer = ListSerializer(itemsSerializer) as KSerializer<List<Any?>>
                composite.encodeSerializableElement(desc, idx, listSerializer, items)
            }
            is PropertyDef.ObjectDef -> {
                val nestedBag = value as ParameterBag
                val nestedSerializer = ParameterBagSerializer(def)
                composite.encodeSerializableElement(desc, idx, nestedSerializer, nestedBag)
            }
            else -> composite.encodeStringElement(desc, idx, value.toString())
        }
    }

    private fun decodeValue(
        composite: CompositeDecoder,
        desc: SerialDescriptor,
        idx: Int,
        def: PropertyDef,
    ): Any? {
        return when (def) {
            is PropertyDef.StringDef, is PropertyDef.EnumDef ->
                composite.decodeStringElement(desc, idx)
            is PropertyDef.IntegerDef ->
                composite.decodeIntElement(desc, idx)
            is PropertyDef.DoubleDef ->
                composite.decodeDoubleElement(desc, idx)
            is PropertyDef.BooleanDef ->
                composite.decodeBooleanElement(desc, idx)
            is PropertyDef.ArrayDef -> {
                val itemsDef = def.items.schema
                val itemsSerializer = elementSerializer(itemsDef)
                composite.decodeSerializableElement(desc, idx, ListSerializer(itemsSerializer))
            }
            is PropertyDef.ObjectDef -> {
                val nestedSerializer = ParameterBagSerializer(def)
                composite.decodeSerializableElement(desc, idx, nestedSerializer)
            }
            else -> composite.decodeStringElement(desc, idx)
        }
    }


    private fun elementSerializer(def: PropertyDef): KSerializer<*> = when (def) {
        is PropertyDef.StringDef, is PropertyDef.EnumDef ->
            kotlinx.serialization.serializer<String>()
        is PropertyDef.IntegerDef ->
            kotlinx.serialization.serializer<Int>()
        is PropertyDef.DoubleDef ->
            kotlinx.serialization.serializer<Double>()
        is PropertyDef.BooleanDef ->
            kotlinx.serialization.serializer<Boolean>()
        is PropertyDef.ObjectDef ->
            ParameterBagSerializer(def)
        is PropertyDef.ArrayDef ->
            ListSerializer(elementSerializer(def.items.schema))
        else -> kotlinx.serialization.serializer<String>()
    }
}
