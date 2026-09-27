package id.kaloriku.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import id.kaloriku.wear.WearAnalyzeState
import id.kaloriku.wear.WearViewModel
import id.kaloriku.wear.voice.WearVoiceInput

/**
 * Voice logging on the watch: launch the platform speech activity, analyze with
 * Jev, confirm, save. A typed fallback is always available so logging still works
 * when no usable speech recognizer is installed.
 *
 * @param micGranted whether `RECORD_AUDIO` is currently granted. Speech is only
 *   auto-launched when it is; otherwise the typed fallback is shown immediately,
 *   since the platform speech activity would just fail or silently deny.
 * @param onMicResult receives a fresh permission result. The screen requests the
 *   permission itself when it can still prompt, so a grant re-enables voice here
 *   without the user having to leave and re-enter.
 */
@Composable
fun VoiceLogScreen(
    vm: WearViewModel,
    micGranted: Boolean,
    onMicResult: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { WearVoiceInput(context, scope) }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val analyzeState by vm.analyzeState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    var text by rememberSaveable { mutableStateOf("") }

    // Live permission state: starts from the host's value, but the screen owns the
    // request so a grant made here takes effect immediately. Survives config change.
    var micAllowed by rememberSaveable { mutableStateOf(micGranted) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }

    // Owns the platform speech activity result and feeds it back into WearVoiceInput.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        voice.onActivityResult(result.resultCode, result.data)
    }

    // Owns the mic permission request so the voice route can recover from a denial.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micAllowed = granted
        onMicResult(granted)
    }

    fun launchVoice() {
        val intent = voice.recognitionIntent()
        // Guard again at launch time: a missing handler would otherwise throw.
        if (intent.resolveActivity(context.packageManager) == null) {
            voice.markUnavailable()
            return
        }
        // Only arm the listening state and timeout when the launch actually succeeded,
        // otherwise a failure would be overwritten by a fake "Mendengarkan..." and a
        // 20s wait.
        runCatching { launcher.launch(intent) }
            .onSuccess { voice.onLaunched() }
            .onFailure { voice.markUnavailable() }
    }

    // Kick off voice capture once on entry, but only with the microphone granted.
    // On denial the typed fallback is the immediate next step, with an explanation.
    LaunchedEffect(Unit) {
        if (micAllowed) {
            launchVoice()
            return@LaunchedEffect
        }
        // Ask once; the result updates `micAllowed` and either path stays usable.
        if (permissionAsked) return@LaunchedEffect
        permissionAsked = true
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            micAllowed = true
            onMicResult(true)
            launchVoice()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Re-launch speech only when a permission grant lands after the screen is up, i.e.
    // on a false -> true transition. Keying on the first composition too would double
    // launch alongside the effect above.
    var wasMicAllowed by rememberSaveable { mutableStateOf(micGranted) }
    LaunchedEffect(micAllowed) {
        val becameAllowed = micAllowed && !wasMicAllowed
        wasMicAllowed = micAllowed
        if (becameAllowed && !voiceState.listening && voiceState.finalText.isNullOrBlank()) {
            launchVoice()
        }
    }
    LaunchedEffect(voiceState.partial) {
        if (voiceState.partial.isNotBlank()) text = voiceState.partial
    }
    LaunchedEffect(voiceState.finalText) {
        val final = voiceState.finalText
        if (!final.isNullOrBlank()) {
            text = final
            voice.stop()
        }
    }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    fun analyzeNow() {
        if (text.isNotBlank()) {
            voice.stop()
            vm.analyze(text)
        }
    }

    /**
     * Restarts capture from scratch: drops the previous analysis and transcript so the
     * field is empty, then re-opens the platform speech activity. Used by the retry
     * actions so "Ulangi" never leaves the user staring at a stale transcript.
     */
    fun retryVoice() {
        vm.dismiss()
        text = ""
        voice.reset()
        if (micAllowed) {
            launchVoice()
        } else if (!permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            // Already denied once: do not nag, just leave the typed field in focus.
            voice.markUnavailable()
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Extra bottom padding keeps the last action clear of the scaling list's
            // shrunk/clipped edge so its label stays fully legible.
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val state = analyzeState) {
                is WearAnalyzeState.Ready -> {
                    item {
                        Text(
                            text = "${state.result.totalKcal} kkal",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    item {
                        Text(
                            text = state.result.meal.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.result.items.size) { index ->
                        val item = state.result.items[index]
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${item.kcal} kkal" +
                                        when {
                                            item.webGrounded -> " · dari web"
                                            item.isLocal -> " · lokal"
                                            else -> ""
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // The dominant macro is a Jev decision carried transiently on
                                // the analysed item; it is shown only here at confirm time,
                                // because a persisted entry keeps no macro data.
                                macroLabel(item.macroProfile)?.let { label ->
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            }
                        }
                    }
                    if (state.result.isDuplicate) {
                        item {
                            Text(
                                text = "Mirip catatan terakhir. Periksa agar kalori tidak dobel.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    if (state.result.usedWebGrounding) {
                        item {
                            Text(
                                text = "Kalori makanan baru dicari dari web.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    item {
                        Button(onClick = { vm.confirm(); onClose() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Simpan")
                        }
                    }
                    // Full-width like the other secondary actions: a compact TextButton at
                    // the list edge gets shrunk and clipped by the scaling list.
                    item {
                        OutlinedButton(
                            onClick = { retryVoice() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Ulangi") }
                    }
                }

                is WearAnalyzeState.Error -> {
                    item {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }
                    item {
                        Button(
                            onClick = { retryVoice() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Coba lagi") }
                    }
                    item { TextButton(onClick = onClose) { Text("Tutup") } }
                }

                WearAnalyzeState.Running -> {
                    item {
                        CircularProgressIndicator()
                    }
                    item {
                        Text(
                            "Menganalisa dengan Jev...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    // The analysis waits on the Jev network call; without this the user
                    // is stuck on a spinner with no way out. Batal only dismisses the
                    // in-flight result locally, it cannot cancel the shared request.
                    item {
                        TextButton(onClick = { vm.dismiss(); onClose() }) { Text("Batal") }
                    }
                }

                WearAnalyzeState.Idle -> {
                    // Status line: never a dead end — always explains the next step.
                    item {
                        val status = voiceState.error
                            ?: when {
                                !micAllowed -> "Izin mikrofon ditolak. Ketik makananmu."
                                voiceState.listening -> "Mendengarkan..."
                                else -> "Ketik manual"
                            }
                        Text(
                            text = status,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (voiceState.error != null || !micAllowed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            textAlign = TextAlign.Center,
                        )
                    }

                    item {
                        WatchTextField(
                            label = "Ketik makananmu",
                            value = text,
                            onValueChange = { text = it },
                            placeholder = "nasi goreng satu piring",
                            onDone = { analyzeNow() },
                        )
                    }

                    item {
                        Button(
                            onClick = { analyzeNow() },
                            enabled = text.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Analisa") }
                    }

                    item {
                        OutlinedButton(
                            onClick = { voice.reset(); retryVoice() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (micAllowed) "Catat suara lagi" else "Coba mikrofon lagi") }
                    }

                    item { TextButton(onClick = onClose) { Text("Batal") } }
                }
            }
        }
    }
}

/**
 * Human-readable Indonesian label for a Jev-decided macro profile, or null when Jev
 * could not tell (or the value is outside the known set). Unknown values are hidden
 * rather than shown raw, so the watch never displays a machine token.
 */
private fun macroLabel(profile: String): String? = when (profile) {
    "karbohidrat" -> "Karbo"
    "protein" -> "Protein"
    "lemak" -> "Lemak"
    "serat" -> "Serat"
    "seimbang" -> "Seimbang"
    else -> null
}
