package com.github.hatoyuze.tool.pipeline.plugins

import com.github.hatoyuze.tool.bridge.SchemaDrivenExecutor
import com.github.hatoyuze.tool.executor.TypedToolExecutor
import com.github.hatoyuze.tool.pipeline.PipelineException
import com.github.hatoyuze.tool.pipeline.ToolCallPhase
import com.github.hatoyuze.tool.pipeline.ToolCallHost
import kotlinx.coroutines.CancellationException

/**
 * 序列化插件：在 [ToolCallPhase.TRANSFORM] 阶段自动反序列化 tool call arguments。
 *
 * 对 [TypedToolExecutor] 使用其配置的 [serializer][TypedToolExecutor.serializer]；
 * 对 [SchemaDrivenExecutor] 无需额外操作（自行处理反序列化）。
 */
public object SerializationPlugin {
    /** 安装到指定的 [ToolCallHost] */
    public fun install(host: ToolCallHost) {
        host.intercept(ToolCallPhase.TRANSFORM) { ctx ->
            val executor = ctx.executor
            try {
                when (executor) {
                    is SchemaDrivenExecutor -> {
                        // SchemaDrivenExecutor 内部自行处理反序列化
                    }
                    is TypedToolExecutor<*> -> {
                        val serializer = executor.serializer
                        if (serializer != null) {
                            ctx.typedParams = serializer.deserialize(ctx.call.arguments)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw PipelineException(
                    "Failed to deserialize arguments for '${ctx.call.name}': ${e.message}", e,
                )
            }
            ctx.proceed()
        }
    }
}
