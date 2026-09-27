package id.kaloriku.wear.voice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Watch-side voice input built on the platform speech activity
 * ([RecognizerIntent.ACTION_RECOGNIZE_SPEECH]).
 *
 * The direct [SpeechRecognizer] recognizer API is deliberately NOT used: on some
 * Wear devices the only advertised recognition service is a TTS-engine stub, so
 * the recognition listener callbacks never fire and the session hangs forever
 * (no ready-for-speech, no error). Launching the platform activity instead
 * engages the real microphone, and the activity owner (the composable) supplies
 * the [androidx.activity.result.ActivityResultLauncher].
 */
class WearVoiceInput(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    data class State(
        val listening: Boolean = false,
        val partial: String = "",
        val finalText: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** True when some activity can handle ACTION_RECOGNIZE_SPEECH on this device. */
    val isAvailable: Boolean
        get() = context.packageManager
            .queryIntentActivities(recognitionIntent(), 0)
            .isNotEmpty()

    private var timeoutJob: Job? = null

    /** The intent to hand to an ActivityResultLauncher. Indonesian, free-form, up to 3 results. */
    fun recognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, LANGUAGE_TAG)
        putExtra(RecognizerIntent.EXTRA_PROMPT, PROMPT)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
    }

    /**
     * Marks the listening state and arms the hard timeout. Call right after the
     * launcher is dispatched so the screen cannot hang silently.
     */
    fun onLaunched() {
        _state.value = State(listening = true)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(TIMEOUT_MS)
            if (_state.value.listening) {
                _state.value = State(error = "Waktu habis. Coba lagi atau ketik manual.")
            }
        }
    }

    /** Feeds the platform activity result back in. Blank / canceled is a soft cancel, not an error. */
    fun onActivityResult(resultCode: Int, data: Intent?) {
        timeoutJob?.cancel()
        timeoutJob = null

        if (resultCode != Activity.RESULT_OK) {
            // The user backed out of the speech activity. Say so briefly instead of
            // leaving the screen wordless; the typed field below is still the next step.
            _state.value = State(error = CANCELLED)
            return
        }
        val text = extractTranscript(data)
        _state.value = if (text.isBlank()) {
            // RESULT_OK but nothing usable: soft cancel so the user can retry or type.
            State(error = CANCELLED)
        } else {
            State(partial = text, finalText = text)
        }
    }

    /**
     * Pulls the transcript out of a speech-activity result.
     *
     * Handlers differ: AOSP uses [SpeechRecognizer.RESULTS_RECOGNITION] while the Wear
     * keyboard activity can return [RecognizerIntent.EXTRA_RESULTS] or [Intent.EXTRA_TEXT],
     * sometimes nested in a pending-intent bundle. The known keys are probed in order and
     * the nested bundle is unwrapped so a valid transcript is never dropped.
     */
    private fun extractTranscript(data: Intent?): String {
        if (data == null) return ""
        val keys = listOf(
            SpeechRecognizer.RESULTS_RECOGNITION,
            RecognizerIntent.EXTRA_RESULTS,
            Intent.EXTRA_TEXT,
        )
        for (key in keys) {
            data.getStringArrayListExtra(key)
                ?.firstOrNull { it.isNotBlank() }
                ?.let { return it.trim() }
            data.getStringExtra(key)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.trim() }
        }
        val nested = data.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE)
        for (key in keys) {
            nested?.getStringArrayList(key)
                ?.firstOrNull { it.isNotBlank() }
                ?.let { return it.trim() }
            nested?.getString(key)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.trim() }
        }
        return ""
    }

    /** Reports that the platform speech activity could not be resolved/launched. */
    fun markUnavailable() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = State(error = "Pengenalan suara tidak tersedia. Ketik manual.")
    }

    /** Clears any error/result so a fresh capture can start from a clean slate. */
    fun reset() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = State()
    }

    /** No-op state cleanup; nothing platform-level is held open by this class. */
    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = _state.value.copy(listening = false)
    }

    private companion object {
        const val LANGUAGE_TAG = "id-ID"
        const val PROMPT = "Sebutkan makananmu"
        const val MAX_RESULTS = 3
        const val TIMEOUT_MS = 20_000L
        /** Shown when the user backs out of the speech activity with nothing captured. */
        const val CANCELLED = "Dibatalkan. Ketik manual atau coba lagi."
    }
}
