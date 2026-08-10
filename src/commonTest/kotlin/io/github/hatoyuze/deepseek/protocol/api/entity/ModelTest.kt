package io.github.hatoyuze.deepseek.protocol.api.entity
import kotlin.test.Test
import io.github.hatoyuze.deepseek.protocol.api.entity.Model
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ModelTest {

    private val sampleModels = listOf(
        Model("model", "deepseek", "deepseek-v4-pro"),
        Model("model", "deepseek", "deepseek-v4-flash"),
        Model("model", "deepseek", "deepseek-chat"),
    )

    @Test
    fun `ofModel finds existing model`() {
        val found = Model.ofModel("deepseek-v4-pro", sampleModels)
        assertNotNull(found)
        assertEquals("deepseek-v4-pro", found.id)
        assertEquals("deepseek", found.owner)
    }

    @Test
    fun `ofModel returns null for unknown model`() {
        val found = Model.ofModel("nonexistent", sampleModels)
        assertNull(found)
    }

    @Test
    fun `pro finds deepseek-v4-pro`() {
        val found = Model.pro(sampleModels)
        assertNotNull(found)
        assertEquals("deepseek-v4-pro", found.id)
    }

    @Test
    fun `flash finds deepseek-v4-flash`() {
        val found = Model.flash(sampleModels)
        assertNotNull(found)
        assertEquals("deepseek-v4-flash", found.id)
    }
}
