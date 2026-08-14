package io.github.hatoyuze.deepseek.toolcall.registry

import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutor
import kotlin.concurrent.Volatile

/**
 * 工具注册中心，管理 `名称 → (定义, 执行器)` 的映射。
 *
 * 是 [ToolCallHost] 的底层存储。
 *
 * 线程模型：`register` 应在并发执行开始前的 setup 阶段完成；
 * 此后 `getExecutor`/`getDefinition`/`getDefinitions`/`isEmpty` 可被并发安全地读取
 * （`definitionsCache` 以 `@Volatile` 缓存，注册会使缓存失效并重建）。
 */
public class ToolRegistry {
    private val entries = mutableMapOf<String, Pair<ToolDefinition, ToolExecutor>>()
    @Volatile
    private var definitionsCache: List<ToolDefinition>? = null

    /** 注册单个工具 */
    public fun register(definition: ToolDefinition, executor: ToolExecutor) {
        entries[definition.name] = definition to executor
        definitionsCache = null
    }

    /** 按名称查找执行器 */
    public fun getExecutor(name: String): ToolExecutor? = entries[name]?.second

    /** 获取所有已注册工具的定义 */
    public fun getDefinitions(): List<ToolDefinition> =
        definitionsCache ?: entries.values.map { it.first }.also { definitionsCache = it }

    /** 按名称查找定义 */
    public fun getDefinition(name: String): ToolDefinition? = entries[name]?.first

    /** 是否为空 */
    public fun isEmpty(): Boolean = entries.isEmpty()
}
