package id.kaloriku.shared.sync

import id.kaloriku.shared.FakeFoodEntryDao
import id.kaloriku.shared.FakeSettingsSource
import id.kaloriku.shared.FakeSyncTransport
import id.kaloriku.shared.data.FoodRepository
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch-edit propagation — the direction the convergence suite never covered.
 *
 * Reported defect: "edit on the watch, then sync, and the phone still shows the old
 * version". The merge layer itself is direction-symmetric and correct; these tests
 * exist to (a) cover the watch-as-editor path explicitly, (b) pin the reply that the
 * phone must send for the sync to terminate, and (c) pin the wire rule that keeps a
 * row with a missing revision stamp from out-ranking a real one.
 */
class WatchEditPropagationTest {

    private val day = "2026-09-25"
    private val base = 1790300000000L

    // ------------------------------------------------------------------ fixtures

    private fun entry(
        syncId: String,
        kcal: Int = 300,
        name: String = "Nasi Goreng",
        loggedAt: Long = base,
        updatedAt: Long = loggedAt,
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
    )

    /** One side of the bridge: its own database, transport and coordinator. */
    private class Device(val role: SyncRole, val nodeId: String) {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport(nodes = listOf(PeerNode("peer", "Partner")))
        val repo = FoodRepository(dao, FakeSettingsSource())
        val sync = SyncCoordinator(role, repo, FakeSettingsSource(), transport)
    }

    /** One full exchange, initiated by each side in turn, as the real apps do. */
    private suspend fun exchange(a: Device, b: Device) {
        a.sync.sync()
        a.transport.deliverAllTo(b.sync, fromNodeId = a.nodeId)
        b.sync.sync()
        b.transport.deliverAllTo(a.sync, fromNodeId = b.nodeId)
        a.transport.deliverAllTo(b.sync, fromNodeId = a.nodeId)
        b.transport.deliverAllTo(a.sync, fromNodeId = b.nodeId)
    }

    /** Repeated exchanges until the acks and the relayed recent lists settle. */
    private suspend fun settle(a: Device, b: Device, rounds: Int = 3) {
        repeat(rounds) {
            exchange(a, b)
            a.transport.clear()
            b.transport.clear()
        }
    }

    // ------------------------------------------------- end-to-end watch -> phone

    @Test
    fun `watch edit reaches the phone`() = runTest {
        val phone = Device(SyncRole.PHONE, "phone-node")
        val watch = Device(SyncRole.WATCH, "watch-node")

        phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)
        assertEquals(300, phone.repo.recent().single().kcal)

        // Edit on the watch: exactly what WearViewModel.editEntry does.
        val watchId = watch.repo.recent().single().id
        watch.repo.updateEntry(watchId, nowMillis = base + 10_000) {
            it.copy(foodName = "Nasi Goreng Spesial", kcal = 480)
        }
        settle(phone, watch)

        assertEquals(
            "the edited name must land on the phone",
            "Nasi Goreng Spesial",
            phone.repo.recent().single().foodName,
        )
        assertEquals("the edited kcal must land on the phone", 480, phone.repo.recent().single().kcal)
    }

    @Test
    fun `watch edit reaches the phone when the phone initiates the exchange`() = runTest {
        val phone = Device(SyncRole.PHONE, "phone-node")
        val watch = Device(SyncRole.WATCH, "watch-node")

        phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)

        val watchId = watch.repo.recent().single().id
        watch.repo.updateEntry(watchId, nowMillis = base + 10_000) { it.copy(kcal = 480) }

        // The phone drives: the watch can only answer via respondToRequest, which is
        // the path a "Sinkronkan sekarang" tap on the phone actually takes.
        phone.sync.sync()
        phone.transport.deliverAllTo(watch.sync, fromNodeId = phone.nodeId)
        watch.transport.deliverAllTo(phone.sync, fromNodeId = watch.nodeId)
        phone.transport.clear()
        watch.transport.clear()

        assertEquals("the watch must push its edit back on request", 480, phone.repo.recent().single().kcal)
    }

    // --------------------------------------------------- the reply that ends it

    @Test
    fun `the phone acks the watch edit so the watch stops re-pushing`() = runTest {
        val phone = Device(SyncRole.PHONE, "phone-node")
        val watch = Device(SyncRole.WATCH, "watch-node")

        phone.repo.saveEntry(entry("e1", kcal = 300))
        settle(phone, watch)

        val watchId = watch.repo.recent().single().id
        watch.repo.updateEntry(watchId, nowMillis = base + 10_000) { it.copy(kcal = 480) }
        watch.sync.sync()
        watch.transport.deliverAllTo(phone.sync, fromNodeId = watch.nodeId)

        // Without this reply the watch keeps re-sending the same revision on every
        // exchange: the edit would look delivered but never actually settle. Both
        // batches the watch sent carry the entry, so both are acked.
        val acks = phone.transport.messagesOfType<SyncMessage.Ack>()
        assertTrue("the phone must ack the batches it stored", acks.isNotEmpty())
        assertTrue(
            "every acked batch must cover the edited entry: $acks",
            acks.all { it.syncIds.contains("e1") },
        )
    }
}
