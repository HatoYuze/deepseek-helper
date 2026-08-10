package com.github.hatoyuze.tool.bridge

import com.github.hatoyuze.tool.executor.ToolCall
import com.github.hatoyuze.tool.executor.ToolExecutionContext
import com.github.hatoyuze.tool.executor.ToolExecutor
import com.github.hatoyuze.tool.executor.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal class SchemaDrivenExecutor(
    private val schema: com.github.hatoyuze.tool.executor.PropertyDef.ObjectDef,
    serializer: ParameterBagSerializer,
    private val handler: suspend (ParameterBag, ToolExecutionContext) -> String,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val bagSerializer = serializer

    override suspend fun execute(call: ToolCall, ctx: ToolExecutionContext): ToolResult {
        val bag = try {
            json.decodeFromString(bagSerializer, call.arguments)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return ToolResult.error(call.id, "ToolError[${e::class.simpleName}]: Parameter deserialization failed — ${e.message}")
        }
        return try {
            val result = handler(bag, ctx)
            ToolResult.success(call.id, result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            ToolResult.error(call.id, "ToolError[${e::class.simpleName}]: ${e.message ?: "Execution failed"}")
        }
    }
}
