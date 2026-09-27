package id.kaloriku.shared.analysis

import id.kaloriku.shared.FakeFoodEntryDao
import id.kaloriku.shared.FakeKenariApi
import id.kaloriku.shared.FakeSettingsSource
import id.kaloriku.shared.ai.JevResponse
import id.kaloriku.shared.ai.KenariException
import id.kaloriku.shared.data.FoodRepository
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import id.kaloriku.shared.sync.SyncCodec
import id.kaloriku.shared.sync.SyncMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for the audit fixes that hardened [FoodAnalyzer] against model output.
 *
 * Two failure shapes are pinned here:
 *
 *  - A model that answers a weight with a phrase instead of a number used to leak a
 *    non-finite `Double` into the persisted model. `JSONObject.put` refuses non-finite
 *    doubles, so the entry was visible in the app but blew up the sync push and the
 *    backup export. The contract that matters is end-to-end: a bad weight survives
 *    analysis, persistence and a sync round-trip, and reads back as "not stated".
 *
 *  - The fallback paths used to read the wall clock instead of the `nowMillis` the
 *    caller passed, so a result computed for one instant could report a meal derived
 *    from a different one. With a fixed `nowMillis` the fallback meal must be a pure
 *    function of that instant, independent of when the test runs.
 */
class FoodAnalyzerRobustnessTest {

    private val dayKey = "2026-09-25"

    /** The instant every fallback test is anchored to: 2026-09-25 in Asia/Jakarta. */
    private fun at(hour: Int, minute: Int = 0): Long = JakartaTime.atTime(dayKey, hour, minute)

    // --------------------------------------------------------------- fixtures

    /**
     * A one-item extraction whose weight is scripted verbatim, so each test can control
     * exactly what the model claims the weight is. The meal hint is deliberately blank:
     * the meal must then come from the passed `nowMillis`, never from a fixed string.
     */
    private fun extractionWithGram(gramLiteral: String): String = """
        {"ringkasan":"nasi goreng","waktu":"",
         "items":[{"nama":"Nasi Goreng","porsi":"1 piring","gram":$gramLiteral}]}
    """.trimIndent()

    /**
     * A complete single-item Jev answer. NASI bucket 4 keeps the estimate clearly
     * positive, and local/clear/brand are set so no web lookup is triggered.
     */
    private fun singleItemJev(): JevResponse = JevResponse(
        model = "jev-1.13-free",
        answers = mapOf(
            "health" to FakeKenariApi.scoreAnswer(3.0),
            "meal" to FakeKenariApi.choiceAnswer("MAKAN_SIANG"),
            "item_0_kcal" to FakeKenariApi.scoreAnswer(4.0, mapOf("4" to 1.0)),
            "item_0_portion_scale" to FakeKenariApi.scoreAnswer(2.0, mapOf("2" to 1.0)),
            "item_0_macro" to FakeKenariApi.choiceAnswer("karbohidrat"),
            "item_0_brand" to FakeKenariApi.noulAnswer(0.0),
            "item_0_local" to FakeKenariApi.noulAnswer(1.0),
            "item_0_clear" to FakeKenariApi.noulAnswer(1.0),
        ),
    )

    private fun repo(dao: FakeFoodEntryDao) = FoodRepository(dao, FakeSettingsSource())

    // ------------------------------------------------- bad weight: not "NaN"

    @Test
    fun `a non numeric gram is treated as not stated and does not drop the food`() = runTest {
        // "sekitar 100" is what a chat model produces for a weight it can only describe.
        // optDouble answers NaN for it, which used to be stored verbatim. The item must
        // still be produced with a real calorie figure: a bad weight is not a bad food.
        val api = FakeKenariApi()
            .enqueueChat(extractionWithGram("\"sekitar 100\""))
            .enqueueJev(singleItemJev())
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("nasi goreng porsi sekitar seratus gram", LogSource.VOICE_PHONE)

        assertEquals(1, result.items.size)
        assertNull("a non-numeric weight must read as 'not stated', never NaN", result.items[0].grams)
        assertTrue(
            "the food must survive a bad weight; got kcal=${result.items[0].kcal}",
            result.items[0].kcal > 0,
        )
    }

