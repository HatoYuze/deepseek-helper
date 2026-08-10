package io.github.hatoyuze.deepseek.toolcall

import io.github.hatoyuze.deepseek.toolcall.executor.ToolCall
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutionContext
import io.github.hatoyuze.deepseek.toolcall.executor.ToolExecutor
import io.github.hatoyuze.deepseek.toolcall.executor.ToolResult
import io.github.hatoyuze.deepseek.toolcall.registry.ToolDefinition

/**
 * 工具处理器抽象类，同时绑定 [ToolDefinition] 和提供 [ToolExecutor] 能力。
 *
 * 实现此抽象类可一次完成工具的定义与执行注册：
 *
 * ```kotlin
 * class WeatherTool : ToolHandler() {
 *     override val definition = createFunction("get_weather") {
 *         description = "获取天气"
 *         parameters { string("city") { required = true } }
 *     }
 *
 *     override suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult {
 *         // 解析参数、执行业务逻辑
 *         return ToolResult.success(call.id, """{"weather": "晴"}""")
 *     }
 * }
 * ```
 */
public abstract class ToolHandler : ToolExecutor {
    /** 工具的完整定义，用于序列化为 API 请求中的 `tools` 数组 */
    public abstract val definition: ToolDefinition

    /** 执行具体业务逻辑 */
    public abstract override suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult
}
