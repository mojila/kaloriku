package id.kaloriku.shared.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import id.kaloriku.shared.domain.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kaloriku_settings")

/** Read/write access to [AppSettings]; lets the repository be tested without Android. */
interface SettingsSource {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
}

/** Persists [AppSettings] with DataStore. */
class SettingsStore(private val context: Context) : SettingsSource {

    private object Keys {
        val target = intPreferencesKey("daily_target_kcal")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            dailyTargetKcal = prefs[Keys.target] ?: AppSettings.DEFAULT_TARGET,
        )
    }

    override suspend fun current(): AppSettings = settings.first()

    suspend fun setDailyTarget(value: Int) {
        context.dataStore.edit { it[Keys.target] = value.coerceIn(500, 6000) }
    }
}
