package io.github.hatoyuze.deepseek.protocol.api

/**
 * 流式补全事件的公共祖先。
 *
 * [ChatChunk] 与 [FimChunk] 均继承自该类型，[SseHook] 因此可以统一接收两类流事件。
 */
public sealed class Chunk
