package id.kaloriku.shared.analysis

import id.kaloriku.shared.FakeKenariApi
import id.kaloriku.shared.ai.JevQuestion
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.ai.KenariException
import id.kaloriku.shared.ai.WebSearchResult
import id.kaloriku.shared.domain.AnalyzedItem
import id.kaloriku.shared.domain.CalorieRubric
import id.kaloriku.shared.domain.FoodCategory
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Jev brand-detection gate added to [FoodAnalyzer].
 *
 * A branded item must be grounded with Kenari web search even when Jev would
 * otherwise call it local and clear, because the brand — not the generic dish —
 * determines the calories. Generic local foods must keep costing zero searches.
 */
class FoodAnalyzerBrandDetectionTest {

    // --------------------------------------------------------------- fixtures

    private val bensuExtraction = """
        {"ringkasan":"ayam geprek bensu","waktu":"makan_siang",
         "items":[{"nama":"Ayam Geprek Bensu","porsi":"1 porsi","gram":null}]}
    """.trimIndent()

    private val bensuLookupJson = """
        {"dikenali":true,"ringkasan":"Ayam geprek khas gerai Bensu",
         "porsi_acuan":"1 porsi","kcal_acuan":640,"sumber":"bensu.id"}
    """.trimIndent()

    private val bensuSearchHits = listOf(
        WebSearchResult(
            title = "Ayam Geprek Bensu",
            url = "https://bensu.id/",
            snippet = "Ayam geprek krispi dengan sambal bawang khas Bensu, sekitar 640 kkal per porsi.",
        ),
    )

    private fun extractionFor(name: String, portion: String = "1 porsi"): String =
        """{"ringkasan":"${name.lowercase()}","waktu":"makan_siang",
            "items":[{"nama":"$name","porsi":"$portion","gram":null}]}""".trimIndent()

    /**
     * A first-pass Jev answer for a single item. [brand], [local] and [clear] are the
     * three gates the pipeline reads; anything omitted defaults to a generic value.
     */
    private fun singleItemJev(
        brand: Double = 0.0,
        local: Double = 1.0,
        clear: Double = 1.0,
        bucket: Int = 4,
    ): JevResponse = JevResponse(
        model = "jev-1.13-free",
        answers = mapOf(
            "health" to FakeKenariApi.scoreAnswer(3.0),
            "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
            "item_0_kcal" to FakeKenariApi.scoreAnswer(bucket.toDouble(), mapOf("$bucket" to 1.0)),
            "item_0_portion" to FakeKenariApi.choiceAnswer("sedang"),
            "item_0_brand" to FakeKenariApi.noulAnswer(brand),
            "item_0_local" to FakeKenariApi.noulAnswer(local),
            "item_0_clear" to FakeKenariApi.noulAnswer(clear),
        ),
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun `brand question is asked in the first jev pass for every item`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionFor("Nasi Goreng", "1 piring"))
            .enqueueJev(singleItemJev(brand = 0.0, local = 1.0, clear = 1.0))
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("nasi goreng satu piring", LogSource.VOICE_PHONE)

