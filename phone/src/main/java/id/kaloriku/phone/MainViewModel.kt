package id.kaloriku.phone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import id.kaloriku.shared.KaloriKu
import id.kaloriku.shared.analysis.Insight
import id.kaloriku.shared.domain.AnalysisResult
import id.kaloriku.shared.domain.AnalyzedItem
import id.kaloriku.shared.domain.AppSettings
import id.kaloriku.shared.domain.DailySummary
import id.kaloriku.shared.domain.DayTotal
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.LogSource
import id.kaloriku.shared.domain.MealType
import id.kaloriku.shared.sync.SyncRole
import id.kaloriku.shared.sync.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for an in-progress or finished analysis. */
sealed interface AnalyzeState {
    data object Idle : AnalyzeState
    data object Running : AnalyzeState
    data class Ready(val result: AnalysisResult) : AnalyzeState
    data class Error(val message: String) : AnalyzeState
}

data class DashboardUi(
    val todayTotal: Int = 0,
    val target: Int = 2000,
    val todayEntries: List<FoodEntry> = emptyList(),
    val recent: List<FoodEntry> = emptyList(),
) {
    val remaining: Int get() = (target - todayTotal).coerceAtLeast(0)
    val progress: Float get() = if (target <= 0) 0f else (todayTotal.toFloat() / target).coerceIn(0f, 1f)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val container = KaloriKu.init(app, SyncRole.PHONE)

    val syncStatus: StateFlow<SyncStatus> = container.sync.status

    val settings: StateFlow<AppSettings> = container.repository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val todayEntries = container.repository.observeDay()

    val dashboard: StateFlow<DashboardUi> = combine(
        container.repository.observeDayTotal(),
        container.repository.observeDay(),
        container.repository.observeRecent(15),
        settings,
    ) { total, day, recent, prefs ->
        DashboardUi(
            todayTotal = total,
            target = prefs.dailyTargetKcal,
            todayEntries = day,
            recent = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUi())

    private val _analyzeState = MutableStateFlow<AnalyzeState>(AnalyzeState.Idle)
    val analyzeState: StateFlow<AnalyzeState> = _analyzeState.asStateFlow()

    /** The entry currently open in the edit sheet, or null when it is closed. */
    private val _editing = MutableStateFlow<FoodEntry?>(null)
    val editing: StateFlow<FoodEntry?> = _editing.asStateFlow()

    /** True while Jev re-estimates the calories of an edited entry. */
    private val _editSaving = MutableStateFlow(false)
    val editSaving: StateFlow<Boolean> = _editSaving.asStateFlow()

    /** Set when an edit could not be saved, so the UI can tell the user instead of failing silently. */
    private val _editError = MutableStateFlow<String?>(null)
    val editError: StateFlow<String?> = _editError.asStateFlow()

    fun clearEditError() {
        _editError.value = null
    }

    /** Set when a delete could not be applied, so the dashboard can report it. */
    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    fun clearDeleteError() {
        _deleteError.value = null
    }

    /**
     * Jev-decided macro profile per logged entry, keyed by local row id.
     *
     * The macro profile is transient by design: it lives on [AnalyzedItem] and is
     * deliberately not persisted to Room, so it cannot survive a process restart.
     * [FoodRepository.saveAnalysis] returns the new row ids in the same order as the
     * analyzed items, which lets the phone attach the Jev decision to the row it just
     * wrote for the rest of this app session.
     *
     * A row with no entry here simply renders no macro label; the absence of data is
     * not the same as Jev answering "tidak_jelas".
     */
    private val _macroProfiles = MutableStateFlow<Map<Long, String>>(emptyMap())
    val macroProfiles: StateFlow<Map<Long, String>> = _macroProfiles.asStateFlow()

    /**
     * Records the Jev macro decision for freshly saved rows.
     *
     * [entryIds] and [profiles] are positionally aligned: [FoodRepository.saveAnalysis]
     * inserts the analyzed items in order and returns their row ids in that same order.
     * A length mismatch (which should not happen) is handled by pairing only the
     * overlapping range rather than failing the save path.
     */
    private fun rememberMacroProfiles(entryIds: List<Long>, profiles: List<String>) {
        val additions = entryIds.zip(profiles) { id, profile -> id to profile }
        if (additions.isEmpty()) return
        _macroProfiles.value = _macroProfiles.value + additions.toMap()
    }

    /** Drops the transient macro decisions for rows that no longer exist. */
    private fun forgetMacroProfiles(entryIds: List<Long>) {
        if (entryIds.isEmpty()) return
        _macroProfiles.value = _macroProfiles.value - entryIds.toSet()
    }

    private val _trend = MutableStateFlow<List<DayTotal>>(emptyList())
    val trend: StateFlow<List<DayTotal>> = _trend.asStateFlow()

    private val _summaries = MutableStateFlow<List<DailySummary>>(emptyList())
    val summaries: StateFlow<List<DailySummary>> = _summaries.asStateFlow()

    private val _topFoods = MutableStateFlow<List<Pair<String, Int>>>(emptyList())
    val topFoods: StateFlow<List<Pair<String, Int>>> = _topFoods.asStateFlow()

    private val _insight = MutableStateFlow<Insight?>(null)
    val insight: StateFlow<Insight?> = _insight.asStateFlow()

    private val _insightLoading = MutableStateFlow(false)
    val insightLoading: StateFlow<Boolean> = _insightLoading.asStateFlow()

    init {
        // Stats are loaded imperatively, so refresh them whenever a sync merges
        // entries from the watch.
        viewModelScope.launch {
            var seenMerge = -1
            container.sync.status.collect { status ->
                val marker = status.lastMerged
                if (status.hasSynced && marker != seenMerge) {
                    seenMerge = marker
                    loadStats()
                }
            }
        }
    }

    /** Analyze an Indonesian transcript. */
    fun analyze(transcript: String, source: LogSource = LogSource.VOICE_PHONE) {
        if (transcript.isBlank()) return
        viewModelScope.launch {
            _analyzeState.value = AnalyzeState.Running
            try {
                val recent = container.repository.recent(5)
                val result = container.analyzer.analyze(transcript, source, existingRecent = recent)
                if (result.items.isEmpty()) {
                    _analyzeState.value = AnalyzeState.Error(
                        "Tidak ada makanan yang terdeteksi. Coba sebutkan nama makanan dan porsinya.",
                    )
                } else {
                    _analyzeState.value = AnalyzeState.Ready(result)
                }
            } catch (e: Exception) {
                _analyzeState.value = AnalyzeState.Error(e.message ?: "Analisa gagal.")
            }
        }
    }

    fun confirmAnalysis(source: LogSource = LogSource.VOICE_PHONE) {
        val ready = _analyzeState.value as? AnalyzeState.Ready ?: return
        viewModelScope.launch {
            val ids = container.repository.saveAnalysis(ready.result, source)
            rememberMacroProfiles(ids, ready.result.items.map { it.macroProfile })
            _analyzeState.value = AnalyzeState.Idle
            loadStats()
            // Push the new entries to the watch automatically.
            container.sync.sync()
        }
    }

    /** Debug-only: analyze and immediately persist, for automated on-device checks. */
    fun analyzeAndSave(transcript: String, source: LogSource = LogSource.VOICE_PHONE) {
        if (transcript.isBlank()) return
        viewModelScope.launch {
            _analyzeState.value = AnalyzeState.Running
            try {
                val recent = container.repository.recent(5)
                val result = container.analyzer.analyze(transcript, source, existingRecent = recent)
                if (result.items.isEmpty()) {
                    _analyzeState.value = AnalyzeState.Error("Tidak ada makanan yang terdeteksi.")
                } else {
                    val ids = container.repository.saveAnalysis(result, source)
                    rememberMacroProfiles(ids, result.items.map { it.macroProfile })
                    _analyzeState.value = AnalyzeState.Idle
                    loadStats()
                    container.sync.sync()
                }
            } catch (e: Exception) {
                _analyzeState.value = AnalyzeState.Error(e.message ?: "Analisa gagal.")
            }
        }
    }

    fun dismissAnalysis() {
        _analyzeState.value = AnalyzeState.Idle
    }

    /** Manual sync triggered by the "Sinkronkan sekarang" button. */
    fun syncNow() {
        viewModelScope.launch {
            container.sync.sync(manual = true)
        }
    }

    /** Automatic sync when the app returns to the foreground. */
    fun syncOnForeground() {
        viewModelScope.launch {
            container.sync.sync()
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch {
            try {
                container.repository.delete(id)
                forgetMacroProfiles(listOf(id))
                loadStats()
                // Push the tombstone to the watch so the deletion lands there too.
                container.sync.sync()
            } catch (e: Exception) {
                _deleteError.value = e.message ?: "Gagal menghapus catatan."
            }
        }
    }

    /**
     * Opens the edit sheet for [entry]. The sheet edits the user-facing fields only;
     * calorie re-estimation goes through [editEntry] so Jev stays the single source
     * of truth for the numbers.
     */
    fun startEdit(entry: FoodEntry) {
        _editError.value = null
        _editing.value = entry
    }

    fun cancelEdit() {
        _editing.value = null
    }

    /**
     * Applies an edit to the entry currently open in the sheet.
     *
     * When the user changed the food name or the portion, the calorie estimate is
     * re-asked from Jev via [FoodAnalyzer.reestimate]; changing only the meal or the
     * notes is a pure metadata edit and skips the network entirely.
     */
    fun editEntry(
        foodName: String,
        portionText: String,
        meal: MealType,
        notes: String,
    ) {
        val editing = _editing.value ?: return
        // Ignore a second save while the first is still running, so a double tap cannot
        // start two overlapping re-estimations for the same entry.
        if (_editSaving.value) return
        val name = foodName.trim()
        if (name.isBlank()) return
        val portion = portionText.trim()
        val trimmedNotes = notes.trim().ifBlank { null }

        viewModelScope.launch {
            var applied = false
            try {
                // Read the row fresh: the sheet holds a snapshot from when it opened.
                val current = container.repository.find(editing.id)
                if (current == null) {
                    // Deleted from the watch while the sheet was open. Nothing to save.
                    _editError.value = "Catatan ini sudah dihapus di perangkat lain."
                    return@launch
                }
                val needsReestimate =
                    !name.equals(current.foodName.trim(), ignoreCase = true) ||
                        portion != current.portionText.trim()

                _editSaving.value = needsReestimate
                val estimate = if (needsReestimate) {
                    container.analyzer.reestimate(
                        name = name,
                        portionText = portion,
                        grams = current.grams,
                        meal = meal,
                        previous = current.toAnalyzedItem(),
                    )
                } else {
                    null
                }
                applied = applyEdit(
                    id = current.id,
                    name = name,
                    portion = portion,
                    meal = meal,
                    notes = trimmedNotes,
                    estimate = estimate,
                )
                // A rename or a portion change re-asks Jev, so record the fresh macro
                // decision. A metadata-only edit (no estimate) leaves the row's macro
                // untouched, and an unknown row stays unknown rather than being guessed.
                if (applied && estimate != null) {
                    _macroProfiles.value = _macroProfiles.value + (current.id to estimate.macroProfile)
                }
                if (!applied) {
                    _editError.value = "Catatan ini sudah dihapus di perangkat lain."
                }
            } catch (e: Exception) {
                _editError.value = e.message ?: "Gagal menyimpan perubahan."
            } finally {
                _editSaving.value = false
                // Always release the sheet, even on the failure paths above, so the UI
                // can never be stuck on a save that did not happen.
                _editing.value = null
            }
            if (applied) {
                loadStats()
                // Make the edit reach the watch.
                container.sync.sync()
            }
        }
    }

    /**
     * Persists one edit revision. Fields the user did not change keep their live
     * values, so an edit made elsewhere in the meantime is not clobbered by a stale
     * snapshot of the sheet.
     *
     * @return true when the row still existed and was written.
     */
    private suspend fun applyEdit(
        id: Long,
        name: String,
        portion: String,
        meal: MealType,
        notes: String?,
        estimate: AnalyzedItem?,
    ): Boolean =
        container.repository.updateEntry(id) { entry ->
            entry.copy(
                foodName = name,
                portionText = portion,
                meal = meal,
                notes = notes,
                kcal = estimate?.kcal ?: entry.kcal,
                kcalLow = estimate?.kcalLow ?: entry.kcalLow,
                kcalHigh = estimate?.kcalHigh ?: entry.kcalHigh,
                confidence = estimate?.confidence ?: entry.confidence,
                isLocal = estimate?.isLocal ?: entry.isLocal,
                webGrounded = estimate?.webGrounded ?: entry.webGrounded,
                canonicalName = estimate?.canonicalName ?: entry.canonicalName,
                grams = estimate?.grams ?: entry.grams,
                source = LogSource.MANUAL,
            )
        }

    fun loadStats() {
        viewModelScope.launch {
            _trend.value = container.repository.trend(14)
            _summaries.value = container.repository.summaries(14)
            _topFoods.value = container.repository.topFoods(14, 6)
        }
    }

    /**
     * Generates an insight only when the view model does not already hold one.
     *
     * The Insights tab calls this on entry so re-opening it is free; the explicit
     * "Buat ulang analisis" button uses [generateInsight] to force a refresh.
     */
    fun generateInsightIfAbsent() {
        if (_insight.value == null && !_insightLoading.value) generateInsight()
    }

    fun generateInsight() {
        viewModelScope.launch {
            _insightLoading.value = true
            try {
                val target = settings.value.dailyTargetKcal
                val trend = container.repository.trend(14)
                val summaries = container.repository.summaries(14)
                _insight.value = container.insights.generate(target, trend, summaries)
            } catch (e: Exception) {
                _insight.value = Insight(
                    text = "Gagal membuat analisis: ${e.message ?: "coba lagi"}",
                    healthTrend = 0.0,
                    localShare = 0.0,
                    source = "error",
                )
            } finally {
                _insightLoading.value = false
            }
        }
    }

    fun setTarget(value: Int) = viewModelScope.launch {
        container.settingsStore.setDailyTarget(value)
    }

    fun clearAll() = viewModelScope.launch {
        // Tombstone rather than hard-delete, then push, so the watch clears its
        // copy too instead of keeping entries the phone no longer has.
        container.repository.deleteAllSynced()
        _macroProfiles.value = emptyMap()
        loadStats()
        container.sync.sync()
    }

    /** A few example Indonesian phrases for the empty state. */
    val examples = listOf(
        "Sarapan bubur ayam satu mangkuk sama kerupuk",
        "Siang makan nasi padang dengan rendang dan daun singkong",
        "Makan nasi goreng satu piring dan es teh manis",
        "Camilan pisang goreng dua biji sama kopi susu",
    )
}

/** Projects a stored entry back into the analysis shape Jev re-estimates against. */
private fun FoodEntry.toAnalyzedItem() = AnalyzedItem(
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
