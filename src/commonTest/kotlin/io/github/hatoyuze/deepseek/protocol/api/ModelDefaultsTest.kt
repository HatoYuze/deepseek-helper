package io.github.hatoyuze.deepseek.protocol.api

import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDefaultsTest {

    @Test
    fun `hardcoded model is deepseek-flash`() {
        // v4 名字已退役：库内只保留官方当前推荐的 deepseek-flash
        assertEquals("deepseek-flash", Model.Flash.id)
        assertEquals("model", Model.Flash.obj)
        assertEquals("deepseek", Model.Flash.owner)
    }

    @Test
    fun `resolvedModel defaults to Flash without network`() {
        // 未指定模型且使用无效 key 时，不应触发任何网络请求
        val ds = Deepseek("bad-key")
        assertEquals(Model.Flash, ds.resolvedModel)
    }

    @Test
    fun `DSL selects flash and custom`() {
        assertEquals(Model.Flash, deepseek("k") { model { flash() } }.resolvedModel)
        assertEquals("my-model", deepseek("k") { model { custom("my-model") } }.resolvedModel.id)
        // 退役名字仍可显式发送（服务端把它路由到 Flash）
        assertEquals(
            "deepseek-v4-pro",
            deepseek("k") { model { custom("deepseek-v4-pro") } }.resolvedModel.id,
        )
    }

    @Test
    fun `stateless resolvedModel also defaults to Flash`() {
        assertEquals(Model.Flash, statelessDeepseek("k") { }.resolvedModel)
    }
}