        val questions = api.lastJevQuestions!!
        assertTrue(
            "first pass must ask item_0_brand; got ${questions.keys}",
            "item_0_brand" in questions,
        )
        val brand = questions["item_0_brand"]
        assertTrue(
            "item_0_brand must be a noul (yes/no) question, was ${brand?.let { it::class.simpleName }}",
            brand is JevQuestion.Noul,
        )
        assertTrue(
            "brand instructions must explain brand/outlet detection",
            brand!!.instructions.contains("merek") || brand.instructions.contains("gerai"),
        )
        assertTrue(
            "the brand question must carry explicit true/false criteria",
            (brand as JevQuestion.Noul).trueDescription != null &&
                brand.falseDescription != null,
        )
    }

    @Test
    fun `a branded item that jev calls both local and clear still triggers one web search`() = runTest {
        // This is the new behavior: brand gates first and outranks local/clear.
        val api = FakeKenariApi()
            .enqueueChat(bensuExtraction)
            .enqueueChat(bensuLookupJson)
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 4))
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 6))
            .enqueueSearch(bensuSearchHits)
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("ayam geprek bensu", LogSource.VOICE_PHONE)

        assertEquals(
            "a local+clear branded item must still cost exactly one search",
            1,
            api.searchCalls,
        )
        assertEquals(
            "a grounded item is re-asked in a second Jev pass",
            2,
            api.jevCalls,
        )
        assertTrue(
            "the search query must name the branded food",
            api.lastSearchQuery!!.contains("Ayam Geprek Bensu"),
        )
        assertEquals(1, result.items.size)
        assertTrue("the item must be marked web-grounded", result.items[0].webGrounded)
    }

    @Test
    fun `a branded item the local catalog knows is still grounded`() = runTest {
        // "Ayam Geprek Bensu" contains the catalog alias "ayam geprek", so it matches
        // locally. The brand gate must still fire before the catalog short-circuit.
        val api = FakeKenariApi()
            .enqueueChat(bensuExtraction)
            .enqueueChat(bensuLookupJson)
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 4))
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 6))
            .enqueueSearch(bensuSearchHits)
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("ayam geprek bensu", LogSource.VOICE_PHONE)

        assertEquals(
            "a catalog match must not suppress grounding for a branded item",
            1,
            api.searchCalls,
        )
        assertTrue(
            "the branded catalog-matching item must be web-grounded",
            result.items[0].webGrounded,
        )
    }

    @Test
    fun `a generic local food with brand zero triggers no search`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionFor("Sambal Terasi", "1 porsi"))
            .enqueueJev(singleItemJev(brand = 0.0, local = 1.0, clear = 1.0))
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("sambal terasi satu porsi", LogSource.VOICE_PHONE)

        assertEquals(
            "a generic local item must not spend a search",
            0,
            api.searchCalls,
        )
        assertEquals("no grounding means no second Jev pass", 1, api.jevCalls)
    }

    @Test
    fun `brand probability just below the threshold triggers no search`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionFor("Ayam Geprek Bensu"))
            .enqueueJev(singleItemJev(brand = 0.49, local = 1.0, clear = 1.0))
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("ayam geprek bensu", LogSource.VOICE_PHONE)

        assertEquals(
            "brand P(yes)=0.49 is below the 0.5 threshold, so no search",
            0,
            api.searchCalls,
        )
    }

    @Test
    fun `brand probability at the threshold triggers exactly one search`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionFor("Ayam Geprek Bensu"))
            .enqueueChat(bensuLookupJson)
            .enqueueJev(singleItemJev(brand = 0.50, local = 1.0, clear = 1.0, bucket = 4))
            .enqueueJev(singleItemJev(brand = 0.50, local = 1.0, clear = 1.0, bucket = 6))
            .enqueueSearch(bensuSearchHits)
        val analyzer = FoodAnalyzer(api)

        analyzer.analyze("ayam geprek bensu", LogSource.VOICE_PHONE)

        assertEquals(
            "brand P(yes)=0.50 is at the threshold and must search exactly once",
            1,
            api.searchCalls,
        )
    }

    @Test
    fun `web evidence reaches the second jev state and changes the final kcal`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(bensuExtraction)
            .enqueueChat(bensuLookupJson)
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 2))
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 6))
            .enqueueSearch(bensuSearchHits)
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("ayam geprek bensu", LogSource.VOICE_PHONE)

        val state = api.lastJevState!!
        assertTrue(
            "the second Jev state must carry the web summary; state was:\n$state",
            state.contains("Ayam geprek khas gerai Bensu"),
        )
        assertTrue(
            "the second Jev state must carry the reference calorie figure 640",
            state.contains("640"),
        )
        assertTrue(
            "the second Jev state must name the source domain",
            state.contains("bensu.id"),
        )

        // The second answer uses the LAUK_HEWANI bucket 6 (650-900 -> point 775),
        // while the first answer used bucket 2 (120-200 -> point 160).
        val groundedPoint = CalorieRubric.interpret(
            FakeKenariApi.scoreAnswer(6.0, mapOf("6" to 1.0)),
            FoodCategory.LAUK_HEWANI,
        ).first
        val ungroundedPoint = CalorieRubric.interpret(
            FakeKenariApi.scoreAnswer(2.0, mapOf("2" to 1.0)),
            FoodCategory.LAUK_HEWANI,
        ).first
        assertEquals(
            "the final estimate must come from the second, evidence-informed answer",
            groundedPoint,
            result.items[0].kcal,
        )
        assertNotEquals(
            "the grounded second answer must move the estimate away from the first",
            ungroundedPoint,
            result.items[0].kcal,
        )
    }

    @Test
    fun `a branded item with no usable search hits keeps the first jev estimate`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(bensuExtraction)
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 4))
        // No scripted search hits -> webSearch returns emptyList(), so no evidence.
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("ayam geprek bensu", LogSource.VOICE_PHONE)

        assertEquals(
            "a search must still have been attempted",
            1,
            api.searchCalls,
        )
        assertEquals(
            "without evidence the calorie question is not re-asked",
            1,
            api.jevCalls,
        )
        assertTrue(
            "the first Jev estimate must survive; got kcal=${result.items[0].kcal}",
            result.items[0].kcal > 0,
        )
        assertFalse(
            "an item with no evidence must not be flagged web-grounded",
            result.items[0].webGrounded,
        )
    }

    @Test
    fun `when jev is unavailable no search is spent and a calorie figure is still produced`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionFor("Burger Ayam Dikichi", "1 buah"))
        api.jevThrows = KenariException("jev down")
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("burger ayam dikichi", LogSource.VOICE_PHONE)

        assertEquals(
            "the fallback Jev sets brand=0.0, so no search may be spent",
            0,
            api.searchCalls,
        )
        assertEquals(1, result.items.size)
        assertTrue(
            "a positive calorie figure must still be produced; got ${result.items[0].kcal}",
            result.items[0].kcal > 0,
        )
        assertEquals(FoodAnalyzer.DEGRADED_ENGINE, result.engine)
    }

    @Test
    fun `reestimate with a branded new name triggers a search`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(bensuLookupJson)
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 4))
            .enqueueJev(singleItemJev(brand = 1.0, local = 1.0, clear = 1.0, bucket = 6))
            .enqueueSearch(bensuSearchHits)
        val analyzer = FoodAnalyzer(api)

        val updated = analyzer.reestimate(
            name = "Ayam Geprek Bensu",
            portionText = "1 porsi",
            grams = null,
            meal = MealType.MAKAN_SIANG,
            previous = previous(),
        )

        assertEquals(
            "a rename to a branded food must trigger a web lookup",
            1,
            api.searchCalls,
        )
        assertEquals(
            "a rename must not call the extraction chat model, only the lookup summariser",
            1,
            api.chatCalls,
        )
        assertEquals("Ayam Geprek Bensu", updated.name)
        assertTrue("a calorie figure must be produced", updated.kcal > 0)
    }

    @Test
    fun `reestimate with a generic new name does not search`() = runTest {
        val api = FakeKenariApi()
            .enqueueJev(singleItemJev(brand = 0.0, local = 1.0, clear = 1.0, bucket = 4))
        val analyzer = FoodAnalyzer(api)

        val updated = analyzer.reestimate(
            name = "Nasi Goreng",
            portionText = "1 piring",
            grams = null,
            meal = MealType.MAKAN_SIANG,
            previous = previous(),
        )

        assertEquals(
            "a generic rename must not spend a search",
            0,
            api.searchCalls,
        )
        assertEquals("no extraction chat is needed on edit", 0, api.chatCalls)
        assertEquals("no grounding means no second Jev pass", 1, api.jevCalls)
        assertTrue(updated.kcal > 0)
    }

    @Test
    fun `four branded items spend at most three searches`() = runTest {
        val extraction = """
            {"ringkasan":"empat makanan bermerek","waktu":"makan_siang",
             "items":[{"nama":"Bensu Satu","porsi":"1 porsi","gram":null},
                      {"nama":"Bensu Dua","porsi":"1 porsi","gram":null},
                      {"nama":"Bensu Tiga","porsi":"1 porsi","gram":null},
                      {"nama":"Bensu Empat","porsi":"1 porsi","gram":null}]}
        """.trimIndent()

        val api = FakeKenariApi().enqueueChat(extraction)
        repeat(3) {
            api.enqueueChat(bensuLookupJson)
            api.enqueueSearch(bensuSearchHits)
        }

        // First pass: every item carries a brand with P(yes)=1.0.
        api.enqueueJev(
            JevResponse(
                model = "jev-1.13-free",
                answers = (0 until 4).flatMap { i ->
                    listOf(
                        "item_${i}_kcal" to FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)),
                        "item_${i}_portion" to FakeKenariApi.choiceAnswer("sedang"),
                        "item_${i}_brand" to FakeKenariApi.noulAnswer(1.0),
                        "item_${i}_local" to FakeKenariApi.noulAnswer(1.0),
                        "item_${i}_clear" to FakeKenariApi.noulAnswer(1.0),
                    )
                }.toMap() + mapOf(
                    "health" to FakeKenariApi.scoreAnswer(2.0),
                    "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
                ),
            ),
        )
        // Second pass re-asks only the grounded items.
        api.enqueueJev(
            JevResponse(
                model = "jev-1.13-free",
                answers = (0 until 3).flatMap { i ->
                    listOf(
                        "item_${i}_kcal" to FakeKenariApi.scoreAnswer(6.0, mapOf("6" to 1.0)),
                        "item_${i}_portion" to FakeKenariApi.choiceAnswer("sedang"),
                        "item_${i}_brand" to FakeKenariApi.noulAnswer(1.0),
                        "item_${i}_local" to FakeKenariApi.noulAnswer(1.0),
                        "item_${i}_clear" to FakeKenariApi.noulAnswer(1.0),
                    )
                }.toMap(),
            ),
        )
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("empat makanan bermerek", LogSource.VOICE_PHONE)

        assertEquals(
            "the maxWebLookups cap must hold at three",
            3,
            api.searchCalls,
        )
        assertEquals("all four items are still returned", 4, result.items.size)
        assertTrue(
            "every item must still carry a positive estimate",
            result.items.all { it.kcal > 0 },
        )
        assertEquals(
            "only the three grounded items are re-asked, in one extra Jev call",
            2,
            api.jevCalls,
        )
    }

    // ----------------------------------------------------------------- helpers

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
}
