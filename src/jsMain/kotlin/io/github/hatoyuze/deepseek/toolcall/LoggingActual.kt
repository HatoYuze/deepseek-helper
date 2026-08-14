package io.github.hatoyuze.deepseek.toolcall

/** JS/Wasm 平台的日志实现，输出到浏览器或 Node.js 控制台。 */
public actual class Logger actual constructor(private val name: String) {
    public actual fun info(msg: () -> String) {
        console.info("[INFO] $name: ${msg()}")
    }

    public actual fun error(msg: () -> String) {
        console.error("[ERROR] $name: ${msg()}")
    }
}
