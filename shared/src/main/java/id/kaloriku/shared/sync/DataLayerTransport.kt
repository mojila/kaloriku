package id.kaloriku.shared.sync

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Production [SyncTransport] over the Wear OS DataLayer message API.
 *
 * The DataLayer rides the phone/watch Bluetooth (and Wi-Fi) link managed by Google
 * Play Services. For delivery to work, both APKs must share the same
 * `applicationId` and signing certificate.
 *
 * Transport failures never throw: a missing pairing simply reports zero nodes.
 */
class DataLayerTransport(private val context: Context) : SyncTransport {

    override suspend fun connectedNodes(): List<PeerNode> = runCatching {
        Wearable.getNodeClient(context).connectedNodes.await().map {
            PeerNode(id = it.id, displayName = it.displayName)
        }
    }.getOrDefault(emptyList())

    override suspend fun send(nodeId: String, message: SyncMessage): Boolean = runCatching {
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, message.path, SyncCodec.encode(message))
            .await()
        true
    }.getOrDefault(false)
}
