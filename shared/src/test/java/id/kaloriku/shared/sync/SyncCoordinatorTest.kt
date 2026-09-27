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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncCoordinatorTest {

    private fun entry(
        syncId: String,
        kcal: Int = 300,
        name: String = "Nasi Goreng",
        loggedAt: Long = 1790300000000L,
    ) = FoodEntry(
        syncId = syncId,
        loggedAt = loggedAt,
        dayKey = "2026-09-25",
        meal = MealType.MAKAN_SIANG,
        foodName = name,
        kcal = kcal,
        source = LogSource.VOICE_PHONE,
    )

    private fun repository(dao: FakeFoodEntryDao) = FoodRepository(dao, FakeSettingsSource())

    private fun coordinator(
        role: SyncRole,
        dao: FakeFoodEntryDao,
        transport: FakeSyncTransport,
    ) = SyncCoordinator(
        role = role,
        repository = repository(dao),
        settings = FakeSettingsSource(),
        transport = transport,
    )

    @Test
    fun `new entries start out pending and are pushed on sync`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport()
        val repo = repository(dao)
        val sync = coordinator(SyncRole.PHONE, dao, transport)

        repo.saveEntry(entry("a"))
        assertEquals(1, repo.pendingSync().size)

        sync.sync()

        val pushed = transport.messagesOfType<SyncMessage.NewEntries>().single()
        assertEquals(1, pushed.entries.size)
        assertEquals("a", pushed.entries.first().syncId)
    }

    @Test
    fun `receiving entries merges them and acks the sender`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport()
        val repo = repository(dao)
        val sync = coordinator(SyncRole.PHONE, dao, transport)

        sync.handle(
            SyncMessage.PATH_NEW_ENTRIES,
            SyncCodec.encode(SyncMessage.NewEntries(listOf(entry("w1")), LogSource.VOICE_WATCH)),
            senderNodeId = "watch-node",
        )

        assertEquals(1, repo.count())
        val ack = transport.messagesOfType<SyncMessage.Ack>().single()
        assertEquals(listOf("w1"), ack.syncIds)
    }

    @Test
    fun `merging is idempotent so redelivery cannot duplicate`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport()
        val repo = repository(dao)
        val sync = coordinator(SyncRole.PHONE, dao, transport)

        val payload = SyncCodec.encode(
            SyncMessage.NewEntries(listOf(entry("dup"), entry("dup2")), LogSource.VOICE_WATCH),
        )
        sync.handle(SyncMessage.PATH_NEW_ENTRIES, payload, "watch-node")
        sync.handle(SyncMessage.PATH_NEW_ENTRIES, payload, "watch-node")
        sync.handle(SyncMessage.PATH_NEW_ENTRIES, payload, "watch-node")

        assertEquals("three deliveries must still store two entries", 2, repo.count())
    }

    @Test
    fun `ack clears the pending flag so entries are not pushed twice`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport()
        val repo = repository(dao)
        val sync = coordinator(SyncRole.PHONE, dao, transport)

        repo.saveEntry(entry("a"))
        repo.saveEntry(entry("b"))
        assertEquals(2, repo.pendingSync().size)

        sync.handle(
            SyncMessage.PATH_ACK,
            SyncCodec.encode(SyncMessage.Ack(listOf("a", "b"))),
            "watch-node",
        )

        assertEquals(0, repo.pendingSync().size)
    }

    @Test
    fun `sync reports a helpful message when no peer is connected`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport(connected = false)
        val sync = coordinator(SyncRole.PHONE, dao, transport)

        sync.sync(manual = true)

        val status = sync.status.value
        assertFalse(status.connected)
        assertTrue("message should mention Bluetooth", status.message!!.contains("Bluetooth"))
    }

    @Test
    fun `sync succeeds and records the timestamp when a peer is present`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport()
        val sync = coordinator(SyncRole.PHONE, dao, transport)

        sync.sync(manual = true)

        val status = sync.status.value
        assertTrue(status.connected)
        assertTrue(status.hasSynced)
        assertEquals("Sinkronisasi selesai.", status.message)
    }

    @Test
    fun `failed delivery leaves entries pending for the next attempt`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport(failSends = true)
        val repo = repository(dao)
        val sync = coordinator(SyncRole.PHONE, dao, transport)

        repo.saveEntry(entry("retry-me"))
        sync.sync()

        assertEquals("entry must stay queued after a failed send", 1, repo.pendingSync().size)
    }

    @Test
    fun `full round trip converges both databases`() = runTest {
        // Phone and watch each hold their own database and coordinator.
        val phoneDao = FakeFoodEntryDao()
        val watchDao = FakeFoodEntryDao()
        val phoneRepo = repository(phoneDao)
        val watchRepo = repository(watchDao)

        val phoneTransport = FakeSyncTransport()
        val watchTransport = FakeSyncTransport()

        val phoneSync = SyncCoordinator(
            SyncRole.PHONE, phoneRepo, FakeSettingsSource(), phoneTransport,
        )
        val watchSync = SyncCoordinator(
            SyncRole.WATCH, watchRepo, FakeSettingsSource(), watchTransport,
        )

        // Each side logs an entry locally.
        phoneRepo.saveEntry(entry("phone-1", kcal = 400, name = "Bubur Ayam"))
        watchRepo.saveEntry(entry("watch-1", kcal = 200, name = "Sate Ayam"))

        // The watch initiates, the phone answers; deliver each side's messages.
        watchSync.sync()
        watchTransport.deliverAllTo(phoneSync, fromNodeId = "watch-node")
        phoneTransport.deliverAllTo(watchSync, fromNodeId = "phone-node")
        // A second pass so the acks settle both pending flags.
        watchSync.sync()
        watchTransport.deliverAllTo(phoneSync, fromNodeId = "watch-node")
        phoneTransport.deliverAllTo(watchSync, fromNodeId = "phone-node")

        assertEquals("phone should hold both entries", 2, phoneRepo.count())
        assertEquals("watch should hold both entries", 2, watchRepo.count())
        assertEquals(
            "phone entry must be marked delivered",
            0, phoneRepo.pendingSync().size,
        )
        assertEquals(
            "watch entry must be marked delivered",
            0, watchRepo.pendingSync().size,
        )
    }

    @Test
    fun `recent update from the peer is merged`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport()
        val repo = repository(dao)
        val sync = coordinator(SyncRole.WATCH, dao, transport)

        sync.handle(
            SyncMessage.PATH_RECENT,
            SyncCodec.encode(
                SyncMessage.RecentUpdate(
                    entries = listOf(entry("p1"), entry("p2")),
                    todayTotal = 600,
                    target = 2000,
                ),
            ),
            senderNodeId = "phone-node",
        )

        assertEquals(2, repo.count())
    }

    @Test
    fun `answering a request never sends another request back`() = runTest {
        val dao = FakeFoodEntryDao()
        val transport = FakeSyncTransport()
        val sync = coordinator(SyncRole.WATCH, dao, transport)

        sync.handle(
            SyncMessage.PATH_REQUEST,
            SyncCodec.encode(SyncMessage.RequestSync),
            senderNodeId = "phone-node",
        )

        // The responder pushes its state but must not echo a RequestSync, otherwise
        // the two devices would ping-pong forever.
        assertEquals(
            "responder must not send RequestSync",
            0,
            transport.messagesOfType<SyncMessage.RequestSync>().size,
        )
        assertTrue(
            "responder should still send its recent list",
            transport.messagesOfType<SyncMessage.RecentUpdate>().isNotEmpty(),
        )
    }
}
