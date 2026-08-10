package com.github.hatoyuze.tool.registry

import com.github.hatoyuze.tool.executor.ToolExecutor

/**
 * 工具注册中心，管理 `名称 → (定义, 执行器)` 的映射。
 *
 * 是 [ToolCallHost] 的底层存储。
 */
public class ToolRegistry {
    private val entries = mutableMapOf<String, Pair<ToolDefinition, ToolExecutor>>()

    /** 注册单个工具 */
    public fun register(definition: ToolDefinition, executor: ToolExecutor) {
        entries[definition.name] = definition to executor
    }

    /** 按名称查找执行器 */
    public fun getExecutor(name: String): ToolExecutor? = entries[name]?.second

    /** 获取所有已注册工具的定义 */
    public fun getDefinitions(): List<ToolDefinition> = entries.values.map { it.first }

    /** 按名称查找定义 */
    public fun getDefinition(name: String): ToolDefinition? = entries[name]?.first

    /** 是否为空 */
    public fun isEmpty(): Boolean = entries.isEmpty()
}
