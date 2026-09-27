package id.kaloriku.shared.sync

import id.kaloriku.shared.data.FoodRepository
import id.kaloriku.shared.data.SettingsSource
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which side of the bridge this process is. */
enum class SyncRole { PHONE, WATCH }

/**
 * The single sync entry point used by both apps.
 *
 * The exchange is deliberately one-directional per initiation, which keeps the two
 * sides from echoing messages at each other:
 *
 *  1. The initiator pushes its pending entries, then sends [SyncMessage.RequestSync]
 *     and its own [SyncMessage.RecentUpdate].
 *  2. The responder answers a `RequestSync` with *its* pending entries and recent
 *     list. It never sends a `RequestSync` of its own.
 *  3. Each side acks the batches it stored, which clears the sender's pending flag.
 *
 * Merging is keyed on [FoodEntry.syncId] and is idempotent, so repeated syncs are
 * harmless. A locally logged entry keeps `pendingSync = true` until the peer acks
 * it, which makes delivery retry-safe across process death and flaky links.
 */
class SyncCoordinator(
    private val role: SyncRole,
    private val repository: FoodRepository,
    private val settings: SettingsSource,
    private val transport: SyncTransport,
) {

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /**
     * Serialises exchanges. A sync requested while another is in flight waits its
     * turn and then runs, instead of being dropped: the push that follows a save is
     * how a fresh edit leaves this device, so losing it would strand the entry with
     * nothing scheduled to deliver it until the next app foreground.
     */
    private val syncMutex = Mutex()

    private fun log(message: String) {
        android.util.Log.d(TAG, "[$role] $message")
    }

    /**
     * Full exchange, initiated by this device: used by the manual buttons, on app
     * foreground, and after every save.
     *
     * @param manual true when the user pressed the sync button, which enables the
     *   "done" confirmation message.
     */
    suspend fun sync(manual: Boolean = false) {
        syncMutex.withLock { runExchange(manual) }
    }

    private suspend fun runExchange(manual: Boolean) {
        _status.value = _status.value.copy(running = true, message = null)

        val nodes = try {
            transport.connectedNodes()
        } catch (e: Exception) {
            // A transport failure must not leave the status wedged at "running", which
            // would block every later sync attempt.
            log("connectedNodes failed: ${e.message}")
            emptyList()
        }
        log("sync(manual=$manual) nodes=${nodes.map { it.id }}")
        if (nodes.isEmpty()) {
            _status.value = _status.value.copy(
                running = false,
                connected = false,
                message = "Belum terhubung ke perangkat pasangan. " +
                    "Pastikan jam dan HP tersambung lewat Bluetooth.",
            )
            return
        }

        try {
            var delivered = pushPending(nodes.map { it.id })
            val payload = recentUpdate()
            nodes.forEach { node ->
                if (transport.send(node.id, SyncMessage.RequestSync)) delivered++
                if (transport.send(node.id, payload)) delivered++
            }
            log("sync sent=$delivered recent=${payload.entries.size} pending=${repository.pendingSync().size}")

            _status.value = SyncStatus(
                running = false,
                connected = true,
                pending = repository.pendingSync().size,
                lastSuccessAt = System.currentTimeMillis(),
                message = if (manual) "Sinkronisasi selesai." else null,
            )
        } catch (e: Exception) {
            // Release the running flag so the user can retry; local data is untouched
            // and any pending entries stay queued for the next attempt.
            log("sync failed: ${e.message}")
            _status.value = _status.value.copy(
                running = false,
                message = "Sinkronisasi gagal: ${e.message ?: "coba lagi"}",
            )
        }
    }

    /**
     * Answers a peer's [SyncMessage.RequestSync]. Pushes our state back but never
     * asks for theirs, so the exchange terminates.
     */
    private suspend fun respondToRequest(nodeId: String) {
        val pushed = pushPending(listOf(nodeId))
        val payload = recentUpdate()
        val ok = transport.send(nodeId, payload)
        log("respond pushed=$pushed recent=${payload.entries.size} delivered=$ok")
    }

    /** Handles one inbound DataLayer message. Called from the listener services. */
    suspend fun handle(path: String, data: ByteArray, senderNodeId: String) {
        val message = SyncCodec.decode(path, data) ?: return
        log("recv ${message::class.simpleName} from=$senderNodeId")
        when (message) {
            is SyncMessage.RequestSync -> respondToRequest(senderNodeId)
            is SyncMessage.NewEntries -> mergeAndAck(message.entries, senderNodeId)
            is SyncMessage.RecentUpdate -> mergeAndAck(message.entries, senderNodeId)
            is SyncMessage.Ack -> {
                repository.markSynced(message.syncIds)
                log("acked ${message.syncIds.size} pending=${repository.pendingSync().size}")
                _status.value = _status.value.copy(
                    connected = true,
                    pending = repository.pendingSync().size,
                    lastSuccessAt = System.currentTimeMillis(),
                )
            }
        }
    }

    /** Merges a batch, then acks the sender so it can clear its pending flags. */
    private suspend fun mergeAndAck(entries: List<FoodEntry>, senderNodeId: String) {
        if (entries.isEmpty()) return
        val accepted = repository.mergeSynced(entries)
        log("merged ${accepted.size}/${entries.size} from=$senderNodeId")
        if (accepted.isNotEmpty()) {
            transport.send(senderNodeId, SyncMessage.Ack(accepted))
            _status.value = _status.value.copy(
                connected = true,
                lastMerged = accepted.size,
                lastSuccessAt = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun pushPending(nodes: List<String>): Int {
        val pending = repository.pendingSync()
        if (pending.isEmpty()) return 0
        val message = SyncMessage.NewEntries(pending, sourceForRole())
        var delivered = 0
        nodes.forEach { node ->
            if (transport.send(node, message)) delivered++
        }
        return delivered
    }

    private suspend fun recentUpdate(): SyncMessage.RecentUpdate {
        val recent = repository.recent(30)
        val todayKey = JakartaTime.todayKey()
        val todayTotal = recent.filter { it.dayKey == todayKey }.sumOf { it.kcal }
        val target = settings.current().dailyTargetKcal
        return SyncMessage.RecentUpdate(recent, todayTotal, target)
    }

    private fun sourceForRole(): LogSource =
        if (role == SyncRole.WATCH) LogSource.VOICE_WATCH else LogSource.VOICE_PHONE

    private companion object {
        const val TAG = "KaloriKuSync"
    }
}
