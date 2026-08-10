package io.github.hatoyuze.deepseek.toolcall.pipeline

import io.github.hatoyuze.deepseek.toolcall.executor.ToolResult
import kotlinx.coroutines.CancellationException

/**
 * 工具调用管道，按 [ToolCallPhase] 有序执行拦截器链。
 *
 * 核心执行拦截器在 init 中自动安装为洋葱最内层。
 */
public class ToolCallPipeline {
    private val phases = mutableMapOf<ToolCallPhase, MutableList<suspend (PipelineContext) -> Unit>>().apply {
        ToolCallPhase.entries.forEach { put(it, mutableListOf()) }
    }

    init {
        intercept(ToolCallPhase.EXECUTE) { ctx ->
            ctx.result = ctx.executor.execute(ctx.call, ctx.executionContext)
            val r = ctx.result ?: return@intercept
            if (r.isError) throw PipelineException(r.content)
        }
    }

    /**
     * 向指定 phase 注册拦截器。
     *
     * 插入到头部，使后注册的拦截器先执行（洋葱模型）。
     */
    public fun intercept(phase: ToolCallPhase, handler: suspend (PipelineContext) -> Unit) {
        phases[phase]?.add(0, handler)
    }

    /** 按 phase 顺序执行所有拦截器，返回最终 [ToolResult] */
    public suspend fun execute(context: PipelineContext): ToolResult {
        try {
            while (context.phaseIndex < context.orderedPhases.size) {
                val phase = context.orderedPhases[context.phaseIndex]
                context.currentPhase = phase
                val interceptors = phases[phase]!!
                if (interceptors.isEmpty()) {
                    context.phaseIndex++
                    continue
                }
                context.interceptorIndex = 0
                context.phaseInterceptors = interceptors
                context.interceptorIndex = 1
                interceptors[0](context)
                context.phaseIndex++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PipelineException) {
            runErrorPhase(context, e)
            context.result = ToolResult.error(context.call.id, e.message)
        } catch (e: Exception) {
            runErrorPhase(context, e)
            context.result = ToolResult.error(
                context.call.id,
                "[${context.currentPhase}] ${e.message ?: "Unexpected error"}",
            )
        }
        return context.result ?: ToolResult.error(context.call.id, "No result produced")
    }

    /**
     * 执行 [ToolCallPhase.ERROR] 阶段的拦截器。
     *
     * 拦截器可通过 [PipelineContext.proceed] 依次推进；其抛出的异常会覆盖为最终错误信息。
     */
    private suspend fun runErrorPhase(context: PipelineContext, cause: Throwable) {
        context.error = cause
        context.currentPhase = ToolCallPhase.ERROR
        val interceptors = phases[ToolCallPhase.ERROR].orEmpty()
        if (interceptors.isEmpty()) return
        context.phaseInterceptors = interceptors
        context.interceptorIndex = 1
        try {
            interceptors[0](context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            context.result = ToolResult.error(context.call.id, "[ERROR] ${e.message ?: "Error handler failed"}")
        }
    }
}
