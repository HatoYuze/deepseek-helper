package io.github.hatoyuze.deepseek.protocol.api.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DeepSeek API 的模型。
 *
 * 库内只硬编码当前官方唯一推荐的模型 [Flash]（`deepseek-flash`），作为未显式指定模型时的
 * 默认值。`deepseek-v4-pro` / `deepseek-v4-flash` 一类 v4 名字已被官方退役（现在只是被路由到
 * Flash 的兼容别名：2026-09 实测两者与 `deepseek-flash` 的 token 用量完全一致），
 * 因此不再提供 `Model.Pro` 常量；确实需要发旧名字时用 `ModelSelector.custom("...")`。
 *
 * 需要获取线上最新列表时，可通过
 * [Deepseek.availableModels][io.github.hatoyuze.deepseek.protocol.api.Deepseek.availableModels] 获取：
 *
 * ```kotlin
 * val models = ds.availableModels()
 * val flash = Model.flash(models) ?: error("flash not available")
 * ```
 *
 * 注意：`/models` 端点可能仍列出已退役的 v4 名字（服务端列表滞后），能否调用才是判据。
 */
@Serializable
public data class Model(
    @SerialName("object") val obj: String,
    @SerialName("owned_by") val owner: String,
    val id: String,
) {
    public companion object {
        /**
         * 库内硬编码的 `deepseek-flash`：未显式指定模型时的默认值，也是当前唯一推荐的模型
         * （文本与图像输入都由它承担）。
         */
        public val Flash: Model = Model("model", "deepseek", "deepseek-flash")

        /** 从模型列表中按名称查找 */
        public fun ofModel(name: String, available: List<Model>): Model? =
            available.find { it.id == name }

        /** 查找 `deepseek-flash` 模型 */
        public fun flash(available: List<Model>): Model? = ofModel("deepseek-flash", available)
    }
}
