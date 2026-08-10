package com.github.hatoyuze.tool.pipeline.plugins

import com.github.hatoyuze.tool.Logger
import com.github.hatoyuze.tool.pipeline.ToolCallPhase
import com.github.hatoyuze.tool.pipeline.ToolCallHost
import io.ktor.util.date.getTimeMillis

/**
 * 日志插件：在每个 phase 前后记录执行耗时。
 *
 * ```kotlin
 * val host = toolHost {
 *     tool("search") { ... }
 *     logging()
 * }
 * ```
 */
public object LoggingPlugin {
    private val logger = Logger("LoggingPlugin")


    public fun install(host: ToolCallHost) {
        ToolCallPhase.entries.forEach { phase ->
            host.intercept(phase) { ctx ->

                val start = getTimeMillis()
                logger.info { "[${ctx.call.id}] $phase → ${ctx.call.name}" }
                try {
                    ctx.proceed()
                } finally {
                    val elapsed = getTimeMillis() - start
                    logger.info { "[${ctx.call.id}] $phase ← ${ctx.call.name} (${elapsed}ms)" }
                }
            }
        }
    }
}
