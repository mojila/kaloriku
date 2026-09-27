package id.kaloriku.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.kaloriku.phone.AnalyzeState
import id.kaloriku.phone.MainViewModel
import id.kaloriku.phone.voice.VoiceInputController
import id.kaloriku.shared.domain.AnalyzedItem
import id.kaloriku.shared.domain.LogSource

/**
 * Bottom sheet for voice logging: live transcript, examples, and a Jev analysis
 * preview the user confirms before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceLogSheet(vm: MainViewModel, micDenied: Boolean = false, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val controller = remember { VoiceInputController(context) }
    val voiceState by controller.state.collectAsStateWithLifecycle()
    val analyzeState by vm.analyzeState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Saveable so rotation and process death keep whatever the user typed.
    var text by rememberSaveable { mutableStateOf("") }
    // Set once the user edits the field by hand, so a late partial result can no
    // longer clobber their typing.
    var userEdited by rememberSaveable { mutableStateOf(false) }
    var autoStarted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!autoStarted && !micDenied && analyzeState is AnalyzeState.Idle) {
            autoStarted = true
            controller.start { }
        }
    }
    // Only mirror partials while listening and while the user has not taken over the
    // field themselves.
    LaunchedEffect(voiceState.partial, voiceState.listening) {
        if (voiceState.listening && !userEdited && voiceState.partial.isNotBlank()) {
            text = voiceState.partial
        }
    }
    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Catat makanan", style = MaterialTheme.typography.titleLarge)

            // Honest permission state: never a fake "listening" indicator when the
            // user refused the microphone.
            if (micDenied) {
                Text(
                    text = "Izin mikrofon ditolak. Ketik makananmu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            when (val state = analyzeState) {
                is AnalyzeState.Ready -> {
                    AnalysisPreview(state, onSave = { vm.confirmAnalysis(LogSource.VOICE_PHONE) }, onRetry = {
                        vm.dismissAnalysis()
                    })
                }
                is AnalyzeState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (!micDenied) {
                        VoiceCaptureControls(controller, voiceState.listening, voiceState.error)
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            userEdited = true
                        },
                        label = { Text("Atau tulis di sini") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.dismissAnalysis() }) { Text("Batal") }
                        androidx.compose.material3.Button(
                            onClick = { vm.analyze(text) },
                            enabled = text.isNotBlank(),
                        ) { Text("Analisa ulang") }
                    }
                }
                AnalyzeState.Running -> {
                    Text(
                        text = "Menganalisa dengan Jev...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                AnalyzeState.Idle -> {
                    if (!micDenied) {
                        VoiceCaptureControls(controller, voiceState.listening, voiceState.error)
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            userEdited = true
                        },
                        label = { Text("Tulis makanan (opsional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                    if (text.isBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Contoh kalimat:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            vm.examples.forEach { example ->
                                TextButton(
                                    onClick = { text = example },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                                ) {
                                    Text(
                                        example,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Batal") }
                        androidx.compose.material3.Button(
                            onClick = {
                                controller.stop()
                                vm.analyze(text)
                            },
                            enabled = text.isNotBlank(),
                        ) { Text("Analisa kalori") }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceCaptureControls(
    controller: VoiceInputController,
    listening: Boolean,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.IconButton(
                onClick = {
                    if (listening) controller.stop() else controller.start { }
                },
                modifier = Modifier
                    .height(48.dp),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = "Mikrofon")
            }
            Text(
                text = if (listening) "Mendengarkan... sebutkan makananmu" else "Tekan mikrofon untuk berbicara",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AnalysisPreview(
    state: AnalyzeState.Ready,
    onSave: () -> Unit,
    onRetry: () -> Unit,
) {
    val result = state.result
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "\"${result.transcript}\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatBlock(label = "total", value = "${result.totalKcal} kkal", modifier = Modifier.weight(1f))
            StatBlock(label = "rentang", value = "${result.totalLow}-${result.totalHigh}", modifier = Modifier.weight(1f))
            StatBlock(label = "waktu", value = result.meal.label, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(2.dp))
        result.items.forEach { item -> ItemRow(item) }
        if (result.isDuplicate) {
            Text(
                text = "Catatan ini mirip dengan yang baru saja kamu simpan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (result.usedWebGrounding) {
            Text(
                text = "Sebagian makanan tidak ada di katalog lokal, jadi kalorinya dicari dari web lalu dinilai Jev.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRetry) { Text("Ulangi") }
            androidx.compose.material3.Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text("Simpan")
            }
        }
    }
}

@Composable
private fun ItemRow(item: AnalyzedItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                MacroBadge(item.macroProfile)
            }
            Text(
                text = buildString {
                    if (item.portionText.isNotBlank()) append(item.portionText)
                    if (item.isLocal) {
                        if (isNotEmpty()) append(" · ")
                        append("lokal")
                    }
                    if (item.webGrounded) {
                        if (isNotEmpty()) append(" · ")
                        append("dari web")
                    }
                    if (item.needsClarification) {
                        if (isNotEmpty()) append(" · ")
                        append("perlu dicek")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${item.kcal} kkal", style = MaterialTheme.typography.titleMedium)
    }
}
