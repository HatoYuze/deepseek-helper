package io.github.hatoyuze.deepseek.toolcall

/**
 * 内置服务端联网搜索工具的魔法名称。
 *
 * 该名称不匹配 Responses API 的函数名规则（`^[a-zA-Z0-9_-]+$`），
 * 仅作为库内部句柄，用于：
 * - [io.github.hatoyuze.deepseek.protocol.api.ChatConfig.enableWebSearch] 开启时，
 *   把模型返回的 `web_search_call` 以统一的 `ChatChunk.ToolCallRequest` 形式对外暴露；
 * - 工具管道对该名字跳过“查找 + 执行”（服务端已执行），直接返回成功结果。
 */
public const val DEEPSEEK_WEB_SEARCH_TOOL: String = "_deepseek__web_search"
