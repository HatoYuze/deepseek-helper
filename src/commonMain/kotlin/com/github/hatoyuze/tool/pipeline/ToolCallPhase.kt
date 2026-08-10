package com.github.hatoyuze.tool.pipeline

/**
 * 工具调用管道的生命周期阶段，严格按声明顺序执行。
 *
 * 每个 phase 可注册多个拦截器，拦截器按注册的逆序执行（洋葱模型）。
 */
public enum class ToolCallPhase {
    /** 参数校验 */
    VALIDATE,

    /** 权限鉴权 */
    AUTHORIZE,

    /** 参数转换（反序列化等） */
    TRANSFORM,

    /** 核心执行 */
    EXECUTE,

    /** 后处理 */
    POST_PROCESS,

    /** 错误处理（异常时进入） */
    ERROR,
}