    @Test
    fun `a negative gram is treated as not stated`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionWithGram("-50"))
            .enqueueJev(singleItemJev())
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE)

        assertEquals(1, result.items.size)
        assertNull("a negative weight is meaningless and must be dropped", result.items[0].grams)
        assertTrue(result.items[0].kcal > 0)
    }

    @Test
    fun `a zero gram is treated as not stated`() = runTest {
        val api = FakeKenariApi()
            .enqueueChat(extractionWithGram("0"))
            .enqueueJev(singleItemJev())
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE)

        assertEquals(1, result.items.size)
        assertNull("a zero weight is not a weight; it must be dropped", result.items[0].grams)
        assertTrue(result.items[0].kcal > 0)
    }

    @Test
    fun `a valid gram still comes through unchanged`() = runTest {
        // Guards against over-correcting: dropping bad weights must not drop good ones.
        val api = FakeKenariApi()
            .enqueueChat(extractionWithGram("150"))
            .enqueueJev(singleItemJev())
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("nasi goreng 150 gram", LogSource.VOICE_PHONE)

        assertEquals(1, result.items.size)
        assertEquals(150.0, result.items[0].grams!!, 0.0)
    }

    // ------------------------------------------- end-to-end persistence contract

    @Test
    fun `a bad-grams analysis persists and round-trips through the sync codec without throwing`() = runTest {
        // This is the regression the original audit caught: the analysis itself was fine,
        // but the poisoned grams value only exploded later, in FoodEntryJson.encode, which
        // backs both the sync push and the backup file. Persisting and encoding here is
        // what actually exercises the JSONObject.put that rejects non-finite numbers.
        val api = FakeKenariApi()
            .enqueueChat(extractionWithGram("\"sekitar 100\""))
            .enqueueJev(singleItemJev())
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("nasi goreng porsi sekitar seratus gram", LogSource.VOICE_PHONE)
        assertNull("precondition: the bad weight is already 'not stated'", result.items[0].grams)

        val dao = FakeFoodEntryDao()
        val repository = repo(dao)
        repository.saveAnalysis(result, LogSource.VOICE_PHONE, nowMillis = at(13))

        val stored = repository.recent().single()
        assertNull("the persisted entry must carry no weight", stored.grams)

        // The sync push path, verbatim: an entry with a non-finite grams would throw
        // JSONException here and break delivery to the watch.
        val encoded = SyncCodec.encode(
            SyncMessage.NewEntries(listOf(stored), LogSource.VOICE_PHONE),
        )
        val decoded = SyncCodec.decode(SyncMessage.PATH_NEW_ENTRIES, encoded) as SyncMessage.NewEntries
        val roundTripped = decoded.entries.single()

        assertNull("the weight must survive the round-trip as null", roundTripped.grams)
        assertEquals("the calories must survive intact", stored.kcal, roundTripped.kcal)
        assertEquals("the food name must survive intact", stored.foodName, roundTripped.foodName)
    }

    // ------------------------------------- deterministic fallback meal (no chat)

    @Test
    fun `chat failure derives the fallback meal from the passed nowMillis`() = runTest {
        // fallbackExtraction used to read System.currentTimeMillis(), so the meal depended
        // on the wall clock at test time rather than the instant the caller asked for.
        // A low-confidence Jev meal answer forces resolveMeal onto the extraction's hint,
        // which is the value fallbackExtraction computed from nowMillis.
        val windows = listOf(
            8 to MealType.SARAPAN,
            13 to MealType.MAKAN_SIANG,
            19 to MealType.MAKAN_MALAM,
            2 to MealType.CAMILAN,
        )
        windows.forEach { (hour, expected) ->
            val api = FakeKenariApi()
            api.chatThrows = KenariException("chat down")
            api.enqueueJev(
                singleItemJev().let { base ->
                    base.copy(
                        answers = base.answers + (
                            "meal" to FakeKenariApi.choiceAnswer("CAMILAN", confidence = 0.1)
                            ),
                    )
                },
            )
            val analyzer = FoodAnalyzer(api)

            val result = analyzer.analyze(
                "nasi goreng dan es teh manis",
                LogSource.VOICE_PHONE,
                nowMillis = at(hour),
            )

            assertEquals(
                "fallback meal at $hour:00 WIB must be $expected",
                expected,
                result.meal,
            )
        }
    }

    // -------------------------------- deterministic fallback meal (no Jev)

    @Test
    fun `jev failure derives the fallback meal from the passed nowMillis and flags degraded`() = runTest {
        // fallbackJev used to read System.currentTimeMillis() as well. The extraction has
        // no meal hint, so the only source for the meal is the nowMillis the caller passed.
        val windows = listOf(
            8 to MealType.SARAPAN,
            13 to MealType.MAKAN_SIANG,
            19 to MealType.MAKAN_MALAM,
            2 to MealType.CAMILAN,
        )
        windows.forEach { (hour, expected) ->
            val api = FakeKenariApi()
                .enqueueChat(
                    """{"ringkasan":"nasi goreng","waktu":"",
                        "items":[{"nama":"Nasi Goreng","porsi":"1 piring","gram":null}]}""".trimIndent(),
                )
            api.jevThrows = KenariException("jev down")
            val analyzer = FoodAnalyzer(api)

            val result = analyzer.analyze("nasi goreng", LogSource.VOICE_PHONE, nowMillis = at(hour))

            assertEquals(
                "Jev-less fallback meal at $hour:00 WIB must be $expected",
                expected,
                result.meal,
            )
            assertEquals(
                "a Jev-less estimate must be reported as degraded",
                FoodAnalyzer.DEGRADED_ENGINE,
                result.engine,
            )
            assertEquals(1, result.items.size)
            assertTrue(
                "a calorie figure must still be produced; got ${result.items[0].kcal}",
                result.items[0].kcal > 0,
            )
        }
    }

    // ------------------------------------------------ empty transcript uses nowMillis

    @Test
    fun `empty transcript derives the meal from the passed nowMillis`() = runTest {
        // emptyResult took no clock at all before the fix. The empty result still has to
        // report a plausible meal for the instant the caller asked about.
        val api = FakeKenariApi()
        val analyzer = FoodAnalyzer(api)

        val result = analyzer.analyze("", LogSource.VOICE_PHONE, nowMillis = at(19))

        assertTrue("an empty transcript yields no items", result.items.isEmpty())
        assertEquals(
            "the empty result must use the passed hour, not the wall clock",
            MealType.MAKAN_MALAM,
            result.meal,
        )
        assertTrue("an empty result still asks for clarification", result.needsClarification)
        assertEquals("no model call may be made for an empty transcript", 0, api.chatCalls)
    }
}
