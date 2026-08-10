package io.github.hatoyuze.deepseek.toolcall

/** 平台无关的日志记录器。JVM 实现委托给 SLF4J。 */
public expect class Logger(name: String) {
    public fun info(msg: () -> String)
}
