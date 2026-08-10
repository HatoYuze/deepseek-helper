package io.github.hatoyuze.deepseek.toolcall.executor

/**
 * 单个参数的完整定义：可选描述 + 类型约束。
 *
 * 在序列化时 [description] 会平铺合并到 JSON Schema 对象中。
 */
public data class ParameterDef(
    val description: String? = null,
    val schema: PropertyDef,
)
