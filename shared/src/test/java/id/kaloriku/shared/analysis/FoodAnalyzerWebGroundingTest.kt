package id.kaloriku.shared.analysis

import id.kaloriku.shared.FakeKenariApi
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.ai.WebSearchResult
import id.kaloriku.shared.domain.CalorieRubric
import id.kaloriku.shared.domain.FoodCategory
import id.kaloriku.shared.domain.LogSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A branded or restaurant food the local catalog cannot place ("Burger Ayam Dikichi")
 * must be grounded with Kenari web search, and the evidence must reach Jev — which
 * still owns the calorie decision. Ordinary Indonesian foods and clear foreign foods
 * must not spend a search.
 */
class FoodAnalyzerWebGroundingTest {

    private val burgerExtraction = """
        {"ringkasan":"burger ayam dikichi","waktu":"makan_siang",
         "items":[{"nama":"Burger Ayam Dikichi","porsi":"1 buah","gram":null}]}
    """.trimIndent()

    private val lookupJson = """
        {"dikenali":true,"ringkasan":"Burger ayam crispy khas gerai Dikichi",
         "porsi_acuan":"1 buah","kcal_acuan":520,"sumber":"dikichi.id"}
    """.trimIndent()

    private val searchHits = listOf(
        WebSearchResult(
            title = "Dikichi Fried Chicken",
            url = "https://dikichi.id/",
            snippet = "Chicken Buzz Burger dengan boneless crispy hot chicken, Rp 21.500.",
        ),
    )

    /** First pass: Jev does not know the food, so it flags it as unclear and non-local. */
    private fun unknownItemJev(bucket: Int = 3): JevResponse = JevResponse(
        model = "jev-1.13-free",
        answers = mapOf(
            "health" to FakeKenariApi.scoreAnswer(2.0),
            "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
            "item_0_kcal" to FakeKenariApi.scoreAnswer(bucket.toDouble(), mapOf("$bucket" to 1.0)),
            "item_0_portion" to FakeKenariApi.choiceAnswer("sedang"),
            "item_0_local" to FakeKenariApi.noulAnswer(0.1),
            "item_0_clear" to FakeKenariApi.noulAnswer(0.2),
        ),
    )

    /** Second pass: the same calorie question, now answered with the web evidence. */
    private fun groundedJev(bucket: Int = 5): JevResponse = JevResponse(
        model = "jev-1.13-free",
        answers = mapOf(
            "item_0_kcal" to FakeKenariApi.scoreAnswer(bucket.toDouble(), mapOf("$bucket" to 1.0)),
            "item_0_portion" to FakeKenariApi.choiceAnswer("sedang"),
            "item_0_local" to FakeKenariApi.noulAnswer(0.1),
            "item_0_clear" to FakeKenariApi.noulAnswer(0.9),
        ),
    )

    @Test
    fun `an unrecognized branded food is grounded with web search`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(burgerExtraction)
            .enqueueChat(lookupJson)
            .enqueueJev(unknownItemJev())
            .enqueueJev(groundedJev())
            .enqueueSearch(searchHits)
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("burger ayam dikichi", LogSource.VOICE_PHONE)

