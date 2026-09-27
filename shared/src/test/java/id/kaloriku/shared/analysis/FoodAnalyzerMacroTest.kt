package id.kaloriku.shared.analysis

import id.kaloriku.shared.FakeKenariApi
import id.kaloriku.shared.ai.JevQuestion
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.domain.LogSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Macro classification must come from Jev, never from heuristics.
 * These tests pin the `item_{i}_macro` choice question and its mapping.
 */
class FoodAnalyzerMacroTest {

    private val extractionJson = """
        {"ringkasan":"ayam goreng dan nasi","waktu":"makan_siang",
         "items":[{"nama":"Ayam Goreng","porsi":"1 potong","gram":null},
                  {"nama":"Nasi Putih","porsi":"1 piring","gram":null}]}
    """.trimIndent()

    private fun jevResponse(macro0: String?, macro1: String?): JevResponse = JevResponse(
        model = "jev-1.13-free",
        answers = buildMap {
            put("health", FakeKenariApi.scoreAnswer(3.0))
            put("meal", FakeKenariApi.choiceAnswer("MAKAN_SIANG"))
            put("item_0_kcal", FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)))
            put("item_0_portion", FakeKenariApi.choiceAnswer("sedang"))
            put("item_0_local", FakeKenariApi.noulAnswer(1.0))
            put("item_0_clear", FakeKenariApi.noulAnswer(1.0))
            if (macro0 != null) put("item_0_macro", FakeKenariApi.choiceAnswer(macro0))
            put("item_1_kcal", FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)))
            put("item_1_portion", FakeKenariApi.choiceAnswer("sedang"))
            put("item_1_local", FakeKenariApi.noulAnswer(1.0))
            put("item_1_clear", FakeKenariApi.noulAnswer(1.0))
            if (macro1 != null) put("item_1_macro", FakeKenariApi.choiceAnswer(macro1))
        },
    )

    @Test
    fun `a macro choice question is asked per item`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionJson)
            .enqueueJev(jevResponse("protein", "karbohidrat"))
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("ayam goreng sama nasi", LogSource.VOICE_PHONE)

        val questions = api.lastJevQuestions!!
        val macro = questions["item_0_macro"]
        assertTrue("item_0_macro must be asked, got ${questions.keys}", macro != null)
        assertTrue(
            "item_0_macro must be a choice question, was ${macro?.let { it::class.simpleName }}",
            macro is JevQuestion.Choice,
        )
        assertTrue("item_1_macro must be asked", "item_1_macro" in questions)
    }

    @Test
    fun `jev macro answers land on the analyzed items`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionJson)
            .enqueueJev(jevResponse("protein", "karbohidrat"))
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("ayam goreng sama nasi", LogSource.VOICE_PHONE)

        assertEquals("protein", result.items[0].macroProfile)
        assertEquals("karbohidrat", result.items[1].macroProfile)
    }

    @Test
    fun `a missing macro answer defaults to tidak_jelas`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionJson)
            .enqueueJev(jevResponse(null, null))
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("ayam goreng sama nasi", LogSource.VOICE_PHONE)

        assertTrue(result.items.all { it.macroProfile == "tidak_jelas" })
    }

    @Test
    fun `an unknown macro answer defaults to tidak_jelas`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionJson)
            .enqueueJev(jevResponse("keju_leleh", "karbohidrat"))
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("ayam goreng sama nasi", LogSource.VOICE_PHONE)

        assertEquals("tidak_jelas", result.items[0].macroProfile)
        assertEquals("karbohidrat", result.items[1].macroProfile)
    }
}
