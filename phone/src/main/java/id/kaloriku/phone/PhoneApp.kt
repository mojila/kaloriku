package id.kaloriku.phone

import android.app.Application
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.sync.SyncRole

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Eagerly build the shared container so the first screen has no cold-start cost.
        KaloriKu.init(this, SyncRole.PHONE)
    }
}
