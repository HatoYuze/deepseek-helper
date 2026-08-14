package io.github.hatoyuze.deepseek.toolcall

import io.github.oshai.kotlinlogging.KotlinLogging

public actual class Logger actual constructor(name: String) {
    private val delegate = KotlinLogging.logger(name)
    public actual fun info(msg: () -> String): Unit = delegate.info(msg)
    public actual fun error(msg: () -> String): Unit = delegate.error(msg)
}