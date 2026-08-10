package io.github.hatoyuze.deepseek.toolcall

import io.github.oshai.kotlinlogging.KotlinLogging

actual class Logger actual constructor(name: String) {
    private val delegate = KotlinLogging.logger(name)
    actual fun info(msg: () -> String) = delegate.info(msg)
}