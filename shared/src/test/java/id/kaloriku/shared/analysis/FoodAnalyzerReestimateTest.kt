package id.kaloriku.shared.analysis

import id.kaloriku.shared.FakeKenariApi
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.ai.KenariException
import id.kaloriku.shared.domain.AnalyzedItem
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Editing a logged item re-asks Jev for the calorie estimate instead of scaling the
 * old number, because a renamed food is a different food.
 */
class FoodAnalyzerReestimateTest {

    private fun previous() = AnalyzedItem(
        name = "Nasi Goreng",
        canonicalName = "nasi_goreng",
        portionText = "1 piring",
        kcal = 380,
        kcalLow = 300,
        kcalHigh = 500,
        confidence = 0.8,
        isLocal = true,
    )

    private fun jevResponse(bucket: Int = 6): JevResponse = JevResponse(
        model = "jev-1.13-free",
        answers = mapOf(
            "health" to FakeKenariApi.scoreAnswer(3.0),
            "meal" to FakeKenariApi.choiceAnswer("MAKAN_MALAM"),
            "item_0_kcal" to FakeKenariApi.scoreAnswer(bucket.toDouble(), mapOf("$bucket" to 1.0)),
            "item_0_portion" to FakeKenariApi.choiceAnswer("besar"),
            // Jev decides the portion scale (level 4 = double a standard serving).
            "item_0_portion_scale" to FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)),
            "item_0_local" to FakeKenariApi.noulAnswer(1.0),
            "item_0_clear" to FakeKenariApi.noulAnswer(1.0),
        ),
    )

    @Test
    fun `reestimate asks jev and returns a new estimate`() = runTest {
        val api = FakeKenariApi().enqueueJev(jevResponse())
        val analyzer = FoodAnalyzer(api)

        val updated = analyzer.reestimate(
            name = "Rendang",
            portionText = "2 potong",
            grams = null,
            meal = MealType.MAKAN_MALAM,
            previous = previous(),
        )

        assertEquals("Rendang", updated.name)
        assertEquals("2 potong", updated.portionText)
        assertTrue("the estimate must be recomputed", updated.kcal != previous().kcal)
        assertTrue(updated.kcalLow <= updated.kcal)
        assertTrue(updated.kcalHigh >= updated.kcal)
        assertEquals("the meal decision is re-asked", 1, api.jevCalls)
    }

    @Test
    fun `reestimate does not need a chat call`() = runTest {
        val api = FakeKenariApi().enqueueJev(jevResponse())
        val analyzer = FoodAnalyzer(api)

        analyzer.reestimate("Sate Ayam", "10 tusuk", null, MealType.MAKAN_MALAM, previous())

        assertEquals("the name is already clean, no extraction needed", 0, api.chatCalls)
    }

    @Test
    fun `reestimate passes the edited name and portion to jev`() = runTest {
        val api = FakeKenariApi().enqueueJev(jevResponse())
        val analyzer = FoodAnalyzer(api)

        analyzer.reestimate("Soto Betawi", "1 mangkuk besar", 400.0, MealType.MAKAN_SIANG, previous())

        val state = api.lastJevState!!
        assertTrue("state should carry the edited name", state.contains("Soto Betawi"))
        assertTrue("state should carry the edited portion", state.contains("1 mangkuk besar"))
        assertTrue("state should carry the edited weight", state.contains("400"))
    }

    @Test
    fun `reestimate falls back to a catalog estimate when jev is down`() = runTest {
        val api = FakeKenariApi()
        api.jevThrows = KenariException("jev down")
        val analyzer = FoodAnalyzer(api)

        val updated = analyzer.reestimate("Nasi Goreng", "1 piring", null, MealType.MAKAN_SIANG, previous())

        // Must not throw, and must still produce a usable number.
        assertEquals("Nasi Goreng", updated.name)
        assertTrue("a calorie figure is always produced", updated.kcal > 0)
    }

    @Test
    fun `a blank name keeps the previous values instead of wiping the entry`() = runTest {
        val api = FakeKenariApi().enqueueJev(jevResponse())
        val analyzer = FoodAnalyzer(api)

        val updated = analyzer.reestimate("   ", "", null, MealType.MAKAN_SIANG, previous())

        assertEquals(previous(), updated)
        assertEquals("a blank edit must not reach Jev", 0, api.jevCalls)
    }
}
