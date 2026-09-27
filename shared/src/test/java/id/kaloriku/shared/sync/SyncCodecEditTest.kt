package id.kaloriku.shared.sync

import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edit/delete bookkeeping must survive the phone<->watch wire, otherwise the two
 * devices cannot agree on which revision is newest.
 */
class SyncCodecEditTest {

    private fun entry(
        syncId: String = "sync-1",
        updatedAt: Long = 1790300000000L,
        deleted: Boolean = false,
        notes: String? = null,
    ) = FoodEntry(
        syncId = syncId,
        loggedAt = 1790300000000L,
        dayKey = "2026-09-25",
        meal = MealType.MAKAN_SIANG,
        foodName = "Nasi Goreng",
        kcal = 380,
        source = LogSource.VOICE_PHONE,
        updatedAt = updatedAt,
        deleted = deleted,
        notes = notes,
    )

    private fun roundTrip(entry: FoodEntry): FoodEntry =
        (SyncCodec.decode(
            SyncMessage.PATH_NEW_ENTRIES,
            SyncCodec.encode(SyncMessage.NewEntries(listOf(entry), LogSource.VOICE_PHONE)),
        ) as SyncMessage.NewEntries).entries.single()

    @Test
    fun `revision and deletion flag survive the round trip`() {
        val decoded = roundTrip(entry(updatedAt = 1790300999000L, deleted = true))
        assertEquals(1790300999000L, decoded.updatedAt)
        assertTrue(decoded.deleted)
    }

    @Test
    fun `notes survive the round trip`() {
        assertEquals("porsi kecil", roundTrip(entry(notes = "porsi kecil")).notes)
    }

    @Test
    fun `a live entry decodes as live`() {
        val decoded = roundTrip(entry(deleted = false))
        assertFalse(decoded.deleted)
        assertFalse(decoded.isEdited)
    }

    @Test
    fun `an entry from an older peer defaults to its own first revision`() {
        // A pre-edit build sends neither updatedAt nor deleted. Such an entry must be
        // read as its own first revision so a local edit can still win the merge.
        val payload = """
            {"kind":"new","source":"VOICE_WATCH","entries":[
              {"syncId":"legacy","loggedAt":1790300000000,"foodName":"Tempe","kcal":90}
            ]}
        """.trimIndent()
        val decoded = (SyncCodec.decode(
            SyncMessage.PATH_NEW_ENTRIES,
            payload.toByteArray(),
        ) as SyncMessage.NewEntries).entries.single()

        assertEquals(1790300000000L, decoded.updatedAt)
        assertFalse(decoded.deleted)
    }
}
