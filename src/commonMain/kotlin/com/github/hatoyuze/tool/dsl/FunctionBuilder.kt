package com.github.hatoyuze.tool.dsl

import com.github.hatoyuze.tool.executor.Formats
import com.github.hatoyuze.tool.executor.ParameterDef
import com.github.hatoyuze.tool.executor.PropertyDef
import com.github.hatoyuze.tool.registry.ToolDefinition

/**
 * 仅构建 JSON Schema 参数定义，不生成完整 [ToolDefinition]。
 *
 * 适用于仅需要 JSON Schema、不依赖完整工具注册的场景：
 *
 * ```kotlin
 * val schema = parametersOf {
 *     string("name") { required = true }
 *     integer("age") { }
 * }
 * ```
 */
public fun parametersOf(block: ParametersBuilder.() -> Unit): PropertyDef.ObjectDef =
    ParametersBuilder().apply(block).build()

/**
 * 声明式构建一个完整的 [ToolDefinition]。
 *
 * ```kotlin
 * val def = createFunction("search") {
 *     description = "搜索互联网"
 *     strict = true
 *     parameters {
 *         string("q") { description = "搜索关键词"; required = true }
 *     }
 * }
 * ```
 */
public fun createFunction(name: String, block: FunctionBuilder.() -> Unit): ToolDefinition =
    FunctionBuilder(name).apply(block).build()

/**
 * 函数定义构建器。
 *
 * 定义 `parameters` 和 `$def` 引用块，最终生成 [ToolDefinition]。
 */
public class FunctionBuilder internal constructor(private val name: String) {
    /** 函数描述，会传递给模型 */
    public var description: String = ""

    /** 是否启用严格 schema 校验 */
    public var strict: Boolean = false

    private var paramsBuilder: ParametersBuilder? = null
    private var defsBuilder: DefinitionsBuilder? = null

    /** 定义函数参数 */
    public fun parameters(block: ParametersBuilder.() -> Unit) {
        paramsBuilder = ParametersBuilder().apply(block)
    }

    /** 定义可复用的 `$def` 引用 */
    public fun definitions(block: DefinitionsBuilder.() -> Unit) {
        defsBuilder = DefinitionsBuilder().apply(block)
    }

    /** 构建最终的 [ToolDefinition] */
    public fun build(): ToolDefinition {
        val rootParams = paramsBuilder?.build() ?: PropertyDef.ObjectDef(emptyMap())
        val defs = defsBuilder?.build()
        return ToolDefinition.from(name, description, strict, rootParams, defs)
    }
}

/**
 * 参数 Schema 构建器。
 *
 * 提供常见 JSON Schema 类型的快捷方法：
 *
 * ```kotlin
 * parameters {
 *     string("name") { description = "姓名"; required = true }
 *     integer("age") { minimum = 0; maximum = 150 }
 *     number("score") { minimum = 0.0; maximum = 100.0 }
 *     boolean("active", description = "是否激活")
 *     enum("status") { values = listOf("pending", "done") }
 *     array("tags") { items { string("tag") { } } }
 *     obj("address") {
 *         properties {
 *             string("city") { required = true }
 *         }
 *     }
 * }
 * ```
 */
public class ParametersBuilder {
    internal val properties = mutableMapOf<String, ParameterDef>()
    internal val requiredNames = mutableListOf<String>()

    internal fun required(name: String) { requiredNames.add(name) }

    /** 定义 string 类型参数 */
    public fun string(name: String, block: StringParamBuilder.() -> Unit = {}) {
        val b = StringParamBuilder().apply(block)
        properties[name] = ParameterDef(
            description = b.description,
            schema = PropertyDef.StringDef(pattern = b.pattern, format = b.format),
        )
        if (b.required) required(name)
    }

    /** 定义 integer 类型参数 */
    public fun integer(name: String, block: IntegerParamBuilder.() -> Unit = {}) {
        val b = IntegerParamBuilder().apply(block)
        properties[name] = ParameterDef(
            description = b.description,
            schema = PropertyDef.IntegerDef(
                b.minimum, b.maximum, b.exclusiveMinimum, b.exclusiveMaximum,
                b.multipleOf, b.default, b.const,
            ),
        )
        if (b.required) required(name)
    }

    /** 定义 number 类型参数 */
    public fun number(name: String, block: DoubleParamBuilder.() -> Unit = {}) {
        val b = DoubleParamBuilder().apply(block)
        properties[name] = ParameterDef(
            description = b.description,
            schema = PropertyDef.DoubleDef(
                b.minimum, b.maximum, b.exclusiveMinimum, b.exclusiveMaximum,
                b.multipleOf, b.default, b.const,
            ),
        )
        if (b.required) required(name)
    }

    /** 定义 boolean 类型参数 */
    public fun boolean(name: String, description: String = "", required: Boolean = false) {
        properties[name] = ParameterDef(description = description, schema = PropertyDef.BooleanDef())
        if (required) required(name)
    }

