package id.kaloriku.shared.sync

import id.kaloriku.shared.FakeFoodEntryDao
import id.kaloriku.shared.FakeSettingsSource
import id.kaloriku.shared.FakeSyncTransport
import id.kaloriku.shared.data.FoodRepository
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end convergence coverage for the edit/delete feature.
 *
 * Two independent [FoodRepository] instances are driven through two real
 * [SyncCoordinator]s wired to [FakeSyncTransport]s that act as the Wear DataLayer:
 * [FakeSyncTransport.deliverAllTo] hands every recorded message to the peer.
 *
 * A "full exchange" is modelled exactly like the real apps: each side runs
 * `sync()` (which pushes pending + its recent list and asks the peer to push back),
 * then both transports are drained into the other coordinator. Running the exchange
 * twice settles the acks so both pending queues empty out.
 */
class EditDeleteConvergenceTest {

    private val day = "2026-09-25"
    private val base = 1790300000000L

    // ------------------------------------------------------------------ fixtures

    private fun entry(
        syncId: String,
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
        portionText = "satu piring",
        kcal = kcal,
        kcalLow = kcal,
        kcalHigh = kcal,
        confidence = 0.8,
        source = LogSource.VOICE_PHONE,
        updatedAt = updatedAt,
        deleted = deleted,
    )

    /** One device under test: its DAO, repository, coordinator and fake transport. */
    private class Device(
        val role: SyncRole,
        val nodeId: String,
    ) {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport(nodes = listOf(PeerNode("peer", "Partner")))
        val repo = FoodRepository(dao, FakeSettingsSource())
        val sync = SyncCoordinator(role, repo, FakeSettingsSource(), transport)
    }

    private suspend fun exchange(a: Device, b: Device) {
        // Each side initiates; a responder never echoes a RequestSync, so this
        // terminates after one round per initiator plus the acks.
        a.sync.sync()
        a.transport.deliverAllTo(b.sync, fromNodeId = a.nodeId)
        b.sync.sync()
        b.transport.deliverAllTo(a.sync, fromNodeId = b.nodeId)
        // Flush the acks that the deliveries above enqueued at the tail of the
        // exchange (A acked B's batch after A had already handed its own batch to
        // B). A real DataLayer delivers these; dropping them would strand a pending
        // flag and make this harness lie.
        a.transport.deliverAllTo(b.sync, fromNodeId = a.nodeId)
        b.transport.deliverAllTo(a.sync, fromNodeId = b.nodeId)
    }

    /** Runs a few full exchanges so pending flags and acks fully settle. */
    private suspend fun settle(a: Device, b: Device, rounds: Int = 3) {
        repeat(rounds) {
            exchange(a, b)
            a.transport.clear()
            b.transport.clear()
        }
    }

    private fun phone() = Device(SyncRole.PHONE, "phone-node")
    private fun watch() = Device(SyncRole.WATCH, "watch-node")

    // ------------------------------------------------------- phone edit -> watch

