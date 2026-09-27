package id.kaloriku.shared.data

import id.kaloriku.shared.FakeFoodEntryDao
import id.kaloriku.shared.FakeSettingsSource
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the "edit an entry's date/time" (reschedule) contract: moving an entry to
 * another day must move the row between day lists and totals, keep its identity,
 * always read as edited — including into the future — and travel to the peer like
 * any other revision, where a stale copy cannot undo it.
 *
 * The feature is driven through [FoodRepository.updateEntry]'s `newLoggedAt`
 * parameter, which the repository overwrites onto `loggedAt`/`dayKey` after the
 * caller's `transform` runs.
 */
class FoodRepositoryRescheduleTest {

    private val oldDay = "2026-09-25"
    private val newDay = "2026-09-20"

    // A fixed "now" so updatedAt assertions are deterministic. 2026-09-26 12:00 WIB.
    private val now = JakartaTime.atTime("2026-09-26", 12, 0)
    private val oldLoggedAt = JakartaTime.atTime(oldDay, 8, 30)
    private val newLoggedAt = JakartaTime.atTime(newDay, 19, 15)

    private fun entry(
        syncId: String = "sync-1",
        kcal: Int = 300,
        name: String = "Nasi Goreng",
        loggedAt: Long = oldLoggedAt,
        dayKey: String = JakartaTime.dayKey(loggedAt),
    ) = FoodEntry(
        syncId = syncId,
        loggedAt = loggedAt,
        dayKey = dayKey,
        meal = MealType.MAKAN_SIANG,
        foodName = name,
        kcal = kcal,
        source = LogSource.VOICE_PHONE,
    )

    private fun repo(dao: FakeFoodEntryDao) = FoodRepository(dao, FakeSettingsSource())

    // ------------------------------------------------------------ move between days

