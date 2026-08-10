package com.github.hatoyuze.protocol.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DeepSeek API 支持的模型。
 *
 * 默认提供 [Flash] / [Pro] 两个库内硬编码的常用模型；需要获取线上最新列表时，
 * 可通过 [Deepseek.availableModels][com.github.hatoyuze.protocol.api.Deepseek.availableModels] 获取：
 *
 * ```kotlin
 * val models = ds.availableModels()
 * val v4Flash = Model.flash(models) ?: error("flash not available")
 * ```
 */
@Serializable
public data class Model(
    @SerialName("object") val obj: String,
    @SerialName("owned_by") val owner: String,
    val id: String,
) {
    public companion object {
        /** 库内硬编码的 deepseek-v4-flash 模型，作为未显式指定模型时的默认值 */
        public val Flash: Model = Model("model", "deepseek", "deepseek-v4-flash")

        /** 库内硬编码的 deepseek-v4-pro 模型 */
        public val Pro: Model = Model("model", "deepseek", "deepseek-v4-pro")

        /** 从模型列表中按名称查找 */
        public fun ofModel(name: String, available: List<Model>): Model? =
            available.find { it.id == name }

        /** 查找 deepseek-v4-pro 模型 */
        public fun pro(available: List<Model>): Model? = ofModel("deepseek-v4-pro", available)

        /** 查找 deepseek-v4-flash 模型 */
        public fun flash(available: List<Model>): Model? = ofModel("deepseek-v4-flash", available)
    }
}
