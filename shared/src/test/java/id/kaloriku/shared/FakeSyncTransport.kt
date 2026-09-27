package id.kaloriku.shared

import id.kaloriku.shared.sync.PeerNode
import id.kaloriku.shared.sync.SyncCodec
import id.kaloriku.shared.sync.SyncMessage
import id.kaloriku.shared.sync.SyncTransport

/**
 * In-memory [SyncTransport] that records outbound messages and can deliver them
 * to another coordinator, simulating the DataLayer.
 */
class FakeSyncTransport(
    var nodes: List<PeerNode> = listOf(PeerNode("peer-node", "Partner")),
    var connected: Boolean = true,
    /** When true, [send] reports failure (simulates a dropped link). */
    var failSends: Boolean = false,
) : SyncTransport {

    /** Everything sent, in order. */
    val sent = mutableListOf<Pair<String, SyncMessage>>()

    override suspend fun connectedNodes(): List<PeerNode> = if (connected) nodes else emptyList()

    override suspend fun send(nodeId: String, message: SyncMessage): Boolean {
        if (failSends) return false
        sent += nodeId to message
        return true
    }

    /** Messages of a given type, decoded from what was recorded. */
    inline fun <reified T : SyncMessage> messagesOfType(): List<T> =
        sent.mapNotNull { it.second as? T }

    /** Delivers every recorded message to [peer], as the DataLayer would. */
    suspend fun deliverAllTo(peer: id.kaloriku.shared.sync.SyncCoordinator, fromNodeId: String = "peer-node") {
        // Copy first: the peer may enqueue acks while we iterate.
        val batch = sent.toList()
        batch.forEach { (_, message) ->
            peer.handle(message.path, SyncCodec.encode(message), fromNodeId)
        }
    }

    fun clear() = sent.clear()
}
