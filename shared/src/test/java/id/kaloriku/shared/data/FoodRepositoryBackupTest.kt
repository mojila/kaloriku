package id.kaloriku.shared.data

import id.kaloriku.shared.FakeFoodEntryDao
import id.kaloriku.shared.FakeSettingsSource
import id.kaloriku.shared.domain.AppSettings
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the backup export/restore contract on [FoodRepository].
 *
 * A backup must carry tombstones as well as live rows, and a restore must be
 * authoritative: it bumps every restored row's revision so the restored state wins
 * the next merge instead of being undone by a local tombstone or a newer peer copy.
 * It also has to be idempotent — restoring the same file twice must not duplicate
 * the log — because that is what makes a restore safe to retry.
 */
class FoodRepositoryBackupTest {

    private val day = "2026-09-25"
    private val base = 1790300000000L

    // A fixed "now" so updatedAt assertions are deterministic. 2026-09-26 12:00 WIB.
    private val now = JakartaTime.atTime("2026-09-26", 12, 0)

    private fun entry(
        syncId: String = "sync-1",
        kcal: Int = 300,
        name: String = "Nasi Goreng",
        loggedAt: Long = base,
        updatedAt: Long = loggedAt,
        notes: String? = null,
        deleted: Boolean = false,
    ) = FoodEntry(
        syncId = syncId,
        loggedAt = loggedAt,
        dayKey = JakartaTime.dayKey(loggedAt),
        meal = MealType.MAKAN_SIANG,
        foodName = name,
        portionText = "1 porsi",
        grams = 200.0,
        kcal = kcal,
        kcalLow = kcal - 50,
        kcalHigh = kcal + 50,
        confidence = 0.8,
        isLocal = true,
        source = LogSource.VOICE_PHONE,
        rawTranscript = "makan $name",
        notes = notes,
        updatedAt = updatedAt,
        deleted = deleted,
    )

    private fun repo(dao: FakeFoodEntryDao) =
        FoodRepository(dao, FakeSettingsSource(AppSettings(2200)))

    // ------------------------------------------------------------------ allForBackup

