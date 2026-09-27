package id.kaloriku.shared.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFoodCatalogTest {

    @Test
    fun `every catalog id is unique`() {
        val ids = LocalFoodCatalog.foods.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `search finds a food by name and by alias`() {
        assertNotNull(LocalFoodCatalog.search("nasi goreng").firstOrNull { it.id == "nasi_goreng" })
        assertNotNull(LocalFoodCatalog.search("nasgor").firstOrNull { it.id == "nasi_goreng" })
        assertNotNull(LocalFoodCatalog.search("es teh").firstOrNull { it.id == "es_teh_manis" })
    }

    @Test
    fun `best match prefers the longest name`() {
        val match = LocalFoodCatalog.bestMatch("nasi goreng spesial")
        assertEquals("nasi_goreng", match?.id)
    }

    @Test
    fun `best match handles portion suffixes`() {
        assertEquals("sate_ayam", LocalFoodCatalog.bestMatch("Sate Ayam")?.id)
        assertEquals("sate_ayam", LocalFoodCatalog.bestMatch("sate ayam sepuluh tusuk")?.id)
        assertEquals("es_teh_manis", LocalFoodCatalog.bestMatch("es teh manis satu gelas")?.id)
        assertEquals("tempe_goreng", LocalFoodCatalog.bestMatch("tempe goreng dua potong")?.id)
    }

    @Test
    fun `best match returns null for unknown food`() {
        assertNull(LocalFoodCatalog.bestMatch(""))
        assertNull(LocalFoodCatalog.bestMatch("zzzz not a food"))
    }

    @Test
    fun `best match resolves an alias to its canonical food`() {
        assertEquals("nasi_goreng", LocalFoodCatalog.bestMatch("nasgor")?.id)
    }

    @Test
    fun `catalog covers every food category`() {
        val categories = LocalFoodCatalog.foods.map { it.category }.toSet()
        FoodCategory.entries.forEach { category ->
            assertTrue("missing category $category", category in categories)
        }
    }

    @Test
    fun `kcal values are non-negative and portions described`() {
        LocalFoodCatalog.foods.forEach { food ->
            assertTrue("${food.id} kcal", food.kcalPerPortion >= 0)
            assertTrue("${food.id} portion", food.portionLabel.isNotBlank())
        }
    }

    @Test
    fun `search is case insensitive and empty-safe`() {
        assertTrue(LocalFoodCatalog.search("").isEmpty())
        assertEquals(
            LocalFoodCatalog.search("sate ayam").map { it.id },
            LocalFoodCatalog.search("SATE AYAM").map { it.id },
        )
    }
}
