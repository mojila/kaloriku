package id.kaloriku.shared.sync

import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCodecTest {

    private fun entry(id: Long, kcal: Int, local: Boolean = true, syncId: String = "sync-$id") = FoodEntry(
        id = id,
        syncId = syncId,
        loggedAt = 1790300000000L + id,
        dayKey = "2026-09-25",
        meal = MealType.MAKAN_SIANG,
        foodName = "Nasi Goreng $id",
        canonicalName = "nasi_goreng",
        portionText = "1 piring",
        grams = 250.0,
        kcal = kcal,
        kcalLow = kcal - 50,
        kcalHigh = kcal + 50,
        confidence = 0.8,
        isLocal = local,
        healthScore = 2.5,
        source = LogSource.VOICE_WATCH,
        rawTranscript = "nasi goreng",
    )

    @Test
    fun `recent update round-trips entries and totals`() {
        val message = SyncMessage.RecentUpdate(
            entries = listOf(entry(1, 380), entry(2, 90, local = false)),
            todayTotal = 470,
            target = 2000,
        )
        val decoded = SyncCodec.decode(SyncMessage.PATH_RECENT, SyncCodec.encode(message))
        assertTrue(decoded is SyncMessage.RecentUpdate)
        val recent = decoded as SyncMessage.RecentUpdate
        assertEquals(2, recent.entries.size)
        assertEquals(470, recent.todayTotal)
        assertEquals(2000, recent.target)
        val first = recent.entries.first()
        assertEquals("Nasi Goreng 1", first.foodName)
        assertEquals(MealType.MAKAN_SIANG, first.meal)
        assertEquals(LogSource.VOICE_WATCH, first.source)
        assertEquals(2.5, first.healthScore, 0.0001)
        assertEquals(330, first.kcalLow)
        assertEquals(430, first.kcalHigh)
    }

    @Test
    fun `sync identity survives the round trip`() {
        val original = entry(5, 250, syncId = "abc-123")
        val decoded = SyncCodec.decode(
            SyncMessage.PATH_NEW_ENTRIES,
            SyncCodec.encode(SyncMessage.NewEntries(listOf(original), LogSource.VOICE_WATCH)),
        ) as SyncMessage.NewEntries
        assertEquals("abc-123", decoded.entries.first().syncId)
    }

    @Test
    fun `new entries carry the watch source`() {
        val message = SyncMessage.NewEntries(listOf(entry(7, 250)), LogSource.VOICE_WATCH)
        val decoded = SyncCodec.decode(SyncMessage.PATH_NEW_ENTRIES, SyncCodec.encode(message))
        assertTrue(decoded is SyncMessage.NewEntries)
        assertEquals(1, (decoded as SyncMessage.NewEntries).entries.size)
        assertEquals(LogSource.VOICE_WATCH, decoded.source)
    }

    @Test
    fun `request sync round-trips`() {
        val decoded = SyncCodec.decode(SyncMessage.PATH_REQUEST, SyncCodec.encode(SyncMessage.RequestSync))
        assertEquals(SyncMessage.RequestSync, decoded)
    }

    @Test
    fun `ack carries the sync ids`() {
        val decoded = SyncCodec.decode(
            SyncMessage.PATH_ACK,
            SyncCodec.encode(SyncMessage.Ack(listOf("a", "b", "c"))),
        ) as SyncMessage.Ack
        assertEquals(listOf("a", "b", "c"), decoded.syncIds)
    }

    @Test
    fun `unknown path and malformed bytes are ignored`() {
        assertNull(SyncCodec.decode("/kaloriku/other", "{}".toByteArray()))
        assertNull(SyncCodec.decode(SyncMessage.PATH_RECENT, "not json".toByteArray()))
    }

    @Test
    fun `missing optional fields decode with safe defaults`() {
        val payload = """{"kind":"new","entries":[{"foodName":"Tempe","loggedAt":1790300000000}]}"""
        val decoded = SyncCodec.decode(SyncMessage.PATH_NEW_ENTRIES, payload.toByteArray())
        val e = (decoded as SyncMessage.NewEntries).entries.first()
        assertEquals("Tempe", e.foodName)
        assertEquals(0, e.kcal)
        assertEquals(MealType.CAMILAN, e.meal)
        assertEquals("2026-09-25", e.dayKey)
    }

    @Test
    fun `generated sync ids are unique and non blank`() {
        val ids = (1..500).map { FoodEntry.newSyncId() }
        assertTrue("all ids non-blank", ids.all { it.isNotBlank() })
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `sync ids stay unique even within one millisecond`() {
        val same = 1790300000000L
        val ids = (1..200).map { FoodEntry.newSyncId(same) }
        assertEquals(200, ids.toSet().size)
        assertNotEquals(ids[0], ids[1])
    }
}
