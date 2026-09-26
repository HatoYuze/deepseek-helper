package io.github.hatoyuze.deepseek.protocol.api.entity

import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelTest {

    private val sampleModels = listOf(
        Model("model", "deepseek", "deepseek-flash"),
        Model("model", "deepseek", "deepseek-v4-pro"),
        Model("model", "deepseek", "deepseek-chat"),
    )

    @Test
    fun `ofModel finds existing model`() {
        val found = Model.ofModel("deepseek-flash", sampleModels)
        assertNotNull(found)
        assertEquals("deepseek-flash", found.id)
        assertEquals("deepseek", found.owner)
    }

    @Test
    fun `ofModel still finds a retired v4 name when the server lists it`() {
        // /models 可能滞后、仍列出已退役别名；按名字查找的通用能力不应因此失效
        val found = Model.ofModel("deepseek-v4-pro", sampleModels)
        assertNotNull(found)
        assertEquals("deepseek-v4-pro", found.id)
    }

    @Test
    fun `ofModel returns null for unknown model`() {
        assertNull(Model.ofModel("nonexistent", sampleModels))
    }

    @Test
    fun `flash finds deepseek-flash`() {
        val found = Model.flash(sampleModels)
        assertNotNull(found)
        assertEquals("deepseek-flash", found.id)
    }

    @Test
    fun `flash returns null when the account cannot use it`() {
        assertNull(Model.flash(listOf(Model("model", "deepseek", "other-model"))))
    }
}
