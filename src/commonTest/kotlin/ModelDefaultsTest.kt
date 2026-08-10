package com.github.hatoyuze.protocol.api

import com.github.hatoyuze.protocol.data.Model
import org.junit.Test
import kotlin.test.assertEquals

class ModelDefaultsTest {

    @Test
    fun `hardcoded flash and pro models`() {
        assertEquals("deepseek-v4-flash", Model.Flash.id)
        assertEquals("deepseek-v4-pro", Model.Pro.id)
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
    fun `DSL selects flash pro and custom`() {
        assertEquals(Model.Flash, deepseek("k") { model { flash() } }.resolvedModel)
        assertEquals(Model.Pro, deepseek("k") { model { pro() } }.resolvedModel)
        assertEquals("my-model", deepseek("k") { model { custom("my-model") } }.resolvedModel.id)
    }

    @Test
    fun `stateless resolvedModel also defaults to Flash`() {
        assertEquals(Model.Flash, statelessDeepseek("k") { }.resolvedModel)
    }
}
