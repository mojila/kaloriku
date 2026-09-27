package id.kaloriku.wear.sync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.sync.SyncRole
import kotlinx.coroutines.runBlocking

/**
 * Wear side of the DataLayer bridge. All protocol handling lives in the shared
 * [id.kaloriku.shared.sync.SyncCoordinator]; this service only provides the
 * Android entry point so the phone's messages reach the watch app.
 */
class WearSyncService : WearableListenerService() {

    /**
     * Run the exchange to completion before returning.
     *
     * This matters most on the watch: when the phone asks for a sync while the watch
     * app is closed, this callback is the only reason the process exists. A dispatched
     * coroutine can be killed before it pushes the watch's pending edit back, leaving
     * the phone stuck on the old revision no matter how often the user syncs.
     */
    override fun onMessageReceived(event: MessageEvent) {
        val container = KaloriKu.init(applicationContext, SyncRole.WATCH)
        runBlocking {
            container.sync.handle(event.path, event.data, event.sourceNodeId)
        }
    }
}
