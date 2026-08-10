package io.github.hatoyuze.deepseek.toolcall.executor

/**
 * JSON Schema 参数约束抽象基类。
 *
 * 纯数据模型，序列化逻辑由 [PropertyDefSerializer] 统一处理。
 */
public sealed class PropertyDef {

    /** 字符串类型，支持 pattern / format 约束 */
    public data class StringDef(
        val pattern: String? = null,
        val format: Formats? = null,
    ) : PropertyDef()

    /** 数字基类，携带通用数值约束 */
    public sealed class NumberDef<T : Number>(
        val minimum: T? = null,
        val maximum: T? = null,
        val exclusiveMinimum: T? = null,
        val exclusiveMaximum: T? = null,
        val multipleOf: T? = null,
        val default: T? = null,
        val const: T? = null,
    ) : PropertyDef()

    /** 浮点数约束 */
    public class DoubleDef(
        minimum: Double? = null, maximum: Double? = null,
        exclusiveMinimum: Double? = null, exclusiveMaximum: Double? = null,
        multipleOf: Double? = null, default: Double? = null, const: Double? = null,
    ) : NumberDef<Double>(minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf, default, const)

    /** 整数约束 */
    public class IntegerDef(
        minimum: Int? = null, maximum: Int? = null,
        exclusiveMinimum: Int? = null, exclusiveMaximum: Int? = null,
        multipleOf: Int? = null, default: Int? = null, const: Int? = null,
    ) : NumberDef<Int>(minimum, maximum, exclusiveMinimum, exclusiveMaximum, multipleOf, default, const)

    /** 布尔类型 */
    public class BooleanDef : PropertyDef()

    /** 枚举类型 */
    public data class EnumDef(val values: List<String>) : PropertyDef()

    /** 数组类型 */
    public data class ArrayDef(val items: ParameterDef) : PropertyDef()

    /** 对象类型 */
    public data class ObjectDef(
        val properties: Map<String, ParameterDef>,
        val required: List<String> = emptyList(),
        val additionalProperties: Boolean = false,
    ) : PropertyDef()

    /** anyOf 组合类型 */
    public data class AnyOfDef(val options: List<PropertyDef>) : PropertyDef()

    /** `$ref` 引用类型 */
    public data class RefDef(val ref: String) : PropertyDef()
}
