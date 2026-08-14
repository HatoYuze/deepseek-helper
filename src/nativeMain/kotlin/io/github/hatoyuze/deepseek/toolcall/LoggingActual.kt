package io.github.hatoyuze.deepseek.toolcall

/** 非 JVM 原生平台（macOS/iOS/Linux/Windows）的日志实现，输出到标准输出。 */
public actual class Logger actual constructor(private val name: String) {
    public actual fun info(msg: () -> String) {
        println("[INFO] $name: ${msg()}")
    }

    public actual fun error(msg: () -> String) {
        println("[ERROR] $name: ${msg()}")
    }
}
