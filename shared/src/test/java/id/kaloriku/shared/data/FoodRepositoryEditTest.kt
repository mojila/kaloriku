package id.kaloriku.shared.data

import id.kaloriku.shared.FakeFoodEntryDao
import id.kaloriku.shared.FakeSettingsSource
import id.kaloriku.shared.domain.FoodEntry
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
 * Covers the edit/delete contract that the phone and watch UIs rely on: edits bump
 * the revision, deletes become tombstones, and neither can be undone by a stale copy
 * arriving from the peer.
 */
class FoodRepositoryEditTest {

    private val day = "2026-09-25"
    private val base = 1790300000000L

    private fun entry(
        syncId: String = "sync-1",
        kcal: Int = 300,
        name: String = "Nasi Goreng",
        loggedAt: Long = base,
    ) = FoodEntry(
        syncId = syncId,
        loggedAt = loggedAt,
        dayKey = day,
        meal = MealType.MAKAN_SIANG,
        foodName = name,
        kcal = kcal,
        source = LogSource.VOICE_PHONE,
    )

    private fun repo(dao: FakeFoodEntryDao) = FoodRepository(dao, FakeSettingsSource())

    // ------------------------------------------------------------------ edits

    @Test
    fun `updateEntry applies the change and bumps the revision`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())

        val ok = repo.updateEntry(id, nowMillis = base + 5_000) {
            it.copy(foodName = "Nasi Goreng Spesial", kcal = 450, source = LogSource.MANUAL)
        }

        assertTrue(ok)
        val stored = repo.recent().single()
        assertEquals("Nasi Goreng Spesial", stored.foodName)
        assertEquals(450, stored.kcal)
        assertEquals(LogSource.MANUAL, stored.source)
        assertEquals(base + 5_000, stored.updatedAt)
        assertTrue("a changed entry should read as edited", stored.isEdited)
    }

    @Test
    fun `updateEntry keeps the identity fields stable`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(syncId = "stable-id", loggedAt = base))

        repo.updateEntry(id, nowMillis = base + 1) {
            // Try to move the identity; the repository must refuse.
            it.copy(id = 999, syncId = "hijacked", loggedAt = base + 10_000, kcal = 400)
        }

        val stored = repo.recent().single()
        assertEquals(id, stored.id)
        assertEquals("stable-id", stored.syncId)
        assertEquals(base, stored.loggedAt)
    }

    @Test
    fun `an edit is queued for the peer`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        assertEquals("delivered entries are not pending", 0, repo.pendingSync().size)

        repo.updateEntry(id, nowMillis = base + 1) { it.copy(kcal = 500) }

        val pending = repo.pendingSync().single()
        assertEquals("the new revision must be re-queued", "sync-1", pending.syncId)
        assertEquals(500, pending.kcal)
    }

    @Test
    fun `updating a missing entry reports failure instead of throwing`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        assertFalse(repo.updateEntry(404L) { it.copy(kcal = 1) })
    }

    @Test
    fun `find returns the live entry and hides a deleted one`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))

        assertEquals("sync-1", repo.find(id)?.syncId)

        repo.delete(id, nowMillis = base + 100)
        assertNull("a deleted entry must not be editable", repo.find(id))
        assertNull(repo.find(404L))
    }

    @Test
    fun `an entry cannot be edited after it is deleted`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))

        repo.delete(id, nowMillis = base + 100)

        assertFalse("editing a tombstone must not resurrect it", repo.updateEntry(id) { it.copy(kcal = 999) })
        assertTrue(repo.recent().isEmpty())
    }

    // ---------------------------------------------------------------- deletion

    @Test
    fun `delete hides the entry from every read and from the day total`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))

        repo.delete(id, nowMillis = base + 100)

        assertTrue("day read must hide tombstones", repo.dayEntries(day).isEmpty())
        assertTrue("recent read must hide tombstones", repo.recent().isEmpty())
        assertEquals("day total must drop the entry", 0, repo.observeDayTotal(day).first())
        assertEquals("count must drop the entry", 0, repo.count())
        assertEquals(0, repo.summary(day).totalKcal)
    }

    @Test
    fun `deleting an entry the peer never saw is still tombstoned`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())

        // Never acked, so at first glance no peer copy exists. It is still tombstoned:
        // an un-acked edit can sit alongside an older copy the peer already stored, and
        // dropping the row would let that copy resurrect the entry.
        repo.delete(id, nowMillis = base + 100)

        val tombstone = repo.pendingSync().single()
        assertTrue(tombstone.deleted)
        assertEquals(1, dao.all().size)
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `deleting after an edit still propagates the deletion`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        // Delivered once, then edited: the peer holds the original, we hold a newer
        // revision, and the entry is pending again.
        repo.markSynced(listOf("sync-1"))
        repo.updateEntry(id, nowMillis = base + 10) { it.copy(kcal = 500) }

        repo.delete(id, nowMillis = base + 100)

        val tombstone = repo.pendingSync().single()
        assertEquals("sync-1", tombstone.syncId)
        assertTrue(tombstone.deleted)

        // The watch's stale original must not bring it back.
        repo.mergeSynced(listOf(entry(kcal = 300).copy(updatedAt = base)))
        assertTrue(repo.recent().isEmpty())
    }

    @Test
    fun `deleting a delivered entry leaves a queued tombstone`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))

        repo.delete(id, nowMillis = base + 100)

        val tombstone = repo.pendingSync().single()
        assertEquals("sync-1", tombstone.syncId)
        assertTrue("the deletion itself must travel", tombstone.deleted)
        assertEquals(base + 100, tombstone.updatedAt)
    }

    // ------------------------------------------------------- merge / convergence

    @Test
    fun `a newer peer revision overwrites the local one`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))

        val accepted = repo.mergeSynced(
            listOf(entry(kcal = 700).copy(updatedAt = base + 60_000)),
        )

        assertEquals(listOf("sync-1"), accepted)
        assertEquals(700, repo.recent().single().kcal)
        assertEquals(
            "an applied remote change must not be re-queued",
            0,
            repo.pendingSync().size,
        )
    }

    @Test
    fun `a stale peer revision is ignored`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.updateEntry(id, nowMillis = base + 60_000) { it.copy(kcal = 900) }

        repo.mergeSynced(listOf(entry(kcal = 100).copy(updatedAt = base + 1)))

        assertEquals("the newer local edit must survive", 900, repo.recent().single().kcal)
    }

    @Test
    fun `a tombstone from the peer deletes the local copy`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))

        repo.mergeSynced(
            listOf(entry().copy(deleted = true, updatedAt = base + 60_000)),
        )

        assertTrue(repo.recent().isEmpty())
        assertEquals(0, repo.count())
    }

    @Test
    fun `a stale copy cannot resurrect an entry deleted here`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base + 60_000)

        // The peer re-sends its old, still-live copy (it has not processed the delete).
        repo.mergeSynced(listOf(entry(kcal = 300).copy(updatedAt = base)))

        assertTrue(
            "the deletion must win over a stale live copy",
            repo.recent().isEmpty(),
        )
    }

    @Test
    fun `deleted entries are still pushed so the peer learns about them`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))

        repo.delete(id, nowMillis = base + 100)

        val pushed = repo.pendingSync()
        assertEquals(1, pushed.size)
        assertTrue(pushed.single().deleted)
    }

    // ------------------------------------------------------------ tombstone purge

    @Test
    fun `a delivered tombstone survives the grace period and is then dropped`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base + 100)

        // Ack arrives immediately: the tombstone must still be retained, so a stale
        // copy queued on the peer cannot bring the entry back.
        repo.markSynced(listOf("sync-1"), nowMillis = base + 200)
        assertEquals("tombstone must outlive the immediate ack", 1, dao.all().size)

        repo.mergeSynced(listOf(entry().copy(updatedAt = base)))
        assertTrue(repo.recent().isEmpty())

        // Much later, it is safe to reclaim the row.
        repo.markSynced(listOf("sync-1"), nowMillis = base + FoodRepository.TOMBSTONE_GRACE_MILLIS + 1_000)
        assertEquals("an aged tombstone is reclaimed", 0, dao.all().size)
    }

    @Test
    fun `an ack for unrelated entries does not drop a fresh tombstone`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base + 100)

        // An ack arrives for a different entry: the tombstone must stay put.
        repo.markSynced(listOf("some-other-sync-id"), nowMillis = base + 200)

        assertEquals(1, dao.all().size)
        assertTrue(repo.pendingSync().single().deleted)
    }
}
