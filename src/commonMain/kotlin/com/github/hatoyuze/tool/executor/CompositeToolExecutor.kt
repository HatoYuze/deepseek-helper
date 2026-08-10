package com.github.hatoyuze.tool.executor

/**
 * 组合执行器，按 [ToolCall.name] 路由到注册的执行器。
 *
 * 当不需要 [ToolRegistry] 的完整注册机制时，可作为轻量替代。
 */
public class CompositeToolExecutor(
    private val executors: Map<String, ToolExecutor>,
) : ToolExecutor {

    override suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult {
        val executor = executors[call.name]
            ?: return ToolResult.error(call.id, "Unknown function: ${call.name}")
        return executor.execute(call, ctx)
    }
}
