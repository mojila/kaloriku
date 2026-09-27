package id.kaloriku.shared.data

import id.kaloriku.shared.domain.AppSettings
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the backup document format: every field of an entry must survive a
 * round trip, tombstones must be carried (a backup that dropped them would let a
 * deleted entry come back on the next sync), and a malformed or foreign file must
 * be reported as a typed failure rather than throwing on the user's data.
 */
class BackupCodecTest {

    private val base = 1790300000000L

    /**
     * A fully populated entry: every field non-default so a dropped field is caught
     * by the round trip. Nullable fields are deliberately null except where a case
     * needs them set, and those cases build their own entry.
     */
    private fun entry(
        syncId: String = "sync-1",
        deleted: Boolean = false,
        loggedAt: Long = base,
        updatedAt: Long = base + 500,
    ) = FoodEntry(
        id = 42,
        syncId = syncId,
        loggedAt = loggedAt,
        dayKey = "2026-09-25",
        meal = MealType.MAKAN_SIANG,
        foodName = "Nasi Goreng",
        canonicalName = "nasi goreng",
        portionText = "1 porsi",
        grams = 250.0,
        kcal = 420,
        kcalLow = 350,
        kcalHigh = 500,
        confidence = 0.83,
        isLocal = true,
        webGrounded = true,
        healthScore = 6.5,
        source = LogSource.VOICE_PHONE,
        rawTranscript = "makan nasi goreng satu porsi",
        notes = "enak",
        updatedAt = updatedAt,
        deleted = deleted,
    )

    private fun backup(
        entries: List<FoodEntry> = listOf(entry()),
        settings: AppSettings = AppSettings(2200),
        createdAt: Long = base + 10_000,
        appVersion: String = "1.2.3",
    ) = BackupFile(entries, settings, createdAt, appVersion)

    private fun decoded(raw: String): BackupFile =
        when (val result = BackupCodec.decode(raw)) {
            is BackupParseResult.Success -> result.file
            is BackupParseResult.Failure -> error("expected Success but got ${result.error}")
        }

    private fun failure(raw: String): BackupError =
        when (val result = BackupCodec.decode(raw)) {
            is BackupParseResult.Success -> error("expected Failure but got $result")
            is BackupParseResult.Failure -> result.error
        }

    // ------------------------------------------------------------------ round trip

    @Test
    fun `encode then decode returns every non-null field of every entry unchanged`() = runTest {
        val original = backup(
            entries = listOf(
                entry(syncId = "a", loggedAt = base, updatedAt = base + 500),
                entry(syncId = "b", loggedAt = base + 1, updatedAt = base + 600),
            ),
        )

        val decoded = decoded(BackupCodec.encode(original))

        assertEquals("entry count must survive", 2, decoded.entries.size)
        decoded.entries.forEachIndexed { index, actual ->
            val expected = original.entries[index]
            assertEquals("syncId", expected.syncId, actual.syncId)
            assertEquals("loggedAt", expected.loggedAt, actual.loggedAt)
            assertEquals("dayKey", expected.dayKey, actual.dayKey)
            assertEquals("meal", expected.meal, actual.meal)
            assertEquals("foodName", expected.foodName, actual.foodName)
            assertEquals("canonicalName", expected.canonicalName, actual.canonicalName)
            assertEquals("portionText", expected.portionText, actual.portionText)
            assertEquals("grams", expected.grams, actual.grams)
            assertEquals("kcal", expected.kcal, actual.kcal)
            assertEquals("kcalLow", expected.kcalLow, actual.kcalLow)
            assertEquals("kcalHigh", expected.kcalHigh, actual.kcalHigh)
            assertEquals("confidence", expected.confidence, actual.confidence, 1e-9)
            assertEquals("isLocal", expected.isLocal, actual.isLocal)
            assertEquals("webGrounded", expected.webGrounded, actual.webGrounded)
            assertEquals("healthScore", expected.healthScore, actual.healthScore, 1e-9)
            assertEquals("source", expected.source, actual.source)
            assertEquals("rawTranscript", expected.rawTranscript, actual.rawTranscript)
            assertEquals("notes", expected.notes, actual.notes)
            assertEquals("updatedAt", expected.updatedAt, actual.updatedAt)
            assertEquals("deleted", expected.deleted, actual.deleted)
        }
    }

    @Test
    fun `nullable fields come back as null not empty or zero`() = runTest {
        val original = backup(
            entries = listOf(
                entry().copy(
                    canonicalName = null,
                    grams = null,
                    rawTranscript = null,
                    notes = null,
                ),
            ),
        )

        val actual = decoded(BackupCodec.encode(original)).entries.single()

        assertNull("canonicalName", actual.canonicalName)
        assertNull("grams", actual.grams)
        assertNull("rawTranscript", actual.rawTranscript)
        assertNull("notes", actual.notes)
    }

    @Test
    fun `tombstones are preserved by encode then decode`() = runTest {
        val original = backup(
            entries = listOf(
                entry(syncId = "live", deleted = false),
                entry(syncId = "gone", deleted = true),
            ),
        )

        val decoded = decoded(BackupCodec.encode(original))

        assertEquals(2, decoded.entries.size)
        val gone = decoded.entries.single { it.syncId == "gone" }
        assertTrue("a deleted entry must come back deleted", gone.deleted)
        val live = decoded.entries.single { it.syncId == "live" }
        assertEquals(false, live.deleted)
    }

    @Test
    fun `liveEntryCount excludes tombstones`() = runTest {
        val file = backup(
            entries = listOf(
                entry(syncId = "1"),
                entry(syncId = "2"),
                entry(syncId = "3", deleted = true),
            ),
        )

        assertEquals(2, file.liveEntryCount)
        // The count is derived, not encoded, so it must survive the trip too.
        assertEquals(2, decoded(BackupCodec.encode(file)).liveEntryCount)
    }