    /** 定义 enum 类型参数 */
    public fun enum(name: String, block: EnumParamBuilder.() -> Unit) {
        val b = EnumParamBuilder().apply(block)
        properties[name] = ParameterDef(
            description = b.description,
            schema = PropertyDef.EnumDef(b.values),
        )
        if (b.required) required(name)
    }

    /** 定义 array 类型参数 */
    public fun array(name: String, block: ArrayParamBuilder.() -> Unit) {
        val b = ArrayParamBuilder().apply(block)
        properties[name] = ParameterDef(
            description = b.description,
            schema = PropertyDef.ArrayDef(b.items),
        )
        if (b.required) required(name)
    }

    /** 定义嵌套 object 类型参数 */
    public fun obj(name: String, block: ObjectParamBuilder.() -> Unit) {
        val b = ObjectParamBuilder().apply(block)
        val nested = ParametersBuilder().apply(b.paramsBlock)
        properties[name] = ParameterDef(
            description = b.description,
            schema = PropertyDef.ObjectDef(
                properties = nested.properties,
                required = nested.requiredNames.toList(),
            ),
        )
        if (b.required) required(name)
    }

    /** 定义 anyOf 类型参数 */
    public fun anyOf(name: String, block: AnyOfParamBuilder.() -> Unit) {
        val b = AnyOfParamBuilder().apply(block)
        properties[name] = ParameterDef(
            description = b.description,
            schema = PropertyDef.AnyOfDef(b.options),
        )
        if (b.required) required(name)
    }

    /** 引用 `$def` 中的定义 */
    public fun ref(name: String, ref: String, description: String = "", required: Boolean = false) {
        properties[name] = ParameterDef(description = description, schema = PropertyDef.RefDef(ref))
        if (required) required(name)
    }

    internal fun build(): PropertyDef.ObjectDef = PropertyDef.ObjectDef(
        properties = properties.toMap(),
        required = requiredNames.toList(),
    )
}

/** 参数构建器基类 */
public open class ParamBuilderBase {
    /** 参数描述 */
    public var description: String = ""

    /** 是否必填 */
    public var required: Boolean = false
}

/** string 参数构建器 */
public class StringParamBuilder : ParamBuilderBase() {
    /** 正则匹配模式 */
    public var pattern: String? = null

    /** 预定义格式 ([Formats.email] 等) */
    public var format: Formats? = null
}

/** integer 参数构建器 */
public class IntegerParamBuilder : ParamBuilderBase() {
    public var minimum: Int? = null
    public var maximum: Int? = null
    public var exclusiveMinimum: Int? = null
    public var exclusiveMaximum: Int? = null
    public var multipleOf: Int? = null
    public var default: Int? = null
    public var const: Int? = null
}

/** number (double) 参数构建器 */
public class DoubleParamBuilder : ParamBuilderBase() {
    public var minimum: Double? = null
    public var maximum: Double? = null
    public var exclusiveMinimum: Double? = null
    public var exclusiveMaximum: Double? = null
    public var multipleOf: Double? = null
    public var default: Double? = null
    public var const: Double? = null
}

/** enum 参数构建器 */
public class EnumParamBuilder : ParamBuilderBase() {
    /** 枚举值的字符串列表 */
    public var values: List<String> = emptyList()
}

/** array 参数构建器 */
public class ArrayParamBuilder : ParamBuilderBase() {
    internal var items: ParameterDef = ParameterDef(schema = PropertyDef.StringDef())

    /**
     * 定义数组元素的类型约束。
     *
     * ```kotlin
     * array("tags") {
     *     items { string("tag") { description = "标签" } }
     * }
     * ```
     */
    public fun items(block: ParametersBuilder.() -> Unit) {
        val nested = ParametersBuilder().apply(block)
        items = nested.properties.values.firstOrNull()
            ?: ParameterDef(schema = PropertyDef.StringDef())
    }
}

/** 嵌套 object 参数构建器 */
public class ObjectParamBuilder : ParamBuilderBase() {
    internal var paramsBlock: ParametersBuilder.() -> Unit = {}

    /** 定义嵌套对象的属性 */
    public fun properties(block: ParametersBuilder.() -> Unit) { paramsBlock = block }
}

/** anyOf 参数构建器 */
public class AnyOfParamBuilder : ParamBuilderBase() {
    internal val options = mutableListOf<PropertyDef>()

    /** 添加一个 anyOf 选项 */
    public fun option(block: ParametersBuilder.() -> Unit) {
        val nested = ParametersBuilder().apply(block)
        nested.properties.values.forEach { options.add(it.schema) }
    }
}

/**
 * `$def` 定义构建器。
 *
 * 允许在 parameters 中通过 `ref()` 引用此处定义的 schema 块。
 */
public class DefinitionsBuilder {
    private val defs = mutableMapOf<String, PropertyDef>()

    /** 定义一个可复用的 schema 块 */
    public fun define(name: String, block: ParametersBuilder.() -> Unit) {
        val nested = ParametersBuilder().apply(block)
        defs[name] = PropertyDef.ObjectDef(
            properties = nested.properties,
            required = nested.requiredNames.toList(),
        )
    }

    internal fun build(): Map<String, PropertyDef> = defs.toMap()
}
