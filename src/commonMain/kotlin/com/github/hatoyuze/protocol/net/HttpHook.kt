package com.github.hatoyuze.protocol.net

/**
 * HTTP 请求/响应观察钩子。
 *
 * 通过 [HttpHookRegistry] 全局注册后，底层网络层发出的每个请求与响应都会回调到此处，
 * 可用于调试、日志或流量统计；默认实现为空操作。
 */
interface HttpHook {
    fun onRequest(method: String, url: String, headers: Map<String, String>, body: String?)

    fun onResponse(method: String, url: String, status: Int, headers: Map<String, String>, body: String?)

    /**
     * 流式响应中每个原始 SSE 事件数据回调。
     *
     * 实现方可在内部累积数据，并在 [onResponse] 中统一输出；默认空操作，
     * 不追踪 SSE 的钩子无需实现此方法。
     */
    fun onSseEvent(data: String) {}

    companion object {
        val NONE: HttpHook = object : HttpHook {
            override fun onRequest(method: String, url: String, headers: Map<String, String>, body: String?) {}
            override fun onResponse(method: String, url: String, status: Int, headers: Map<String, String>, body: String?) {}
        }
    }
}