    @Test
    fun `settings dailyTargetKcal round-trips`() = runTest {
        val original = backup(settings = AppSettings(dailyTargetKcal = 2400))

        val decoded = decoded(BackupCodec.encode(original))

        assertEquals(2400, decoded.settings.dailyTargetKcal)
    }

    @Test
    fun `createdAt and appVersion round-trip`() = runTest {
        val original = backup(createdAt = base + 123, appVersion = "9.9.9")

        val decoded = decoded(BackupCodec.encode(original))

        assertEquals(base + 123, decoded.createdAt)
        assertEquals("9.9.9", decoded.appVersion)
    }

    // ------------------------------------------------------------------- failures

    @Test
    fun `decoding a non-JSON string is NotABackup and does not throw`() = runTest {
        assertEquals(BackupError.NotABackup, failure("this is not json at all"))
    }

    @Test
    fun `decoding an empty string is NotABackup`() = runTest {
        assertEquals(BackupError.NotABackup, failure(""))
    }

    @Test
    fun `decoding valid JSON that is not a backup is NotABackup`() = runTest {
        assertEquals(BackupError.NotABackup, failure("""{"hello":"world"}"""))
    }

    @Test
    fun `decoding a backup with a higher version reports UnsupportedVersion`() = runTest {
        val raw = BackupCodec.encode(backup()).replace("\"version\":1", "\"version\":99")

        assertEquals(BackupError.UnsupportedVersion(99), failure(raw))
    }

    @Test
    fun `decoding a backup with an empty entries array is Empty`() = runTest {
        assertEquals(BackupError.Empty, failure(BackupCodec.encode(backup(entries = emptyList()))))
    }

    @Test
    fun `a missing entries array is NotABackup`() = runTest {
        val raw = """{"format":"kaloriku-backup","version":1,"settings":{"dailyTargetKcal":2000}}"""

        assertEquals(BackupError.NotABackup, failure(raw))
    }

    // ----------------------------------------------------------- filtered entries

    @Test
    fun `an entry with a blank syncId is dropped`() = runTest {
        val original = backup(
            entries = listOf(
                entry(syncId = "kept"),
                entry(syncId = "   "),
            ),
        )

        val decoded = decoded(BackupCodec.encode(original))

        assertEquals(listOf("kept"), decoded.entries.map { it.syncId })
    }

    @Test
    fun `a backup whose only entry has a blank syncId is Empty`() = runTest {
        val original = backup(entries = listOf(entry(syncId = "")))

        assertEquals(BackupError.Empty, failure(BackupCodec.encode(original)))
    }

    @Test
    fun `good entries survive alongside unusable ones`() = runTest {
        // One good row, one blank-syncId row, and one non-object element in the array.
        val raw = """
            {
              "format": "kaloriku-backup",
              "version": 1,
              "entries": [
                {"syncId":"good","loggedAt":$base,"dayKey":"2026-09-25","foodName":"Nasi","kcal":300},
                {"syncId":"","loggedAt":$base,"foodName":"Buang","kcal":1},
                7
              ]
            }
        """.trimIndent()

        val decoded = decoded(raw)

        assertEquals(1, decoded.entries.size)
        assertEquals("good", decoded.entries.single().syncId)
    }

    // ------------------------------------------------------------------- settings

    @Test
    fun `an out-of-range dailyTargetKcal is clamped into 500 to 6000`() = runTest {
        val tooHigh = decoded(BackupCodec.encode(backup(settings = AppSettings(999_999))))
        assertEquals(6000, tooHigh.settings.dailyTargetKcal)

        val tooLow = decoded(BackupCodec.encode(backup(settings = AppSettings(1))))
        assertEquals(500, tooLow.settings.dailyTargetKcal)
    }

    @Test
    fun `a missing settings object falls back to the default target`() = runTest {
        val raw = """
            {
              "format": "kaloriku-backup",
              "version": 1,
              "entries": [
                {"syncId":"x","loggedAt":$base,"dayKey":"2026-09-25","foodName":"Nasi","kcal":300}
              ]
            }
        """.trimIndent()

        assertEquals(AppSettings.DEFAULT_TARGET, decoded(raw).settings.dailyTargetKcal)
    }

    // ------------------------------------------------------------------- stability

    @Test
    fun `decode then encode is stable for the same input`() = runTest {
        val original = backup(
            entries = listOf(entry(syncId = "a"), entry(syncId = "b").copy(deleted = true)),
            settings = AppSettings(2300),
        )

        val decoded = decoded(BackupCodec.encode(original))
        // Compare decoded values, not raw strings: JSON key order is not guaranteed.
        val reDecoded = decoded(BackupCodec.encode(decoded))

        assertEquals(decoded.entries, reDecoded.entries)
        assertEquals(decoded.settings, reDecoded.settings)
        assertEquals(decoded.createdAt, reDecoded.createdAt)
        assertEquals(decoded.appVersion, reDecoded.appVersion)
    }

    @Test
    fun `an entry with a blank foodName is dropped`() = runTest {
        val raw = """
            {
              "format": "kaloriku-backup",
              "version": 1,
              "entries": [
                {"syncId":"good","loggedAt":$base,"foodName":"Nasi","kcal":300},
                {"syncId":"no-name","loggedAt":$base,"foodName":"   ","kcal":5}
              ]
            }
        """.trimIndent()

        assertEquals(listOf("good"), decoded(raw).entries.map { it.syncId })
    }
}
