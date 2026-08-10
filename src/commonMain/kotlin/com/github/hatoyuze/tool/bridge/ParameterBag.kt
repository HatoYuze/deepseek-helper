package com.github.hatoyuze.tool.bridge

/**
 * Schema-driven 动态参数容器。
 *
 * 由 [ParameterBagSerializer] 根据 JSON Schema 自动填充，
 * 无需为每个 tool 定义 data class。
 *
 * ```kotlin
 * execute { bag, _ ->
 *     val city = bag.getString("city")       // 获取字符串
 *     val limit = bag.getInt("limit")         // 获取整数
 *     val tag = bag.getStringOrNull("tag")    // 可选参数
 * }
 * ```
 *
 * 类型不匹配时抛出有意义的错误信息。
 */
public class ParameterBag internal constructor(
    private val values: MutableMap<String, Any?>,
) {
    /** 获取字符串，类型不匹配时抛出异常 */
    public fun getString(name: String): String {
        val v = values[name] ?: throw NoSuchElementException("Parameter '$name' not found")
        return v as? String ?: throw ClassCastException("Parameter '$name' expected String, got ${v::class.simpleName}")
    }

    /** 获取字符串，参数不存在或类型不匹配时返回 `null` */
    public fun getStringOrNull(name: String): String? = (values[name] as? String)

    /** 获取整数，类型不匹配时抛出异常 */
    public fun getInt(name: String): Int {
        val v = values[name] ?: throw NoSuchElementException("Parameter '$name' not found")
        return (v as? Number)?.toInt() ?: throw ClassCastException("Parameter '$name' expected Number, got ${v::class.simpleName}")
    }

    /** 获取浮点数，类型不匹配时抛出异常 */
    public fun getDouble(name: String): Double {
        val v = values[name] ?: throw NoSuchElementException("Parameter '$name' not found")
        return (v as? Number)?.toDouble() ?: throw ClassCastException("Parameter '$name' expected Number, got ${v::class.simpleName}")
    }

    /** 获取布尔值，类型不匹配时抛出异常 */
    public fun getBoolean(name: String): Boolean {
        val v = values[name] ?: throw NoSuchElementException("Parameter '$name' not found")
        return v as? Boolean ?: throw ClassCastException("Parameter '$name' expected Boolean, got ${v::class.simpleName}")
    }

    /** 获取列表，类型不匹配时抛出异常 */
    public fun getList(name: String): List<Any?> {
        val v = values[name] ?: throw NoSuchElementException("Parameter '$name' not found")
        return v as? List<*> ?: throw ClassCastException("Parameter '$name' expected List, got ${v::class.simpleName}")
    }

    /** 获取嵌套对象 */
    public fun getBag(name: String): ParameterBag {
        val v = values[name] ?: throw NoSuchElementException("Parameter '$name' not found")
        return v as? ParameterBag ?: throw ClassCastException("Parameter '$name' expected ParameterBag, got ${v::class.simpleName}")
    }

    public operator fun set(name: String, value: Any?) {
        values[name] = value
    }

    public operator fun get(name: String): Any? = values[name]

    public fun contains(name: String): Boolean = values.containsKey(name)

    public fun keys(): Set<String> = values.keys.toSet()

    public companion object {
        /** 创建空容器 */
        public fun empty(): ParameterBag = ParameterBag(mutableMapOf())
    }
}
