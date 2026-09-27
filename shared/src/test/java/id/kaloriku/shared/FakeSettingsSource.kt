package id.kaloriku.shared

import id.kaloriku.shared.data.SettingsSource
import id.kaloriku.shared.domain.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SettingsSource] for unit tests. */
class FakeSettingsSource(initial: AppSettings = AppSettings()) : SettingsSource {

    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> get() = state

    override suspend fun current(): AppSettings = state.value

    fun set(value: AppSettings) {
        state.value = value
    }
}
