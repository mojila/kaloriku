package id.kaloriku.phone.sync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.sync.SyncRole
import kotlinx.coroutines.runBlocking

/**
 * Phone side of the DataLayer bridge. All protocol handling lives in the shared
 * [id.kaloriku.shared.sync.SyncCoordinator]; this service only provides the
 * Android entry point so messages wake the app.
 */
class PhoneSyncService : WearableListenerService() {

    /**
     * The exchange is run to completion inside the callback on purpose.
     *
     * Google Play Services only keeps this service bound while `onMessageReceived`
     * executes; once it returns the process may be torn down at any moment. Work
     * handed to a dispatched coroutine can therefore be killed before it runs, which
     * silently loses a peer's edit (no ack is ever sent, so the sender waits forever).
     * The handler only touches the local database and the DataLayer, so it is short
     * enough to complete here.
     */
    override fun onMessageReceived(event: MessageEvent) {
        val container = KaloriKu.init(applicationContext, SyncRole.PHONE)
        runBlocking {
            container.sync.handle(event.path, event.data, event.sourceNodeId)
        }
    }
}