    @Test
    fun `phone edit reaches the watch`() = runTest {
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300, name = "Nasi Goreng"))
        settle(phone, watch)

        assertEquals("watch must hold the original", 1, watch.repo.count())
        assertEquals(300, watch.repo.recent().single().kcal)

        // Edit on the phone; the revision moves past the original loggedAt.
        assertTrue(
            phone.repo.updateEntry(id, nowMillis = base + 10_000) {
                it.copy(foodName = "Nasi Goreng Spesial", kcal = 480)
            },
        )
        settle(phone, watch)

        val onWatch = watch.repo.recent().single()
        assertEquals("edited name must land on the watch", "Nasi Goreng Spesial", onWatch.foodName)
        assertEquals("edited kcal must land on the watch", 480, onWatch.kcal)
        assertEquals(base + 10_000, onWatch.updatedAt)
        assertEquals("edit must not duplicate the row", 1, watch.repo.count())
    }

    // ------------------------------------------------------- watch delete -> phone

    @Test
    fun `watch delete reaches the phone and drops the day total`() = runTest {
        val phone = phone()
        val watch = watch()

        // Phone logs two entries summing to 700; the watch receives both.
        phone.repo.saveEntry(entry("e1", kcal = 300, name = "Nasi Goreng"))
        phone.repo.saveEntry(entry("e2", kcal = 400, name = "Sate Ayam", loggedAt = base + 1))
        settle(phone, watch)

        assertEquals(700, phone.repo.observeDayTotal(day).let { firstValue(it) })
        assertEquals(2, watch.repo.count())

        // Delete e1 on the watch.
        val watchId = watch.repo.recent().first { it.syncId == "e1" }.id
        watch.repo.delete(watchId, nowMillis = base + 20_000)
        settle(phone, watch)

        assertNull("deleted entry must disappear from the phone", phone.repo.find(phoneIdOf(phone, "e1")))
        assertEquals("phone day total must drop", 400, firstValue(phone.repo.observeDayTotal(day)))
        assertEquals("only e2 remains on the phone", 1, phone.repo.count())
        assertEquals("only e2 remains on the watch", 1, watch.repo.count())
    }

    // ------------------------------------------------------- phone delete -> watch

    @Test
    fun `phone delete reaches the watch and drops the day total`() = runTest {
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 650))
        settle(phone, watch)
        assertEquals(650, firstValue(watch.repo.observeDayTotal(day)))

        phone.repo.delete(id, nowMillis = base + 20_000)
        settle(phone, watch)

        assertTrue("phone must hide the tombstone", phone.repo.recent().isEmpty())
        assertTrue("watch must hide the tombstone", watch.repo.recent().isEmpty())
        assertEquals(0, firstValue(watch.repo.observeDayTotal(day)))
        assertEquals(0, watch.repo.count())
    }

    // ------------------------------------------------- stale resurrection blocked

    @Test
    fun `a stale peer copy cannot resurrect a deleted entry`() = runTest {
        val phone = phone()
        val watch = watch()

        // Phone logs e1 and delivers it; the watch stores it live.
        val id = phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)
        assertEquals(1, watch.repo.count())

        // The phone then edits AND deletes before the watch has acked the edit, so
        // the phone still has a pending revision and the watch still holds the
        // live original queued as... nothing (it was applied as non-pending). To
        // model the nastiest race, the watch re-queues its live copy as pending
        // (as if it had been logged/edited there just before receiving the delete).
        phone.repo.updateEntry(id, nowMillis = base + 5_000) { it.copy(kcal = 500) }
        phone.repo.delete(id, nowMillis = base + 30_000)
        assertTrue("tombstone must be queued", phone.repo.pendingSync().single().deleted)

        val staleWatchId = watch.repo.recent().single().id
        watch.repo.updateEntry(staleWatchId, nowMillis = base + 1) { it.copy(kcal = 300) }
        assertEquals(1, watch.repo.pendingSync().size)

        // Full exchange: the phone must send its newer tombstone, the watch must
        // apply it, and the watch's own stale pending copy must lose.
        settle(phone, watch)

        assertTrue("entry must stay deleted on the phone", phone.repo.recent().isEmpty())
        assertTrue("entry must stay deleted on the watch", watch.repo.recent().isEmpty())
        assertEquals(0, phone.repo.count())
        assertEquals(0, watch.repo.count())
    }

    @Test
    fun `a tombstone already acked on the sender cannot be undone by the peer re-pushing a live copy`() = runTest {
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)

        // The watch holds the live copy and marks it pending again (un-acked local
        // edit), then the phone deletes and delivers the tombstone.
        val watchId = watch.repo.recent().single().id
        watch.repo.updateEntry(watchId, nowMillis = base + 2) { it.copy(kcal = 310) }

        phone.repo.delete(id, nowMillis = base + 40_000)
        settle(phone, watch)

        assertTrue(phone.repo.recent().isEmpty())
        assertTrue(watch.repo.recent().isEmpty())
        assertEquals("stale live revision must not win", 0, watch.repo.count())
    }

    // ----------------------------------------------------- concurrent edit

    @Test
    fun `concurrent edits converge on the newer revision`() = runTest {
        val phone = phone()
        val watch = watch()

        // Seed both sides with the same entry.
        val id = phone.repo.saveEntry(entry("e1", kcal = 300, name = "Nasi Goreng"))
        settle(phone, watch)

        // Both edit the same syncId without seeing each other's change.
        phone.repo.updateEntry(id, nowMillis = base + 5_000) { it.copy(kcal = 450, foodName = "Nasi Goreng Porsi Besar") }
        val watchId = watch.repo.recent().single().id
        watch.repo.updateEntry(watchId, nowMillis = base + 9_000) { it.copy(kcal = 520, foodName = "Nasi Goreng Telur") }

        settle(phone, watch)

        val phoneCopy = phone.repo.recent().single()
        val watchCopy = watch.repo.recent().single()
        assertEquals("both devices must agree on kcal", 520, phoneCopy.kcal)
        assertEquals(520, watchCopy.kcal)
        assertEquals("both devices must agree on the name", "Nasi Goreng Telur", phoneCopy.foodName)
        assertEquals("Nasi Goreng Telur", watchCopy.foodName)
        assertEquals("the newer revision must win", base + 9_000, phoneCopy.updatedAt)
        assertEquals(1, phone.repo.count())
        assertEquals(1, watch.repo.count())
    }

    @Test
    fun `a true tie keeps the receiver's local revision and both sides still settle`() = runTest {
        val phone = phone()
        val watch = watch()

        // Seed both sides, then edit both to the SAME updatedAt with different data.
        // The merge rule is strictly `incoming.updatedAt > existing.updatedAt`, so on a
        // true tie the incoming revision is ignored and the receiver keeps its own
        // copy. That means the two devices can retain DIFFERENT payloads for a tie,
        // but because neither side re-queues the losing revision neither one is
        // pending, so the exchange terminates and does not ping-pong. This test pins
        // that actual behaviour: tie => divergence in payload, convergence in quiescence.
        val id = phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)

        val tie = base + 7_000
        phone.repo.updateEntry(id, nowMillis = tie) { it.copy(kcal = 410, foodName = "Phone Wins") }
        val watchId = watch.repo.recent().single().id
        watch.repo.updateEntry(watchId, nowMillis = tie) { it.copy(kcal = 620, foodName = "Watch Wins") }

        settle(phone, watch)

        assertEquals("phone keeps its own revision on a tie", "Phone Wins", phone.repo.recent().single().foodName)
        assertEquals("watch keeps its own revision on a tie", "Watch Wins", watch.repo.recent().single().foodName)
        assertEquals("tie must not leave anything pending on the phone", 0, phone.repo.pendingSync().size)
        assertEquals("tie must not leave anything pending on the watch", 0, watch.repo.pendingSync().size)

        // And it must not ping-pong: further exchanges keep both queues empty.
        settle(phone, watch, rounds = 4)
        assertEquals(0, phone.repo.pendingSync().size)
        assertEquals(0, watch.repo.pendingSync().size)
        assertEquals(1, phone.repo.count())
        assertEquals(1, watch.repo.count())
    }

    // ------------------------------------------------- edit then delete ordering

    @Test
    fun `edit then delete on one side ends deleted everywhere`() = runTest {
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)

        phone.repo.updateEntry(id, nowMillis = base + 1_000) { it.copy(kcal = 999, foodName = "Edited") }
        phone.repo.delete(id, nowMillis = base + 2_000)
        settle(phone, watch)

        assertTrue("phone must end deleted", phone.repo.recent().isEmpty())
        assertTrue("watch must end deleted", watch.repo.recent().isEmpty())
        assertEquals(0, phone.repo.count())
        assertEquals(0, watch.repo.count())
        // The final revision delivered must be the tombstone, not the edit.
        assertEquals(base + 2_000, phone.dao.findBySyncId("e1")!!.updatedAt)
        assertEquals(base + 2_000, watch.dao.findBySyncId("e1")!!.updatedAt)
    }

    @Test
    fun `delete then edit on one side stays deleted`() = runTest {
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)

        phone.repo.delete(id, nowMillis = base + 1_000)
        // Editing a tombstone must be refused, so no resurrection revision is made.
        assertFalse(phone.repo.updateEntry(id, nowMillis = base + 2_000) { it.copy(kcal = 999) })
        settle(phone, watch)

        assertTrue(phone.repo.recent().isEmpty())
        assertTrue(watch.repo.recent().isEmpty())
    }

    // ------------------------------------------------------ no ping-pong regression

    @Test
    fun `after convergence repeated exchanges change nothing`() = runTest {
        val phone = phone()
        val watch = watch()

        // A typical mixed history: one logged on the phone, one on the watch,
        // then an edit and a delete.
        val phoneId = phone.repo.saveEntry(entry("p1", kcal = 400, name = "Bubur Ayam"))
        val watchId = watch.repo.saveEntry(
            entry("w1", kcal = 250, name = "Sate Ayam", loggedAt = base + 1),
        )
        settle(phone, watch)

        phone.repo.updateEntry(phoneId, nowMillis = base + 50_000) { it.copy(kcal = 420) }
        watch.repo.delete(watchId, nowMillis = base + 60_000)
        settle(phone, watch)

        val phoneRows = phone.dao.all().map { it.syncId to it.updatedAt }.sortedBy { it.first }
        val watchRows = watch.dao.all().map { it.syncId to it.updatedAt }.sortedBy { it.first }
        val phoneCount = phone.repo.count()
        val watchCount = watch.repo.count()

        assertEquals(0, phone.repo.pendingSync().size)
        assertEquals(0, watch.repo.pendingSync().size)

        // Several more full exchanges: nothing may be re-queued, no extra sends
        // beyond the (empty) recent list, and the stored rows must not change.
        repeat(6) { exchange(phone, watch) }

        assertEquals("pending must stay empty (no sync loop)", 0, phone.repo.pendingSync().size)
        assertEquals("pending must stay empty (no sync loop)", 0, watch.repo.pendingSync().size)
        assertEquals("phone rows must not change", phoneRows, phone.dao.all().map { it.syncId to it.updatedAt }.sortedBy { it.first })
        assertEquals("watch rows must not change", watchRows, watch.dao.all().map { it.syncId to it.updatedAt }.sortedBy { it.first })
        assertEquals(phoneCount, phone.repo.count())
        assertEquals(watchCount, watch.repo.count())
    }

    @Test
    fun `a delete delivered twice is idempotent end to end`() = runTest {
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)
        phone.repo.delete(id, nowMillis = base + 10_000)

        // Deliver the tombstone twice (flaky link / retry) before the ack settles.
        phone.sync.sync()
        phone.transport.deliverAllTo(watch.sync, fromNodeId = phone.nodeId)
        phone.transport.deliverAllTo(watch.sync, fromNodeId = phone.nodeId)
        settle(phone, watch)

        assertTrue(phone.repo.recent().isEmpty())
        assertTrue(watch.repo.recent().isEmpty())
        assertEquals(0, phone.repo.count())
        assertEquals(0, watch.repo.count())
    }

    // --------------------------------------------------- phone edit -> watch

    @Test
    fun `a full phone edit of every field reaches the watch`() = runTest {
        // Mirrors EditEntrySheet's save: name, portion, meal and notes change and the
        // calories are re-estimated, exactly as MainViewModel.editEntry writes them.
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300, name = "Nasi Goreng"))
        settle(phone, watch)
        assertEquals("Nasi Goreng", watch.repo.recent().single().foodName)

        assertTrue(
            phone.repo.updateEntry(id, nowMillis = base + 10_000) {
                it.copy(
                    foodName = "Nasi Goreng Telur",
                    canonicalName = "nasi_goreng_telur",
                    portionText = "dua piring",
                    grams = 400.0,
                    meal = MealType.CAMILAN,
                    notes = "makan malam",
                    kcal = 620,
                    kcalLow = 560,
                    kcalHigh = 700,
                    confidence = 0.7,
                    source = LogSource.MANUAL,
                )
            },
        )
        settle(phone, watch)

        val onWatch = watch.repo.recent().single()
        assertEquals("name", "Nasi Goreng Telur", onWatch.foodName)
        assertEquals("canonical name", "nasi_goreng_telur", onWatch.canonicalName)
        assertEquals("portion", "dua piring", onWatch.portionText)
        assertEquals("grams", 400.0, onWatch.grams!!, 0.001)
        assertEquals("meal", MealType.CAMILAN, onWatch.meal)
        assertEquals("notes", "makan malam", onWatch.notes)
        assertEquals("re-estimated kcal", 620, onWatch.kcal)
        assertEquals("range low", 560, onWatch.kcalLow)
        assertEquals("range high", 700, onWatch.kcalHigh)
        assertEquals("confidence", 0.7, onWatch.confidence, 0.001)
        assertEquals("source", LogSource.MANUAL, onWatch.source)
        assertEquals("revision", base + 10_000, onWatch.updatedAt)
        assertTrue("must read as edited", onWatch.isEdited)
        assertEquals("an edit must not duplicate the row", 1, watch.repo.count())
    }

    @Test
    fun `a metadata-only phone edit still reaches the watch`() = runTest {
        // Changing only the meal or notes skips the Jev re-estimate, so the calories
        // must ride along unchanged while the new metadata still syncs.
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300, name = "Nasi Goreng"))
        settle(phone, watch)

        phone.repo.updateEntry(id, nowMillis = base + 4_000) {
            it.copy(meal = MealType.SARAPAN, notes = "porsi kecil")
        }
        settle(phone, watch)

        val onWatch = watch.repo.recent().single()
        assertEquals(MealType.SARAPAN, onWatch.meal)
        assertEquals("porsi kecil", onWatch.notes)
        assertEquals("calories must be untouched", 300, onWatch.kcal)
        assertEquals(1, watch.repo.count())
    }

    @Test
    fun `a phone edit made while the watch is offline lands on the next sync`() = runTest {
        // The edit is queued as pending until the peer acks, so a watch that was not
        // reachable at edit time still converges once the link comes back.
        val phone = phone()
        val watch = watch()

        val id = phone.repo.saveEntry(entry("e1", kcal = 300, name = "Nasi Goreng"))
        settle(phone, watch)

        phone.repo.updateEntry(id, nowMillis = base + 12_000) {
            it.copy(foodName = "Nasi Goreng Spesial", kcal = 540)
        }
        // No exchange yet: the revision must stay queued, not be lost.
        assertEquals("the edit must be queued for the peer", 1, phone.repo.pendingSync().size)

        settle(phone, watch)

        assertEquals("Nasi Goreng Spesial", watch.repo.recent().single().foodName)
        assertEquals(540, watch.repo.recent().single().kcal)
        assertEquals("queue must drain after delivery", 0, phone.repo.pendingSync().size)
        assertEquals(1, watch.repo.count())
    }

    // --------------------------------------------------- delete all -> peer

    @Test
    fun `delete all on the phone clears the watch too`() = runTest {
        val phone = phone()
        val watch = watch()

        // Two entries logged on the phone, delivered to the watch, plus one the
        // watch logged itself. After settling, both sides hold all three.
        phone.repo.saveEntry(entry("p1", kcal = 300, name = "Nasi Goreng"))
        phone.repo.saveEntry(entry("p2", kcal = 400, name = "Sate Ayam", loggedAt = base + 1))
        watch.repo.saveEntry(entry("w1", kcal = 180, name = "Pisang Goreng", loggedAt = base + 2))
        settle(phone, watch)
        assertEquals(3, phone.repo.count())
        assertEquals(3, watch.repo.count())

        // "Hapus semua data" on the phone must tombstone and push, not hard-delete.
        phone.repo.deleteAllSynced(nowMillis = base + 30_000)
        assertEquals("phone must hide every entry immediately", 0, phone.repo.count())
        assertTrue("the whole batch must be queued for the peer", phone.repo.pendingSync().isNotEmpty())

        settle(phone, watch)

        assertEquals("watch must be cleared too", 0, watch.repo.count())
        assertTrue("watch must hide every entry", watch.repo.recent().isEmpty())
        assertEquals(0, firstValue(watch.repo.observeDayTotal(day)))
        assertEquals("nothing may stay queued", 0, phone.repo.pendingSync().size)
        assertEquals("nothing may stay queued", 0, watch.repo.pendingSync().size)
    }

    @Test
    fun `a hard local delete all is not a sync operation`() = runTest {
        // Pins the distinction the repository documents: [deleteAll] is a local wipe
        // with no tombstones, so the peer keeps its copy. This is exactly why the
        // UI must call [deleteAllSynced].
        val phone = phone()
        val watch = watch()

        phone.repo.saveEntry(entry("p1", kcal = 300))
        settle(phone, watch)
        assertEquals(1, watch.repo.count())

        phone.repo.deleteAll()
        assertEquals(0, phone.repo.count())
        assertEquals("a hard wipe queues nothing", 0, phone.repo.pendingSync().size)

        settle(phone, watch)

        assertEquals("the peer is untouched by a hard wipe", 1, watch.repo.count())
    }

    // --------------------------------------------------------------- helpers

    private suspend fun phoneIdOf(device: Device, syncId: String): Long =
        device.dao.findBySyncId(syncId)?.id ?: error("no row for $syncId")

    private suspend fun <T> firstValue(flow: Flow<T>): T = flow.first()

    @Test
    fun `delete on watch of a locally logged watch entry reaches the phone`() = runTest {
        val phone = phone()
        val watch = watch()

        // The watch logs its own entry; the phone never saw it before.
        val watchId = watch.repo.saveEntry(
            entry("w-log", kcal = 180, name = "Pisang Goreng"),
        )
        settle(phone, watch)
        assertNotNull(phone.repo.recent().firstOrNull { it.syncId == "w-log" })

        watch.repo.delete(watchId, nowMillis = base + 3_000)
        settle(phone, watch)

        assertNull(phone.repo.recent().firstOrNull { it.syncId == "w-log" })
        assertEquals(0, phone.repo.count())
        assertEquals(0, watch.repo.count())
    }

    // -------------------------------------------- equal-revision delete hazard

    @Test
    fun `a delete that ties against a live local copy wins`() = runTest {
        // Ties go to the deletion. Two devices can only stamp the same millisecond by
        // coincidence, and when they do the safe reading is that the user deleted the
        // entry: letting the live copy win would resurrect it on the peer.
        val dao = FakeFoodEntryDao()
        val repo = FoodRepository(dao, FakeSettingsSource())
        repo.saveEntry(entry("e1", kcal = 300, updatedAt = base))
        repo.markSynced(listOf("e1"))

        repo.mergeSynced(listOf(entry("e1", updatedAt = base, deleted = true)))

        assertEquals("an equal-revision tombstone deletes", 0, repo.count())
        assertTrue(dao.findBySyncId("e1")!!.deleted)
    }

    @Test
    fun `a delete one millisecond newer than the live copy wins`() = runTest {
        val dao = FakeFoodEntryDao()
        val repo = FoodRepository(dao, FakeSettingsSource())
        repo.saveEntry(entry("e1", kcal = 300, updatedAt = base))
        repo.markSynced(listOf("e1"))

        repo.mergeSynced(listOf(entry("e1", updatedAt = base + 1, deleted = true)))

        assertEquals(0, repo.count())
        assertTrue(dao.findBySyncId("e1")!!.deleted)
    }

    @Test
    fun `a pending local revision does not starve a newer peer tombstone`() = runTest {
        // Local edit is still un-acked (pending) and the peer's delete is newer:
        // the tombstone must win and must clear the pending flag, or the entry
        // would stay queued and keep re-pushing a live revision forever.
        val dao = FakeFoodEntryDao()
        val repo = FoodRepository(dao, FakeSettingsSource())
        val id = repo.saveEntry(entry("e1", kcal = 300, updatedAt = base))

        // Simulate an un-acked local edit.
        repo.updateEntry(id, nowMillis = base + 5) { it.copy(kcal = 800) }
        assertEquals(1, repo.pendingSync().size)

        repo.mergeSynced(listOf(entry("e1", updatedAt = base + 10, deleted = true)))

        assertTrue(repo.recent().isEmpty())
        assertEquals("applying the peer tombstone must clear the pending flag", 0, repo.pendingSync().size)
        assertEquals(0, repo.count())
    }
}
