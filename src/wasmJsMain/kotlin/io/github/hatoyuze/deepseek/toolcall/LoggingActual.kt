package io.github.hatoyuze.deepseek.toolcall

/** Wasm 平台的日志实现，输出到标准输出（Node.js/浏览器控制台）。 */
actual class Logger actual constructor(private val name: String) {
    actual fun info(msg: () -> String) {
        println("[INFO] $name: ${msg()}")
    }
}
