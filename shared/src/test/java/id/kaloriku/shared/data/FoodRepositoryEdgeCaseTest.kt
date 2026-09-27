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
 * Edge cases around edit/delete bookkeeping that the main edit suite does not pin
 * down: un-acked edits, double deletes, unknown acks, extreme timestamps and the
 * exact tombstone purge boundary.
 */
class FoodRepositoryEdgeCaseTest {

    private val day = "2026-09-25"
    private val base = 1790300000000L

    private fun entry(
        syncId: String = "sync-1",
        kcal: Int = 300,
        name: String = "Nasi Goreng",
        loggedAt: Long = base,
        updatedAt: Long = loggedAt,
        deleted: Boolean = false,
    ) = FoodEntry(
        syncId = syncId,
        loggedAt = loggedAt,
        dayKey = day,
        meal = MealType.MAKAN_SIANG,
        foodName = name,
        kcal = kcal,
        source = LogSource.VOICE_PHONE,
        updatedAt = updatedAt,
        deleted = deleted,
    )

    private fun repo(dao: FakeFoodEntryDao) = FoodRepository(dao, FakeSettingsSource())

    // ------------------------------------------------------- edit while un-acked

    @Test
    fun `editing an entry that is still pending keeps it pending with the new values`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        // Never acked: still pending.
        val id = repo.saveEntry(entry())
        assertEquals(1, repo.pendingSync().size)

        repo.updateEntry(id, nowMillis = base + 1) { it.copy(kcal = 450) }

