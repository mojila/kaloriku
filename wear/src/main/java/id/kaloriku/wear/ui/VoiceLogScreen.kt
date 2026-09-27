package id.kaloriku.wear.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * Each analyze state presents exactly one obvious primary action, with everything
 * else demoted beneath it, so the screen never offers two equally weighted choices
 * on a display this small. Analysis and microphone logic are untouched here.
 *
 * @param micGranted whether `RECORD_AUDIO` is currently granted. Speech is only
 *   auto-launched when it is; otherwise the typed fallback is shown immediately,
 *   since the platform speech activity would just fail or silently deny.
 * @param onRequestMic asks the activity to show the system mic permission prompt;
 *   its result comes back through [micGranted].
 */
@Composable
fun VoiceLogScreen(
    vm: WearViewModel,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice = remember { WearVoiceInput(context, scope) }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val analyzeState by vm.analyzeState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    var text by rememberSaveable { mutableStateOf("") }

    // Tracks whether speech has been started for this screen instance, so a grant
    // reported by the host later (or a config change) does not double-launch.
    var speechStarted by rememberSaveable { mutableStateOf(false) }

    // Owns the platform speech activity result and feeds it back into WearVoiceInput.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        voice.onActivityResult(result.resultCode, result.data)
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

    // Kick off voice capture once, and only with the microphone granted. On denial
    // the typed fallback is the immediate next step, with an explanation; the host
    // already asked for the permission before routing here.
    LaunchedEffect(Unit) {
        if (micGranted) {
            speechStarted = true
            launchVoice()
        }
    }
    // A grant can land after this screen is already showing (host prompt result, or
    // the retry action): start speech then, but never twice.
    LaunchedEffect(micGranted) {
        if (micGranted && !speechStarted) {
            speechStarted = true
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
        if (micGranted) {
            speechStarted = true
            launchVoice()
        } else {
            // Re-ask the host; its result comes back through `micGranted`.
            speechStarted = false
            onRequestMic()
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Extra bottom padding keeps the last action clear of the scaling list's
            // shrunk/clipped edge so its label stays fully legible.
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val state = analyzeState) {
                // ------------------------------------------------------------------
                // Ready: Jev answered. Save is the only filled action;
                // "Ulangi" is the escape hatch and sits below it, unfilled.
                // ------------------------------------------------------------------
                is WearAnalyzeState.Ready -> {
                    item {
                        Text(
                            text = "Perkiraan Jev",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "${state.result.totalKcal} kkal",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = state.result.meal.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.result.items.size) { index ->
                        val item = state.result.items[index]
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${item.kcal} kkal" +
                                        when {
                                            item.webGrounded -> " · dari web"
                                            item.isLocal -> " · lokal"
                                            else -> ""
                                        },
                                    style = MaterialTheme.typography.labelMedium,
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
                                style = MaterialTheme.typography.labelSmall,
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

                // ------------------------------------------------------------------
                // Error: one thing to do — try again. Close stays as a text button.
                // ------------------------------------------------------------------
                is WearAnalyzeState.Error -> {
                    item {
                        Text(
                            text = "Gagal menganalisa",
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                        )
                    }
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

                // ------------------------------------------------------------------
                // Running: nothing to press but the way out.
                // ------------------------------------------------------------------
                WearAnalyzeState.Running -> {
                    item { Spacer(Modifier.height(8.dp)) }
                    item {
                        CircularProgressIndicator()
                    }
                    item {
                        Text(
                            "Menganalisa dengan Jev…",
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

                // ------------------------------------------------------------------
                // Idle: listening or typing. The typed field leads, and whichever of
                // "Analisa" / "Catat suara" matches the situation is the filled one.
                // ------------------------------------------------------------------
                WearAnalyzeState.Idle -> {
                    val listening = voiceState.listening && voiceState.error == null
                    val hasError = voiceState.error != null || !micGranted

                    item {
                        if (listening) {
                            // An explicit listening state, with the spinner as the only
                            // motion on screen so the state change is unmistakable.
                            CircularProgressIndicator()
                        }
                    }
                    item {
                        // Status line: never a dead end — always explains the next step.
                        val status = voiceState.error
                            ?: when {
                                !micGranted -> "Izin mikrofon ditolak. Ketik makananmu."
                                listening -> "Mendengarkan…"
                                else -> "Ketik makananmu"
                            }
                        Text(
                            text = status,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (hasError) {
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

                    // Primary action depends on whether there is text to analyze. With
                    // text, analyzing is the point; without it, re-listening is.
                    if (text.isNotBlank()) {
                        item {
                            Button(
                                onClick = { analyzeNow() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Analisa") }
                        }
                        item {
                            OutlinedButton(
                                onClick = { voice.reset(); retryVoice() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (micGranted) "Catat suara lagi" else "Coba mikrofon lagi") }
                        }
                    } else {
                        item {
                            Button(
                                onClick = { voice.reset(); retryVoice() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (micGranted) "Catat suara lagi" else "Coba mikrofon lagi") }
                        }
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
