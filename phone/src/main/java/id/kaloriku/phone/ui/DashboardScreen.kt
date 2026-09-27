package id.kaloriku.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.kaloriku.phone.AnalyzeState
import id.kaloriku.phone.MainViewModel
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime

@Composable
fun DashboardScreen(vm: MainViewModel, onRequestMic: () -> Boolean) {
    val dashboard by vm.dashboard.collectAsStateWithLifecycle()
    val analyzeState by vm.analyzeState.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val editSaving by vm.editSaving.collectAsStateWithLifecycle()
    val editError by vm.editError.collectAsStateWithLifecycle()
    val deleteError by vm.deleteError.collectAsStateWithLifecycle()
    val macroProfiles by vm.macroProfiles.collectAsStateWithLifecycle()
    // Saveable so rotation does not silently drop the sheet or a pending confirmation.
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var micDenied by rememberSaveable { mutableStateOf(false) }
    // Only the id is stored, so the state stays saveable across process death.
    var pendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // An edit that could not be saved must be reported, never dropped silently.
    LaunchedEffect(editError) {
        val message = editError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            vm.clearEditError()
        }
    }
    // Same rule for deletes: a failure is surfaced instead of being swallowed.
    LaunchedEffect(deleteError) {
        val message = deleteError
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            vm.clearDeleteError()
        }
    }
    // The sheet is shown while voice logging is open, or whenever an analysis is
    // in flight so its result is never silently lost.
    val sheetVisible = showVoice || analyzeState !is AnalyzeState.Idle
    // A finished-but-unsaved analysis would be lost by a back press / swipe, so it is
    // confirmed first. An empty sheet or an in-flight run dismisses immediately.
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    // A back press must not discard a finished analysis in a single tap. The sheet owns
    // back while it is visible: dismiss it deliberately (with a light confirmation when
    // there is a result to lose).
    BackHandler(enabled = sheetVisible) {
        if (analyzeState is AnalyzeState.Ready) {
            confirmDiscard = true
        } else {
            showVoice = false
            micDenied = false
            vm.dismissAnalysis()
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Buang hasil analisa?") },
            text = { Text("Hasil analisa belum disimpan dan akan hilang.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        showVoice = false
                        micDenied = false
                        vm.dismissAnalysis()
                    },
                ) { Text("Buang") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Batal") }
            },
        )
    }

    val overTarget = dashboard.todayTotal > dashboard.target
    val mealGroups = groupByMeal(dashboard.todayEntries)

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(id.kaloriku.phone.R.drawable.ic_logo),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    Column {
                        SectionLabel(JakartaTime.label(JakartaTime.todayKey()))
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Kalori hari ini",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                }
            }

            // The day's progress is the focal point: one card, one ring, one band of
            // numbers. Everything below it is secondary by comparison.
            item {
                SurfaceCard(contentPadding = 22.dp) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CalorieRing(
                            progress = dashboard.progress,
                            total = dashboard.todayTotal,
                            target = dashboard.target,
                        )
                        Spacer(Modifier.height(22.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Metric(
                                value = "${dashboard.remaining}",
                                label = "sisa kkal",
                                modifier = Modifier.weight(1f),
                            )
                            Metric(
                                value = "${dashboard.todayEntries.size}",
                                label = "catatan",
                                modifier = Modifier.weight(1f),
                            )
                            Metric(
                                value = "${dashboard.todayEntries.count { it.isLocal }}",
                                label = "lokal",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // An over-target day is stated plainly rather than being hidden behind a
            // ring that simply stops filling at 100%.
            if (overTarget) {
                item {
                    Callout(
                        text = "Sudah ${dashboard.todayTotal - dashboard.target} kkal di atas " +
                            "target ${dashboard.target} kkal hari ini.",
                        emphasis = true,
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        micDenied = !onRequestMic()
                        showVoice = true
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Catat dengan suara", style = MaterialTheme.typography.titleMedium)
                }
            }

            item {
                SyncRow(status = syncStatus, onSync = { vm.syncNow() })
            }

            item {
                SectionLabel("Catatan hari ini")
            }

            if (dashboard.todayEntries.isEmpty()) {
                item {
                    Callout(
                        text = "Belum ada catatan. Sebutkan apa yang kamu makan, misalnya " +
                            "\"sarapan bubur ayam satu mangkuk\".",
                    )
                }
            } else {
                // Grouped by meal, each with its own subtotal, so the shape of the day
                // is readable without adding the rows up by hand.
                items(mealGroups, key = { it.meal.name }) { group ->
                    MealSection(
                        group = group,
                        macroProfiles = macroProfiles,
                        onEdit = { vm.startEdit(it) },
                        onDelete = { pendingDeleteId = it.id },
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (sheetVisible) {
        VoiceLogSheet(
            vm = vm,
            micDenied = micDenied,
            onDismiss = {
                // Swiping the sheet away must not silently drop a finished analysis.
                if (analyzeState is AnalyzeState.Ready) {
                    confirmDiscard = true
                } else {
                    showVoice = false
                    micDenied = false
                    vm.dismissAnalysis()
                }
            },
        )
    }

    editing?.let { entry ->
        EditEntrySheet(
            entry = entry,
            macroProfile = macroProfiles[entry.id],
            saving = editSaving,
            onSave = { foodName, portionText, meal, notes, dayKey, hour, minute ->
                vm.editEntry(foodName, portionText, meal, notes, dayKey, hour, minute)
            },
            onDismiss = { if (!editSaving) vm.cancelEdit() },
        )
    }

    // Resolve the id back to the live entry so the dialog survives rotation while
    // still describing the current row.
    val pendingDelete = pendingDeleteId?.let { id -> dashboard.todayEntries.firstOrNull { it.id == id } }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Hapus catatan ini?") },
            text = { Text("\"${entry.foodName}\" akan dihapus dari catatan hari ini dan tidak bisa dikembalikan.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteEntry(entry.id)
                        pendingDeleteId = null
                    },
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Batal") }
            },
        )
    }
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}