        val pending = repo.pendingSync().single()
        assertEquals(450, pending.kcal)
        assertEquals(base + 1, pending.updatedAt)
        assertEquals("still exactly one queued revision", 1, repo.pendingSync().size)
        assertEquals("no duplicate row", 1, dao.all().size)
    }

    // ---------------------------------------------- update on deleted entry

    @Test
    fun `updateEntry on an already-deleted entry returns false and does not resurrect`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base + 5_000)

        val ok = repo.updateEntry(id, nowMillis = base + 9_000) { it.copy(kcal = 999, foodName = "Zombie") }

        assertFalse(ok)
        val tombstone = repo.pendingSync().single()
        assertTrue(tombstone.deleted)
        assertEquals("tombstone revision must not move", base + 5_000, tombstone.updatedAt)
        assertTrue(repo.recent().isEmpty())
        assertEquals(0, repo.count())
    }

    // ------------------------------------- tombstone arrives for newer local

    @Test
    fun `a tombstone arriving for a locally newer revision is ignored`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))
        // Local edit newer than the incoming tombstone.
        repo.updateEntry(id, nowMillis = base + 10_000) { it.copy(kcal = 800) }

        repo.mergeSynced(listOf(entry(kcal = 300).copy(updatedAt = base + 9_000, deleted = true)))

        assertEquals("newer live edit must survive a stale tombstone", 800, repo.recent().single().kcal)
        assertEquals(1, repo.count())
    }

    @Test
    fun `a tombstone arriving with a newer revision deletes a live local entry`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))
        repo.updateEntry(id, nowMillis = base + 10_000) { it.copy(kcal = 800) }

        repo.mergeSynced(listOf(entry(kcal = 300).copy(updatedAt = base + 10_001, deleted = true)))

        assertTrue(repo.recent().isEmpty())
        assertEquals(0, repo.count())
    }

    // ---------------------------------------------------- delete twice

    @Test
    fun `deleting twice is idempotent and keeps the first tombstone revision`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))

        repo.delete(id, nowMillis = base + 1_000)
        repo.delete(id, nowMillis = base + 2_000)

        assertEquals("still one row", 1, dao.all().size)
        val tombstone = dao.findBySyncId("sync-1")!!
        assertTrue(tombstone.deleted)
        assertEquals("second delete must be a no-op", base + 1_000, tombstone.updatedAt)

        // And deleting an id that never existed is a silent no-op.
        repo.delete(404L, nowMillis = base + 3_000)
        assertEquals(1, dao.all().size)
    }

    // ------------------------------------------------- unknown ack

    @Test
    fun `markSynced with an unknown syncId is a no-op and does not purge live rows`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry())

        repo.markSynced(listOf("does-not-exist"), nowMillis = base + 100)

        assertEquals(1, dao.all().size)
        assertEquals(1, repo.pendingSync().size)
        assertFalse(dao.findBySyncId("sync-1")!!.pendingSync.not())
    }

    @Test
    fun `markSynced with an empty list does nothing`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry())

        repo.markSynced(emptyList(), nowMillis = base + 100)

        assertEquals(1, repo.pendingSync().size)
    }

    @Test
    fun `markSynced with an unknown id does purge an aged tombstone it does not match`() = runTest {
        // Documents the (intentional) global sweep: markSynced always runs the purge
        // with the caller's clock, regardless of which ids were acked.
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base + 1_000)
        repo.markSynced(listOf("sync-1"), nowMillis = base + 2_000)

        // Far future: the tombstone is now older than the grace window.
        repo.markSynced(listOf("unrelated"), nowMillis = base + FoodRepository.TOMBSTONE_GRACE_MILLIS + 5_000)

        assertEquals(0, dao.all().size)
    }

    // ------------------------------------------------ updatedAt == loggedAt

    @Test
    fun `a never-edited entry loses a merge against a peer edit`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        // updatedAt defaults to loggedAt.
        repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))

        val accepted = repo.mergeSynced(listOf(entry(kcal = 750).copy(updatedAt = base + 1)))

        assertEquals(listOf("sync-1"), accepted)
        assertEquals(750, repo.recent().single().kcal)
    }

    @Test
    fun `a peer edit at exactly loggedAt does not win the tie`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(kcal = 300)) // updatedAt == loggedAt == base
        repo.markSynced(listOf("sync-1"))

        repo.mergeSynced(listOf(entry(kcal = 999).copy(updatedAt = base)))

        assertEquals("equal revision keeps local", 300, repo.recent().single().kcal)
    }

    // ------------------------------------------------ extreme nowMillis

    @Test
    fun `negative nowMillis is accepted on edit and stored as the revision`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(loggedAt = 0, updatedAt = 0))

        val ok = repo.updateEntry(id, nowMillis = -1_000) { it.copy(kcal = 123) }

        assertTrue(ok)
        assertEquals(-1_000, repo.recent().single().updatedAt)
        // A negative revision is older than a positive one, so any later real edit wins.
        repo.mergeSynced(listOf(entry(kcal = 456, loggedAt = 0, updatedAt = 0)))
        assertEquals("zero is newer than -1000", 456, repo.recent().single().kcal)
    }

    @Test
    fun `Long MAX_VALUE and MIN_VALUE do not overflow the merge comparison`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(updatedAt = Long.MAX_VALUE))
        repo.markSynced(listOf("sync-1"))

        repo.mergeSynced(listOf(entry(kcal = 1, updatedAt = Long.MIN_VALUE)))
        assertEquals("MAX_VALUE local keeps its value", 300, repo.recent().single().kcal)

        // A tombstone at MAX_VALUE must still delete.
        repo.mergeSynced(listOf(entry(updatedAt = Long.MAX_VALUE, deleted = true).copy(kcal = 300)))
        // Ties go to the deletion, so the entry is removed rather than resurrected.
        assertEquals(0, repo.count())
    }

    @Test
    fun `markSynced with Long MIN_VALUE nowMillis keeps tombstones safe`() = runTest {
        // `nowMillis - TOMBSTONE_GRACE_MILLIS` with MIN_VALUE would wrap to a large
        // positive cutoff and purge every acked tombstone. The cutoff is clamped, so a
        // nonsensical clock can never drop a tombstone that is still protecting a delete.
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base)
        repo.markSynced(listOf("sync-1"), nowMillis = base) // clear pending

        repo.markSynced(listOf("sync-1"), nowMillis = Long.MIN_VALUE)

        assertEquals("the tombstone must survive a wrapped cutoff", 1, dao.all().size)
    }

    @Test
    fun `markSynced with Long MAX_VALUE nowMillis purges every acked tombstone`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base)
        repo.markSynced(listOf("sync-1"), nowMillis = base)

        repo.markSynced(listOf("sync-1"), nowMillis = Long.MAX_VALUE)

        assertEquals(0, dao.all().size)
    }

    // --------------------------------------------- tombstone purge boundary

    @Test
    fun `a tombstone exactly at the grace boundary is retained`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base)
        repo.markSynced(listOf("sync-1"), nowMillis = base) // clear pending

        // cutoff = now - GRACE; purge uses `updatedAt < cutoff`, so exactly at the
        // boundary is NOT purged.
        val now = base + FoodRepository.TOMBSTONE_GRACE_MILLIS
        repo.markSynced(listOf("sync-1"), nowMillis = now)

        assertEquals("boundary tombstone must be kept", 1, dao.all().size)
    }

    @Test
    fun `a tombstone one millisecond past the grace boundary is purged`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base)
        repo.markSynced(listOf("sync-1"), nowMillis = base)

        val now = base + FoodRepository.TOMBSTONE_GRACE_MILLIS + 1
        repo.markSynced(listOf("sync-1"), nowMillis = now)

        assertEquals("aged tombstone must be reclaimed", 0, dao.all().size)
    }

    @Test
    fun `a pending tombstone is never purged even when very old`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry())
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base)

        // Never acked: pendingSync stays true.
        repo.markSynced(listOf("unrelated"), nowMillis = base + FoodRepository.TOMBSTONE_GRACE_MILLIS * 10)

        assertEquals(1, dao.all().size)
        assertTrue(repo.pendingSync().single().deleted)
    }

    // ------------------------------------------------------- find / observe

    @Test
    fun `find returns null for a missing id and a deleted id`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        assertNull(repo.find(1L))

        val id = repo.saveEntry(entry())
        assertNull(repo.find(id + 999))
        repo.delete(id, nowMillis = base + 1)
        assertNull(repo.find(id))
    }

    @Test
    fun `observe flows hide tombstones but pendingSync still surfaces them`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))
        repo.delete(id, nowMillis = base + 1)

        assertTrue(repo.observeRecent().first().isEmpty())
        assertTrue(repo.observeDay(day).first().isEmpty())
        assertEquals(0, repo.observeDayTotal(day).first())
        assertEquals(1, repo.pendingSync().size)
    }

    // ------------------------------------------------- merge unknown tombstone

    @Test
    fun `a tombstone for an unknown syncId is inserted as deleted and invisible`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)

        val accepted = repo.mergeSynced(listOf(entry(syncId = "ghost").copy(deleted = true, updatedAt = base + 1)))

        assertEquals(listOf("ghost"), accepted)
        assertEquals(1, dao.all().size)
        assertTrue(dao.findBySyncId("ghost")!!.deleted)
        assertTrue("an unknown tombstone must never be visible", repo.recent().isEmpty())
        assertEquals(0, repo.count())
    }

    @Test
    fun `merge ignores entries with a blank syncId`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)

        val accepted = repo.mergeSynced(listOf(entry(syncId = "")))

        assertTrue(accepted.isEmpty())
        assertEquals(0, dao.all().size)
    }

    @Test
    fun `merge returns accepted ids once even for repeated syncIds in one batch`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)

        val accepted = repo.mergeSynced(
            listOf(entry(syncId = "dup"), entry(syncId = "dup", kcal = 500, updatedAt = base + 1)),
        )

        assertEquals(listOf("dup"), accepted)
        assertEquals(1, dao.all().size)
        assertEquals("the newer copy of the duplicate wins", 500, repo.recent().single().kcal)
    }
}
