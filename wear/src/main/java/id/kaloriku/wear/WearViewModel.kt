package id.kaloriku.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.domain.AnalysisResult
import id.kaloriku.shared.domain.AnalyzedItem
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import id.kaloriku.shared.sync.SyncRole
import id.kaloriku.shared.sync.SyncStatus
import id.kaloriku.wear.tile.requestCalorieTileUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface WearAnalyzeState {
    data object Idle : WearAnalyzeState
    data object Running : WearAnalyzeState
    data class Ready(val result: AnalysisResult) : WearAnalyzeState
    data class Error(val message: String) : WearAnalyzeState
}

data class WearHomeUi(
    val todayTotal: Int = 0,
    val target: Int = 2000,
    val recent: List<FoodEntry> = emptyList(),
) {
    val remaining: Int get() = (target - todayTotal).coerceAtLeast(0)
}

class WearViewModel(app: Application) : AndroidViewModel(app) {

    private val container = KaloriKu.init(app, SyncRole.WATCH)

    val syncStatus: StateFlow<SyncStatus> = container.sync.status

    val home: StateFlow<WearHomeUi> = kotlinx.coroutines.flow.combine(
        container.repository.observeDayTotal(),
        container.repository.observeRecent(15),
        container.repository.settings,
    ) { total, recent, settings ->
        WearHomeUi(todayTotal = total, target = settings.dailyTargetKcal, recent = recent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearHomeUi())

    private val _analyzeState = MutableStateFlow<WearAnalyzeState>(WearAnalyzeState.Idle)
    val analyzeState: StateFlow<WearAnalyzeState> = _analyzeState.asStateFlow()

    /** True while an edit is being saved (and possibly re-estimated through Jev). */
    private val _editBusy = MutableStateFlow(false)
    val editBusy: StateFlow<Boolean> = _editBusy.asStateFlow()

    /** True while a delete is in flight, so the UI can block double taps. */
    private val _deleteBusy = MutableStateFlow(false)
    val deleteBusy: StateFlow<Boolean> = _deleteBusy.asStateFlow()

    init {
        // Sync automatically when the app starts.
        viewModelScope.launch {
            container.sync.sync()
        }
    }

    fun analyze(transcript: String) {
        if (transcript.isBlank()) return
        viewModelScope.launch {
            _analyzeState.value = WearAnalyzeState.Running
            try {
                val recent = container.repository.recent(5)
                val result = container.analyzer.analyze(transcript, LogSource.VOICE_WATCH, existingRecent = recent)
                _analyzeState.value = if (result.items.isEmpty()) {
                    WearAnalyzeState.Error("Tidak ada makanan terdeteksi. Coba sebutkan lagi.")
                } else {
                    WearAnalyzeState.Ready(result)
                }
            } catch (e: Exception) {
                _analyzeState.value = WearAnalyzeState.Error(e.message ?: "Analisa gagal.")
            }
        }
    }

    fun confirm() {
        val ready = _analyzeState.value as? WearAnalyzeState.Ready ?: return
        viewModelScope.launch {
            container.repository.saveAnalysis(ready.result, LogSource.VOICE_WATCH)
            _analyzeState.value = WearAnalyzeState.Idle
            // The tile's total changed; ask the system to rebuild it right away.
            requestCalorieTileUpdate(getApplication())
            // Push the new entries to the phone automatically.
            container.sync.sync()
        }
    }

    /** Debug-only: analyze and persist immediately, for automated on-device checks. */
    fun analyzeAndSave(transcript: String) {
        if (transcript.isBlank()) return
        viewModelScope.launch {
            _analyzeState.value = WearAnalyzeState.Running
            try {
                val recent = container.repository.recent(5)
                val result = container.analyzer.analyze(transcript, LogSource.VOICE_WATCH, existingRecent = recent)
                if (result.items.isEmpty()) {
                    _analyzeState.value = WearAnalyzeState.Error("Tidak ada makanan terdeteksi.")
                } else {
                    container.repository.saveAnalysis(result, LogSource.VOICE_WATCH)
                    _analyzeState.value = WearAnalyzeState.Idle
                    requestCalorieTileUpdate(getApplication())
                    container.sync.sync()
                }
            } catch (e: Exception) {
                _analyzeState.value = WearAnalyzeState.Error(e.message ?: "Analisa gagal.")
            }
        }
    }

    fun dismiss() {
        _analyzeState.value = WearAnalyzeState.Idle
    }

    /**
     * Loads a single entry by its local row id.
     *
     * The home list is capped at the 15 newest rows, so an older entry that is still
     * alive would be missing from it. The detail/edit destinations use this as a
     * fallback before concluding the entry is gone, which keeps a screen opened from
     * a notification or a deep link working even after the row ages out of the window.
     * Returns null only when the entry genuinely no longer exists (deleted or unknown).
     */
    suspend fun findEntry(id: Long): FoodEntry? =
        // A read failure is indistinguishable from "not found" for the caller: both
        // mean the screen cannot show a valid entry, and popping home is the safe end.
        runCatching { container.repository.find(id) }.getOrNull()

    /**
     * Applies a user edit to an already-logged entry.
     *
     * When the name or portion changed the calorie estimate is no longer valid, so it
     * is re-asked through Jev via [FoodAnalyzer.reestimate]; editing only the meal,
     * notes or the date/time reuses the previous numbers and makes no network call.
     * The write preserves the entry's identity (the repository forces `id`/`syncId`)
     * and marks the row as hand-edited with [LogSource.MANUAL] before syncing.
     *
     * [dayKey]/[hour]/[minute] are the corrected date/time: a wrong-day or wrong-time
     * entry can be moved by the user. The new instant travels through the repository's
     * `newLoggedAt` parameter, never through [transform] — the repository overwrites
     * `loggedAt`/`dayKey` after the transform runs, so setting them in the lambda would
     * be silently discarded. Passing null (no change) leaves the original timestamp
     * untouched. Rescheduling does not touch [meal]: the meal the user picked stays.
     *
     * @return the persisted entry so the caller can show the new numbers without
     *   racing the database flow, or null when the entry was gone or the write failed.
     */
    suspend fun editEntry(
        id: Long,
        foodName: String,
        portionText: String,
        meal: MealType,
        notes: String?,
        dayKey: String,
        hour: Int,
        minute: Int,
    ): FoodEntry? {
        val trimmedName = foodName.trim()
        if (trimmedName.isEmpty()) return null
        val trimmedPortion = portionText.trim()
        _editBusy.value = true
        return try {
            val current = container.repository.find(id) ?: return null
            val nameOrPortionChanged =
                current.foodName.trim() != trimmedName || current.portionText.trim() != trimmedPortion
            // A pure reschedule must not invalidate the estimate: only the name/portion
            // check above feeds this, so moving the date/time never asks Jev again.
            val newLoggedAt = JakartaTime.atTime(dayKey, hour, minute)
                // A no-op reschedule stays null so the row is not needlessly re-revisioned.
                .takeIf { it != current.loggedAt }
            val estimate = if (nameOrPortionChanged) {
                container.analyzer.reestimate(
                    name = trimmedName,
                    portionText = trimmedPortion,
                    grams = current.grams,
                    meal = meal,
                    previous = current.toAnalyzedItem(),
                )
            } else {
                null
            }
            val applied = container.repository.updateEntry(
                id = id,
                newLoggedAt = newLoggedAt,
            ) { existing ->
                existing.copy(
                    foodName = trimmedName,
                    canonicalName = estimate?.canonicalName ?: existing.canonicalName,
                    portionText = trimmedPortion,
                    grams = estimate?.grams ?: existing.grams,
                    kcal = estimate?.kcal ?: existing.kcal,
                    kcalLow = estimate?.kcalLow ?: existing.kcalLow,
                    kcalHigh = estimate?.kcalHigh ?: existing.kcalHigh,
                    confidence = estimate?.confidence ?: existing.confidence,
                    isLocal = estimate?.isLocal ?: existing.isLocal,
                    webGrounded = estimate?.webGrounded ?: existing.webGrounded,
                    meal = meal,
                    notes = notes?.takeIf { it.isNotBlank() },
                    source = LogSource.MANUAL,
                )
            }
            if (!applied) return null
            requestCalorieTileUpdate(getApplication())
            container.sync.sync()
            // Read back the row we just wrote, so the caller never has to wait for the
            // Room flow to catch up before showing the new numbers.
            container.repository.find(id)
        } catch (e: Exception) {
            null
        } finally {
            _editBusy.value = false
        }
    }

    /** Deletes an entry and pushes the deletion to the phone. */
    suspend fun deleteEntry(id: Long) {
        _deleteBusy.value = true
        try {
            container.repository.delete(id)
            requestCalorieTileUpdate(getApplication())
            container.sync.sync()
        } catch (e: Exception) {
            // Nothing to recover locally; the row and its tombstone stay as they are.
        } finally {
            _deleteBusy.value = false
        }
    }

    /** Adapts a persisted entry back into the shape [FoodAnalyzer.reestimate] expects. */
    private fun FoodEntry.toAnalyzedItem(): AnalyzedItem = AnalyzedItem(
        name = foodName,
        canonicalName = canonicalName,
        portionText = portionText,
        grams = grams,
        kcal = kcal,
        kcalLow = kcalLow,
        kcalHigh = kcalHigh,
        confidence = confidence,
        isLocal = isLocal,
        webGrounded = webGrounded,
    )

    fun todayLabel(): String = JakartaTime.label(JakartaTime.todayKey())
}