    @Test
    fun `a reschedule moves the entry to the new day list`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 350))
        assertEquals("seeded on the old day", 1, repo.dayEntries(oldDay).size)

        val ok = repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it }

        assertTrue(ok)
        assertTrue("entry must leave the old day", repo.dayEntries(oldDay).isEmpty())
        val moved = repo.dayEntries(newDay).single()
        assertEquals(id, moved.id)
        assertEquals(newLoggedAt, moved.loggedAt)
        assertEquals(newDay, moved.dayKey)
        assertEquals("calories ride along unchanged", 350, moved.kcal)
    }

    @Test
    fun `a reschedule moves the calories from the old day total to the new one`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 420))
        assertEquals(420, repo.observeDayTotal(oldDay).first())
        assertEquals(0, repo.observeDayTotal(newDay).first())

        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it }

        assertEquals("old day must drop the calories", 0, repo.observeDayTotal(oldDay).first())
        assertEquals("new day must gain them", 420, repo.observeDayTotal(newDay).first())
        assertEquals(0, repo.summary(oldDay).totalKcal)
        assertEquals(420, repo.summary(newDay).totalKcal)
    }

    @Test
    fun `the day key is recomputed even when the transform lies about it`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())

        // The transform hands back a stale dayKey; the repository must author the
        // canonical pair (loggedAt, dayKey) itself, or the row would be invisible.
        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) {
            it.copy(loggedAt = newLoggedAt, dayKey = "1999-01-01")
        }

        val stored = repo.dayEntries(newDay).single()
        assertEquals(newDay, stored.dayKey)
        assertEquals(newLoggedAt, stored.loggedAt)
        assertTrue("the bogus day must stay empty", repo.dayEntries("1999-01-01").isEmpty())
    }

    // ------------------------------------------------------------------- sync queue

    @Test
    fun `a reschedule is queued for the peer`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))
        assertEquals("delivered entries are not pending", 0, repo.pendingSync().size)

        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it.copy(kcal = 500) }

        val pending = repo.pendingSync().single()
        assertEquals("sync-1", pending.syncId)
        assertTrue("the reschedule must be marked for delivery", dao.all().single().pendingSync)
        assertEquals(newLoggedAt, pending.loggedAt)
        assertEquals(newDay, pending.dayKey)
        assertEquals(500, pending.kcal)
    }

    // -------------------------------------------------------------------- identity

    @Test
    fun `a reschedule keeps id and syncId even if the transform hijacks them`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(syncId = "stable-id"))

        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) {
            it.copy(id = 999, syncId = "hijacked")
        }

        val stored = repo.dayEntries(newDay).single()
        assertEquals(id, stored.id)
        assertEquals("stable-id", stored.syncId)
        val row = dao.all().single()
        assertEquals(id, row.id)
        assertEquals("stable-id", row.syncId)
    }

    // ------------------------------------------------------------------ updatedAt

    @Test
    fun `a reschedule bumps updatedAt and reads as edited`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())

        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it.copy(kcal = 280) }

        val stored = repo.dayEntries(newDay).single()
        assertEquals("the revision must advance", now, stored.updatedAt)
        assertTrue("a reschedule must read as edited", stored.isEdited)
    }

    @Test
    fun `a reschedule into the future still reads as edited`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        val future = JakartaTime.atTime("2027-03-10", 6, 45)

        // nowMillis is deliberately OLDER than the future date we move to. The revision
        // must still land strictly after loggedAt, or the row would silently stop
        // reporting as edited (isEdited is `updatedAt > loggedAt`).
        repo.updateEntry(id, nowMillis = now, newLoggedAt = future) { it }

        val stored = repo.dayEntries("2027-03-10").single()
        assertEquals("loggedAt moves to the future", future, stored.loggedAt)
        assertEquals("dayKey follows the new date", "2027-03-10", stored.dayKey)
        assertTrue(
            "updatedAt must never predate loggedAt (was ${stored.updatedAt} vs ${stored.loggedAt})",
            stored.updatedAt >= stored.loggedAt,
        )
        assertTrue(
            "a future-dated reschedule must still read as edited (updatedAt=${stored.updatedAt}, loggedAt=${stored.loggedAt})",
            stored.isEdited,
        )
    }

    @Test
    fun `a reschedule into the past keeps the caller's newer revision`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(loggedAt = JakartaTime.atTime(oldDay, 9, 0)))
        val past = JakartaTime.atTime("2026-09-01", 7, 0)

        repo.updateEntry(id, nowMillis = now, newLoggedAt = past) { it }

        val stored = repo.dayEntries("2026-09-01").single()
        assertEquals(past, stored.loggedAt)
        assertEquals("a past move must not lower the revision", now, stored.updatedAt)
        assertTrue(stored.isEdited)
    }

    // ------------------------------------------------------- ordinary edit (no move)

    @Test
    fun `an ordinary edit leaves loggedAt and dayKey untouched`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))

        val ok = repo.updateEntry(id, nowMillis = now, newLoggedAt = null) {
            it.copy(foodName = "Nasi Goreng Spesial", kcal = 450)
        }

        assertTrue(ok)
        val stored = repo.recent().single()
        assertEquals("loggedAt is the row identity and must not move", oldLoggedAt, stored.loggedAt)
        assertEquals("dayKey must not be recomputed on an ordinary edit", oldDay, stored.dayKey)
        assertEquals(450, stored.kcal)
        assertEquals(now, stored.updatedAt)
        assertTrue(stored.isEdited)
    }

    // ------------------------------------------------------------------ tombstones

    @Test
    fun `a deleted entry cannot be rescheduled and stays deleted`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = now)

        assertFalse(
            "rescheduling a tombstone must not resurrect it",
            repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it.copy(kcal = 999) },
        )

        assertTrue("the entry must stay hidden", repo.recent().isEmpty())
        assertTrue(repo.dayEntries(oldDay).isEmpty())
        assertTrue(repo.dayEntries(newDay).isEmpty())
        assertTrue("the tombstone must survive", dao.all().single().deleted)
        assertEquals("no live revision may be queued from a tombstone", 1, repo.pendingSync().size)
        assertTrue(repo.pendingSync().single().deleted)
    }

    @Test
    fun `rescheduling a missing id reports failure`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        assertFalse(repo.updateEntry(404L, nowMillis = now, newLoggedAt = newLoggedAt) { it })
    }

    // ------------------------------------------------------------------ sync travel

    @Test
    fun `a reschedule round-trips to the peer through mergeSynced`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))

        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it.copy(kcal = 380) }

        // Ship the queued revision to a second device, exactly as SyncCoordinator does.
        val peerDao = FakeFoodEntryDao()
        val peer = repo(peerDao)
        peer.mergeSynced(repo.pendingSync())

        val onPeer = peer.dayEntries(newDay).single()
        assertEquals(380, onPeer.kcal)
        assertEquals(newLoggedAt, onPeer.loggedAt)
        assertEquals(newDay, onPeer.dayKey)
        assertEquals("syncId travels intact", "sync-1", onPeer.syncId)
        assertTrue("the peer's old day must be empty", peer.dayEntries(oldDay).isEmpty())
        assertEquals(380, peer.observeDayTotal(newDay).first())
        assertEquals(0, peer.observeDayTotal(oldDay).first())
    }

    @Test
    fun `a stale peer copy cannot undo a reschedule`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))

        val movedAt = now
        repo.updateEntry(id, nowMillis = movedAt, newLoggedAt = newLoggedAt) { it.copy(kcal = 380) }

        // The peer still holds the original day/calories at a lower revision.
        repo.mergeSynced(
            listOf(
                entry(kcal = 300).copy(updatedAt = oldLoggedAt),
            ),
        )

        val stored = repo.dayEntries(newDay).single()
        assertEquals("the reschedule must survive", 380, stored.kcal)
        assertEquals(newLoggedAt, stored.loggedAt)
        assertEquals(newDay, stored.dayKey)
        assertEquals("the newer local revision must win", movedAt, stored.updatedAt)
        assertTrue("the stale old day must stay empty", repo.dayEntries(oldDay).isEmpty())
    }

    // -------------------------------------------------------- multiple entries / day

    @Test
    fun `rescheduling one of two entries leaves the other on the old day`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val movedId = repo.saveEntry(entry(syncId = "moved", kcal = 300))
        repo.saveEntry(entry(syncId = "stays", kcal = 200, loggedAt = JakartaTime.atTime(oldDay, 13, 0)))

        repo.updateEntry(movedId, nowMillis = now, newLoggedAt = newLoggedAt) { it }

        assertEquals("only the untouched entry remains", listOf("stays"), repo.dayEntries(oldDay).map { it.syncId })
        assertEquals(listOf("moved"), repo.dayEntries(newDay).map { it.syncId })
        assertEquals(200, repo.observeDayTotal(oldDay).first())
        assertEquals(300, repo.observeDayTotal(newDay).first())
    }

    @Test
    fun `a reschedule to the same day keeps the entry in that day`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        val laterSameDay = JakartaTime.atTime(oldDay, 21, 0)

        repo.updateEntry(id, nowMillis = now, newLoggedAt = laterSameDay) { it }

        assertEquals(1, repo.dayEntries(oldDay).size)
        assertEquals(laterSameDay, repo.dayEntries(oldDay).single().loggedAt)
        assertEquals(oldDay, repo.dayEntries(oldDay).single().dayKey)
    }

    @Test
    fun `rescheduling does not duplicate the row`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        val newId = repo.saveEntry(newIdEntry())

        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it }
        repo.updateEntry(newId, nowMillis = now + 1, newLoggedAt = newLoggedAt) { it }

        assertEquals("two distinct entries stay two rows", 2, dao.all().size)
        assertEquals(2, repo.count())
    }

    private fun newIdEntry() = entry(syncId = "sync-2", kcal = 150, name = "Teh Manis")

    @Test
    fun `a reschedule survives a second sync read`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))

        repo.updateEntry(id, nowMillis = now, newLoggedAt = newLoggedAt) { it.copy(notes = "dipindah") }
        // Ack it, then read again: the move must persist and nothing may re-queue.
        repo.markSynced(repo.pendingSync().map { it.syncId }, nowMillis = now + 100)

        assertNull("nothing stays queued after the ack", repo.pendingSync().firstOrNull())
        val stored = repo.dayEntries(newDay).single()
        assertEquals(newLoggedAt, stored.loggedAt)
        assertEquals("dipindah", stored.notes)
    }
}
