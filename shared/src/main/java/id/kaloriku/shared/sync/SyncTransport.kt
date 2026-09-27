package id.kaloriku.shared.sync

/** A paired node that messages can be sent to. */
data class PeerNode(val id: String, val displayName: String = "")

/**
 * The transport the sync layer talks to. [DataLayerTransport] is the production
 * Wear OS DataLayer implementation; tests substitute an in-memory fake.
 */
interface SyncTransport {
    suspend fun connectedNodes(): List<PeerNode>
    suspend fun send(nodeId: String, message: SyncMessage): Boolean
}

/** Convenience: send to every reachable node, returning the delivery count. */
suspend fun SyncTransport.sendToAll(message: SyncMessage): Int {
    var delivered = 0
    connectedNodes().forEach { node ->
        if (send(node.id, message)) delivered++
    }
    return delivered
}