        assertEquals("one web lookup", 1, api.searchCalls)
        assertEquals("the calorie question is re-asked with evidence", 2, api.jevCalls)
        assertTrue("the search query names the food", api.lastSearchQuery!!.contains("Burger Ayam Dikichi"))
        assertEquals(1, result.items.size)
        assertEquals("Burger Ayam Dikichi", result.items[0].name)
    }

    @Test
    fun `web evidence and the calorie reference reach the second jev state`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(burgerExtraction)
            .enqueueChat(lookupJson)
            .enqueueJev(unknownItemJev())
            .enqueueJev(groundedJev())
            .enqueueSearch(searchHits)
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("burger ayam dikichi", LogSource.VOICE_PHONE)

        val state = api.lastJevState!!
        assertTrue("state carries the web summary", state.contains("Burger ayam crispy"))
        assertTrue("state carries the source calorie reference", state.contains("520"))
        assertTrue("state names the source", state.contains("dikichi.id"))
        val questions = api.lastJevQuestions!!
        assertTrue("only the item questions are re-asked", "item_0_kcal" in questions)
        assertFalse("the meal question is not re-asked", "meal" in questions)
        assertFalse("the health question is not re-asked", "health" in questions)
        // A web snippet must not be able to rewrite Jev's classification of the item.
        assertFalse("brand is not re-asked", "item_0_brand" in questions)
        assertFalse("local is not re-asked", "item_0_local" in questions)
        assertFalse("clarity is not re-asked", "item_0_clear" in questions)
        assertFalse("macro is not re-asked", "item_0_macro" in questions)
    }

    @Test
    fun `the second jev answer overrides the first estimate`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(burgerExtraction)
            .enqueueChat(lookupJson)
            .enqueueJev(unknownItemJev(bucket = 2))
            .enqueueJev(groundedJev(bucket = 6))
            .enqueueSearch(searchHits)
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("burger ayam dikichi", LogSource.VOICE_PHONE)

        // Bucket 6 of LAINNYA (450..700 -> mid 575) must beat bucket 2 (100..180 -> mid 140).
        val lower = CalorieRubric.buckets.getValue(FoodCategory.LAINNYA)[2].first
        assertTrue("the grounded estimate must win", result.items[0].kcal > lower)
        assertTrue("the evidence raises confidence in the item", result.items[0].confidence > 0)
    }

    @Test
    fun `a catalog food never triggers a web search`() = runTest {
        val extraction = """
            {"ringkasan":"nasi goreng","waktu":"makan_siang",
             "items":[{"nama":"Nasi Goreng","porsi":"1 piring","gram":null}]}
        """.trimIndent()
        val api = FakeKenariApi()
            .enqueueChat(extraction)
            .enqueueJev(
                JevResponse(
                    model = "jev-1.13-free",
                    answers = mapOf(
                        "health" to FakeKenariApi.scoreAnswer(3.0),
                        "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
                        "item_0_kcal" to FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)),
                        "item_0_portion" to FakeKenariApi.choiceAnswer("sedang"),
                        "item_0_local" to FakeKenariApi.noulAnswer(1.0),
                        "item_0_clear" to FakeKenariApi.noulAnswer(1.0),
                    ),
                ),
            )
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("nasi goreng satu piring", LogSource.VOICE_PHONE)

        assertEquals("catalog match costs no search", 0, api.searchCalls)
        assertEquals("no second Jev pass", 1, api.jevCalls)
    }

    @Test
    fun `a clear non-local food is not searched`() = runTest {
        val extraction = """
            {"ringkasan":"hamburger","waktu":"makan_siang",
             "items":[{"nama":"Hamburger","porsi":"1 buah","gram":null}]}
        """.trimIndent()
        val api = FakeKenariApi()
            .enqueueChat(extraction)
            .enqueueJev(
                JevResponse(
                    model = "jev-1.13-free",
                    answers = mapOf(
                        "health" to FakeKenariApi.scoreAnswer(2.0),
                        "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
                        "item_0_kcal" to FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)),
                        "item_0_portion" to FakeKenariApi.choiceAnswer("sedang"),
                        "item_0_local" to FakeKenariApi.noulAnswer(0.0),
                        "item_0_clear" to FakeKenariApi.noulAnswer(0.9),
                    ),
                ),
            )
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("hamburger satu buah", LogSource.VOICE_PHONE)

        assertEquals("Jev knows this food, no search needed", 0, api.searchCalls)
    }

    @Test
    fun `a failed search keeps the first jev estimate`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(burgerExtraction)
            .enqueueJev(unknownItemJev(bucket = 4))
        // No scripted search hits: webSearch returns an empty list.
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("burger ayam dikichi", LogSource.VOICE_PHONE)

        assertEquals("a search was attempted", 1, api.searchCalls)
        assertEquals("no second Jev pass without evidence", 1, api.jevCalls)
        assertEquals(1, result.items.size)
        assertTrue("a calorie figure is still produced", result.items[0].kcal > 0)
    }

    @Test
    fun `an irrelevant search result is discarded`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(burgerExtraction)
            .enqueueChat("""{"dikenali":false,"ringkasan":"","porsi_acuan":"","kcal_acuan":null,"sumber":""}""")
            .enqueueJev(unknownItemJev(bucket = 4))
            .enqueueSearch(searchHits)
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("burger ayam dikichi", LogSource.VOICE_PHONE)

        assertEquals(1, api.searchCalls)
        assertEquals("irrelevant hits must not reach Jev", 1, api.jevCalls)
        assertTrue(result.items[0].kcal > 0)
    }

    @Test
    fun `web lookups are capped so one utterance cannot fan out`() = runTest {
        val extraction = """
            {"ringkasan":"empat makanan tak dikenal","waktu":"makan_siang",
             "items":[{"nama":"Kue Zorba","porsi":"","gram":null},
                      {"nama":"Minuman Qux","porsi":"","gram":null},
                      {"nama":"Snack Blorp","porsi":"","gram":null},
                      {"nama":"Roti Wibble","porsi":"","gram":null}]}
        """.trimIndent()
        val api = FakeKenariApi().enqueueChat(extraction)
        repeat(4) {
            api.enqueueChat(lookupJson)
            api.enqueueSearch(searchHits)
        }
        val unknown = { i: Int ->
            JevResponse(
                model = "jev-1.13-free",
                answers = mapOf(
                    "health" to FakeKenariApi.scoreAnswer(2.0),
                    "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
                    "item_${i}_kcal" to FakeKenariApi.scoreAnswer(3.0, mapOf("3" to 1.0)),
                    "item_${i}_portion" to FakeKenariApi.choiceAnswer("sedang"),
                    "item_${i}_local" to FakeKenariApi.noulAnswer(0.1),
                    "item_${i}_clear" to FakeKenariApi.noulAnswer(0.2),
                ),
            )
        }
        api.enqueueJev(
            JevResponse(
                model = "jev-1.13-free",
                answers = (0 until 4).flatMap { i ->
                    listOf(
                        "item_${i}_kcal" to FakeKenariApi.scoreAnswer(3.0, mapOf("3" to 1.0)),
                        "item_${i}_portion" to FakeKenariApi.choiceAnswer("sedang"),
                        "item_${i}_local" to FakeKenariApi.noulAnswer(0.1),
                        "item_${i}_clear" to FakeKenariApi.noulAnswer(0.2),
                    )
                }.toMap() + mapOf(
                    "health" to FakeKenariApi.scoreAnswer(2.0),
                    "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
                ),
            ),
        )
        api.enqueueJev(groundedJev())
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("empat makanan", LogSource.VOICE_PHONE)

        assertEquals("at most three lookups", 3, api.searchCalls)
        assertEquals(4, result.items.size)
        assertTrue(result.items.all { it.kcal > 0 })
        assertTrue("an ungrounded item keeps its Jev estimate", unknown(3).answers.isNotEmpty())
    }
}
