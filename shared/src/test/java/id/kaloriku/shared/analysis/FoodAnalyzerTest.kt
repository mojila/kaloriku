package id.kaloriku.shared.analysis

import id.kaloriku.shared.FakeKenariApi
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.domain.LogSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodAnalyzerTest {

    private val extractionJson = """
        {"ringkasan":"nasi goreng dan es teh","waktu":"makan_siang",
         "items":[{"nama":"Nasi Goreng","porsi":"1 piring","gram":null},
                  {"nama":"Es Teh Manis","porsi":"1 gelas","gram":null}]}
    """.trimIndent()

    private fun jevResponse(): JevResponse = JevResponse(
        model = "jev-1.13-free",
        answers = mapOf(
            "health" to FakeKenariApi.scoreAnswer(3.0),
            "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
            "item_0_kcal" to FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)),
            "item_0_portion" to FakeKenariApi.choiceAnswer("sedang"),
            "item_0_local" to FakeKenariApi.noulAnswer(1.0),
            "item_0_clear" to FakeKenariApi.noulAnswer(1.0),
            "item_1_kcal" to FakeKenariApi.scoreAnswer(2.0, mapOf("2" to 1.0)),
            "item_1_portion" to FakeKenariApi.choiceAnswer("sedang"),
            "item_1_local" to FakeKenariApi.noulAnswer(1.0),
            "item_1_clear" to FakeKenariApi.noulAnswer(1.0),
        ),
    )

    @Test
    fun `analyze produces one item per detected food with a kcal range`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson).enqueueJev(jevResponse())
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("siang ini nasi goreng satu piring sama es teh manis", LogSource.VOICE_PHONE)

        assertEquals(2, result.items.size)
        assertEquals("Nasi Goreng", result.items[0].name)
        assertTrue(result.items[0].kcal > 0)
        assertTrue(result.items[0].kcalLow <= result.items[0].kcal)
        assertTrue(result.items[0].kcalHigh >= result.items[0].kcal)
        assertEquals(id.kaloriku.shared.domain.MealType.MAKAN_SIANG, result.meal)
        assertTrue(result.items.all { it.isLocal })
        assertEquals(result.items.sumOf { it.kcal }, result.totalKcal)
    }

    @Test
    fun `large portion multiplies the estimate`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson).enqueueJev(
            jevResponse().let { base ->
                base.copy(
                    answers = base.answers + mapOf(
                        // Jev decides the portion: level 4 = double the standard serving.
                        "item_0_portion_scale" to FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)),
                    ),
                )
            },
        )
        val analyzer = FoodAnalyzer(api)
        val result = analyzer.analyze("nasi goreng porsi besar dan es teh", LogSource.VOICE_PHONE)
        val baseRanges = id.kaloriku.shared.domain.CalorieRubric.buckets.getValue(
            id.kaloriku.shared.domain.FoodCategory.NASI,
        )
        // Bucket 4 base point scaled by Jev's 2.0x lands well above the raw bucket.
        assertTrue(result.items[0].kcal > baseRanges[4].last)
    }

    @Test
    fun `a standard portion scale leaves the estimate unchanged`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson).enqueueJev(
            jevResponse().let { base ->
                base.copy(
                    answers = base.answers + mapOf(
                        "item_0_portion_scale" to FakeKenariApi.scoreAnswer(2.0, mapOf("2" to 1.0)),
                    ),
                )
            },
        )
        val analyzer = FoodAnalyzer(api)
        val result = analyzer.analyze("nasi goreng satu piring", LogSource.VOICE_PHONE)
        val ranges = id.kaloriku.shared.domain.CalorieRubric.buckets.getValue(
            id.kaloriku.shared.domain.FoodCategory.NASI,
        )
        assertTrue("standard portion stays in bucket 4", result.items[0].kcal in ranges[4])
    }

    @Test
    fun `empty transcript yields an empty result that needs clarification`() = runTest {
        val analyzer = FoodAnalyzer(FakeKenariApi())
        val result = analyzer.analyze("   ", LogSource.VOICE_PHONE)
        assertTrue(result.items.isEmpty())
        assertTrue(result.needsClarification)
    }

    @Test
    fun `chat failure falls back to the local catalog`() = runTest {
        val api = FakeKenariApi().enqueueJev(jevResponse())
        api.chatThrows = id.kaloriku.shared.ai.KenariException("boom")
        val analyzer = FoodAnalyzer(api)
        val result = analyzer.analyze("nasi goreng dan es teh manis", LogSource.VOICE_PHONE)
        assertTrue("fallback finds catalog items", result.items.isNotEmpty())
    }

    @Test
    fun `jev failure falls back to catalog kcal without crashing`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson)
        api.jevThrows = id.kaloriku.shared.ai.KenariException("jev down")
        val analyzer = FoodAnalyzer(api)
        val result = analyzer.analyze("nasi goreng dan es teh manis", LogSource.VOICE_PHONE)
        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.kcal > 0 })
        // A Jev-less estimate is reported as degraded, never as a Jev judgement.
        assertEquals(FoodAnalyzer.DEGRADED_ENGINE, result.engine)
        assertTrue("degraded results ask for confirmation", result.needsClarification)
    }

    @Test
    fun `malformed chat json is tolerated`() = runTest {
        val api = FakeKenariApi().enqueueChat("not json at all").enqueueJev(jevResponse())
        val analyzer = FoodAnalyzer(api)
        val result = analyzer.analyze("nasi goreng satu piring", LogSource.VOICE_PHONE)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `duplicate detection surfaces the previous entry id`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson).enqueueJev(
            jevResponse().let { base ->
                base.copy(answers = base.answers + ("duplicate" to FakeKenariApi.choiceAnswer("CATATAN_0")))
            },
        )
        val analyzer = FoodAnalyzer(api)
        val existing = listOf(
            id.kaloriku.shared.domain.FoodEntry(
                id = 42,
                loggedAt = 0,
                dayKey = "2026-09-25",
                meal = id.kaloriku.shared.domain.MealType.MAKAN_SIANG,
                foodName = "Nasi Goreng",
                kcal = 380,
            ),
        )
        val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE, existingRecent = existing)
        assertTrue(result.isDuplicate)
        assertEquals(42L, result.duplicateOfId)
    }

    @Test
    fun `duplicate detection returns the entry Jev actually matched, not the newest`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson).enqueueJev(
            jevResponse().let { base ->
                // The third candidate is the real duplicate; the newest must not be blamed.
                base.copy(answers = base.answers + ("duplicate" to FakeKenariApi.choiceAnswer("CATATAN_2")))
            },
        )
        val analyzer = FoodAnalyzer(api)
        val existing = (1..3).map { n ->
            id.kaloriku.shared.domain.FoodEntry(
                id = n.toLong(),
                loggedAt = n.toLong(),
                dayKey = "2026-09-25",
                meal = id.kaloriku.shared.domain.MealType.MAKAN_SIANG,
                foodName = "Makanan $n",
                kcal = 300,
            )
        }
        val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE, existingRecent = existing)
        assertTrue(result.isDuplicate)
        assertEquals("the third candidate is the match", 3L, result.duplicateOfId)
    }

    @Test
    fun `a new log is not flagged as a duplicate`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson).enqueueJev(
            jevResponse().let { base ->
                base.copy(answers = base.answers + ("duplicate" to FakeKenariApi.choiceAnswer("BARU")))
            },
        )
        val analyzer = FoodAnalyzer(api)
        val existing = listOf(
            id.kaloriku.shared.domain.FoodEntry(
                id = 7,
                loggedAt = 0,
                dayKey = "2026-09-25",
                meal = id.kaloriku.shared.domain.MealType.SARAPAN,
                foodName = "Bubur Ayam",
                kcal = 292,
            ),
        )
        val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE, existingRecent = existing)
        assertFalse(result.isDuplicate)
        assertEquals(null, result.duplicateOfId)
    }

    @Test
    fun `meal falls back to the hour when jev is unsure`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(
                """{"ringkasan":"nasi goreng","waktu":"","items":[{"nama":"Nasi Goreng","porsi":"1 piring","gram":null}]}""",
            )
            .enqueueJev(
                jevResponse().let { base ->
                    base.copy(answers = base.answers + ("meal" to FakeKenariApi.choiceAnswer("CAMILAN", 0.1)))
                },
            )
        val analyzer = FoodAnalyzer(api)
        // 2026-09-25 03:00 WIB -> camilan by hour (no meal hint in the transcript).
        val threeAm = id.kaloriku.shared.domain.JakartaTime.startOfDayMillis("2026-09-25") + 3 * 3_600_000L
        val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE, nowMillis = threeAm)
        assertEquals(id.kaloriku.shared.domain.MealType.CAMILAN, result.meal)
    }

    @Test
    fun `ambiguous item is flagged for clarification`() = runTest {
        val api = FakeKenariApi().enqueueChat(extractionJson).enqueueJev(
            jevResponse().let { base ->
                base.copy(answers = base.answers + ("item_0_clear" to FakeKenariApi.noulAnswer(0.2)))
            },
        )
        val analyzer = FoodAnalyzer(api)
        val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE)
        assertTrue(result.needsClarification)
        assertFalse(result.items[0].needsClarification.not())
    }
}
