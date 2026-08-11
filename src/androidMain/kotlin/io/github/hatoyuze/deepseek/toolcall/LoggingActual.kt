package io.github.hatoyuze.deepseek.toolcall

import android.util.Log

/** Android 平台的日志实现，输出到 android.util.Log。 */
actual class Logger actual constructor(private val name: String) {
    actual fun info(msg: () -> String) {
        Log.i(name, msg())
    }
}
