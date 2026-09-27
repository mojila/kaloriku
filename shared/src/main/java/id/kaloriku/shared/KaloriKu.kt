package id.kaloriku.shared

import android.content.Context
import id.kaloriku.shared.ai.KenariClient
import id.kaloriku.shared.analysis.FoodAnalyzer
import id.kaloriku.shared.analysis.InsightsGenerator
import id.kaloriku.shared.data.FoodRepository
import id.kaloriku.shared.data.KaloriKuDatabase
import id.kaloriku.shared.data.SettingsStore
import id.kaloriku.shared.sync.DataLayerTransport
import id.kaloriku.shared.sync.SyncCoordinator
import id.kaloriku.shared.sync.SyncRole

/**
 * Lightweight manual service locator shared by both apps. Holds the Room database,
 * settings, Kenari client, the analysis pipeline and the phone<->watch sync layer.
 * Initialise once per process.
 */
object KaloriKu {

    @Volatile
    private var container: Container? = null

    /**
     * Initialises the shared container. [role] must be passed by the calling app:
     * it is not defaulted so a module can never silently take the wrong side.
     * The first call wins, so the Application class is the authoritative caller.
     */
    fun init(context: Context, role: SyncRole): Container =
        container ?: synchronized(this) {
            container ?: Container(context.applicationContext, role).also { container = it }
        }

    fun container(): Container = container
        ?: error("KaloriKu.init(context) must be called before use.")

    class Container(
        private val appContext: Context,
        role: SyncRole,
    ) {
        val database: KaloriKuDatabase = KaloriKuDatabase.get(appContext)
        val settingsStore: SettingsStore = SettingsStore(appContext)
        val repository: FoodRepository = FoodRepository(database.foodEntryDao(), settingsStore)

        val transport: DataLayerTransport = DataLayerTransport(appContext)
        val sync: SyncCoordinator = SyncCoordinator(
            role = role,
            repository = repository,
            settings = settingsStore,
            transport = transport,
        )

        val kenari: KenariClient = KenariClient()

        val analyzer: FoodAnalyzer = FoodAnalyzer(kenari)
        val insights: InsightsGenerator = InsightsGenerator(kenari)
    }
}
