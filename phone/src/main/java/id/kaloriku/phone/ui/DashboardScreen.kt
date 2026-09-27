package id.kaloriku.phone.ui

import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.kaloriku.phone.AnalyzeState
import id.kaloriku.phone.MainViewModel
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.sync.SyncStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
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
                    modifier = Modifier.size(44.dp),
                )
                Column {
                    SectionLabel(JakartaTime.label(JakartaTime.todayKey()))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Kalori hari ini",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CalorieRing(progress = dashboard.progress, total = dashboard.todayTotal, target = dashboard.target)
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatBlock(
                        label = "sisa",
                        value = "${dashboard.remaining}",
                        modifier = Modifier.weight(1f),
                    )
                    StatBlock(
                        label = "catatan",
                        value = "${dashboard.todayEntries.size}",
                        modifier = Modifier.weight(1f),
                    )
                    StatBlock(
                        label = "makanan lokal",
                        value = "${dashboard.todayEntries.count { it.isLocal }}",
                        modifier = Modifier.weight(1f),
                    )
                }
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
                Text(
                    text = "Belum ada catatan. Tekan tombol di atas dan sebutkan apa yang kamu makan, " +
                        "misalnya \"sarapan bubur ayam satu mangkuk\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }
        } else {
            items(dashboard.todayEntries, key = { it.id }) { entry ->
                EntryRow(
                    entry = entry,
                    macroProfile = macroProfiles[entry.id],
                    onEdit = { vm.startEdit(entry) },
                    onDelete = { pendingDeleteId = entry.id },
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
            onSave = { foodName, portionText, meal, notes ->
                vm.editEntry(foodName, portionText, meal, notes)
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
private fun SyncRow(status: SyncStatus, onSync: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = onSync,
            enabled = !status.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (status.running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Menyinkronkan...")
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sinkronkan dengan jam")
            }
        }
        val hint = status.message
            ?: if (status.hasSynced) "Terakhir sinkron ${timeText(status.lastSuccessAt!!)}" else null
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(
    entry: FoodEntry,
    macroProfile: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.foodName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (macroProfile != null) {
                        Spacer(Modifier.width(8.dp))
                        MacroBadge(macroProfile)
                    }
                    if (entry.isEdited) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "diedit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(entry.meal.label)
                        if (entry.portionText.isNotBlank()) append(" · ${entry.portionText}")
                        append(" · ${timeText(entry.loggedAt)}")
                        if (entry.isLocal) append(" · lokal")
                        if (entry.webGrounded) append(" · dari web")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${entry.kcal} kkal",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (entry.kcalLow != entry.kcalHigh) {
                    Text(
                        text = "± ${entry.kcalLow}-${entry.kcalHigh}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Hapus ${entry.foodName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

private fun timeText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(JakartaTime.zone)
        .format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.forLanguageTag("id-ID")))

@Composable
fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}