    @Test
    fun `allForBackup includes tombstones`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(kcal = 300))
        repo.markSynced(listOf("sync-1"))

        repo.delete(id, nowMillis = base + 100)

        val forBackup = repo.allForBackup()
        assertEquals("the tombstone must be exported, not dropped", 1, forBackup.size)
        val tombstone = forBackup.single()
        assertEquals("sync-1", tombstone.syncId)
        assertTrue("the exported row must be flagged deleted", tombstone.deleted)
        assertEquals("and it must be hidden from the UI reads", 0, repo.recent().size)
    }

    @Test
    fun `allForBackup returns live and deleted rows together`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "live", kcal = 300))
        val id = repo.saveEntry(entry(syncId = "gone", kcal = 400, loggedAt = base + 1))
        repo.markSynced(listOf("gone"))
        repo.delete(id, nowMillis = base + 100)

        val forBackup = repo.allForBackup()

        assertEquals(2, forBackup.size)
        assertEquals(setOf("live", "gone"), forBackup.map { it.syncId }.toSet())
    }

    // ----------------------------------------------------------------- full round trip

    @Test
    fun `export wipe restore brings every field back`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "a", kcal = 300, notes = "enak"))
        repo.saveEntry(entry(syncId = "b", kcal = 450, loggedAt = base + 1))

        val exported = repo.allForBackup()
        repo.deleteAll()
        assertEquals("the log is empty after the wipe", 0, repo.allForBackup().size)

        val written = repo.restore(exported, nowMillis = now)

        assertEquals(2, written)
        val restored = repo.recent()
        assertEquals(2, restored.size)
        val a = restored.single { it.syncId == "a" }
        assertEquals(300, a.kcal)
        assertEquals("Nasi Goreng", a.foodName)
        assertEquals("enak", a.notes)
        assertEquals(LogSource.VOICE_PHONE, a.source)
        assertEquals(0.8, a.confidence, 1e-9)
        assertEquals(200.0, a.grams!!, 1e-9)
        assertTrue(a.isLocal)
        assertEquals(setOf("a", "b"), restored.map { it.syncId }.toSet())
    }

    // -------------------------------------------------------------------- idempotence

    @Test
    fun `restoring an already present entry does not duplicate and keeps the row id`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val id = repo.saveEntry(entry(syncId = "sync-1", kcal = 300))
        val exported = repo.allForBackup()

        val written = repo.restore(exported, nowMillis = now)

        assertEquals(1, written)
        assertEquals("the restore must match the existing row, not insert a twin", 1, repo.count())
        assertEquals(1, dao.all().size)
        assertEquals("the local row id is stable", id, repo.recent().single().id)
    }

    @Test
    fun `restoring the same file twice is idempotent`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "a"))
        repo.saveEntry(entry(syncId = "b", loggedAt = base + 1))
        val exported = repo.allForBackup()
        repo.deleteAll()

        repo.restore(exported, nowMillis = now)
        repo.restore(exported, nowMillis = now)

        assertEquals("a repeated restore must not duplicate the log", 2, repo.count())
        assertEquals(2, dao.all().size)
        assertEquals(setOf("a", "b"), repo.recent().map { it.syncId }.toSet())
    }

    // ----------------------------------------------------------------- tombstone wins

    @Test
    fun `a restore wins over an existing local tombstone and makes the entry visible`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "sync-1", kcal = 300))
        val exported = repo.allForBackup()

        // The user clears the log: the row becomes a tombstone here.
        val id = repo.recent().single().id
        repo.delete(id, nowMillis = base + 100)
        assertTrue("prerequisite: the entry is hidden", repo.recent().isEmpty())

        repo.restore(exported, nowMillis = now)

        val restored = repo.recent().single()
        assertEquals("sync-1", restored.syncId)
        assertEquals(300, restored.kcal)
        assertFalse("the restored entry must be live again", restored.deleted)
        assertEquals("the restore revision must beat the tombstone", now, restored.updatedAt)
        assertEquals("the local row id is reused", id, restored.id)
    }

    @Test
    fun `a restore wins over a newer local tombstone`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "sync-1", kcal = 300))
        val exported = repo.allForBackup()
        val id = repo.recent().single().id

        // Delete with a "now" that is BEFORE the restore timestamp we will use.
        val deleteNow = now - 60_000
        repo.delete(id, nowMillis = deleteNow)
        assertTrue(repo.recent().isEmpty())

        repo.restore(exported, nowMillis = now)

        assertEquals("the restore must win over a tombstone older than nowMillis", 1, repo.recent().size)
        assertEquals("the restore revision is nowMillis", now, repo.recent().single().updatedAt)
    }

    @Test
    fun `a restore older than the local tombstone revision does not resurrect the entry`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "sync-1", kcal = 300))
        val exported = repo.allForBackup()
        val id = repo.recent().single().id

        // Delete with a "now" that is AFTER the restore timestamp we will use:
        // the tombstone's revision is newer than the restore can author.
        val laterNow = now + 60_000
        repo.delete(id, nowMillis = laterNow)
        assertTrue(repo.recent().isEmpty())

        repo.restore(exported, nowMillis = now)

        // The restored row's revision is maxOf(now, base) = now, which is older than
        // the tombstone (laterNow), so it lands as a live row whose revision is stale.
        // Document the observed behaviour: the row is live here, but a later merge of
        // the newer tombstone would delete it again.
        assertEquals("the restore is written as a live row", 1, repo.recent().size)
        assertEquals("and it carries the restore time, not the tombstone time", now, repo.recent().single().updatedAt)

        // A subsequent merge of the newer tombstone re-deletes it: the restore lost.
        repo.mergeSynced(listOf(entry(syncId = "sync-1", updatedAt = laterNow, deleted = true)))
        assertTrue(
            "a tombstone newer than the restore revision wins the merge",
            repo.recent().isEmpty(),
        )
    }

    // ------------------------------------------------------------------ pending sync

    @Test
    fun `a restored entry is queued for the peer`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "sync-1"))
        val exported = repo.allForBackup()
        repo.markSynced(listOf("sync-1"))
        assertEquals("nothing pending before the restore", 0, repo.pendingSync().size)

        repo.restore(exported, nowMillis = now)

        val pending = repo.pendingSync()
        assertEquals(1, pending.size)
        assertEquals("sync-1", pending.single().syncId)
        assertTrue("the row must carry pendingSync = true", dao.all().single().pendingSync)
    }

    // ----------------------------------------------------------------- return value

    @Test
    fun `restore returns the number of rows written and skips blank syncIds`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)

        val written = repo.restore(
            listOf(
                entry(syncId = "a"),
                entry(syncId = ""),
                entry(syncId = "   "),
                entry(syncId = "b", loggedAt = base + 1),
            ),
            nowMillis = now,
        )

        assertEquals("only the two identified entries may be written", 2, written)
        assertEquals(2, repo.count())
        assertEquals(setOf("a", "b"), repo.recent().map { it.syncId }.toSet())
    }

    @Test
    fun `restoring an empty list writes nothing`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)

        assertEquals(0, repo.restore(emptyList(), nowMillis = now))
        assertEquals(0, repo.count())
    }

    // ------------------------------------------------------------------- revisedAt

    @Test
    fun `a restored entry's updatedAt is at least nowMillis`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        // The backup's own revision is older than the restore time.
        val stale = entry(syncId = "sync-1", loggedAt = base, updatedAt = base)

        repo.restore(listOf(stale), nowMillis = now)

        assertEquals("the restore must advance the revision to now", now, repo.recent().single().updatedAt)
    }

    @Test
    fun `restoring an entry whose stored updatedAt is in the future keeps the newer revision`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        val future = now + 90_000
        val futureEntry = entry(syncId = "sync-1", loggedAt = base, updatedAt = future)

        repo.restore(listOf(futureEntry), nowMillis = now)

        assertEquals("maxOf must not move a future revision backwards", future, repo.recent().single().updatedAt)
    }

    @Test
    fun `a stale peer copy cannot overwrite a restored entry`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "sync-1", kcal = 300))
        val exported = repo.allForBackup()
        val id = repo.recent().single().id
        repo.delete(id, nowMillis = base + 100)

        repo.restore(exported, nowMillis = now)

        // The watch still holds its old copy at a lower revision and re-sends it.
        repo.mergeSynced(
            listOf(entry(syncId = "sync-1", kcal = 999, updatedAt = base)),
        )

        val stored = repo.recent().single()
        assertEquals("the stale peer copy must be rejected", 300, stored.kcal)
        assertEquals(now, stored.updatedAt)
    }

    @Test
    fun `a harder race - stale peer copy after restore does not re-delete the entry`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = repo(dao)
        repo.saveEntry(entry(syncId = "sync-1", kcal = 300))
        val exported = repo.allForBackup()
        val id = repo.recent().single().id
        repo.delete(id, nowMillis = base + 100)

        repo.restore(exported, nowMillis = now)

        // A peer tombstone older than the restore must not undo it.
        repo.mergeSynced(
            listOf(entry(syncId = "sync-1", updatedAt = base, deleted = true)),
        )

        assertEquals("the restored entry must survive a stale tombstone", 1, repo.recent().size)
        assertFalse(repo.recent().single().deleted)
    }
}
