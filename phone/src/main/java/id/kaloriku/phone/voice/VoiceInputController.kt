package id.kaloriku.phone.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Thin wrapper around Android's on-device [SpeechRecognizer] tuned for Indonesian.
 * The UI observes [state] and reads [partial] for live feedback.
 */
class VoiceInputController(private val context: Context) {

    data class State(
        val listening: Boolean = false,
        val partial: String = "",
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /**
     * Identity token for the live recognition session. Callbacks check it before
     * mutating [state] so a recognizer that was already destroyed by [stop] can no
     * longer write stale results over a newer session (or over typing).
     */
    private var session: Any? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onResult: (String) -> Unit) {
        // A second tap while listening must not create a parallel recognizer that
        // also writes to the same state flow.
        if (recognizer != null) return
        if (!isAvailable) {
            _state.value = State(error = "Pengenalan suara tidak tersedia di perangkat ini.")
            return
        }
        // Clear any leftover "listening" flag from a session that ended without a
        // matching callback before starting fresh.
        _state.value = State()

        val token = Any()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        session = token
        sr.setRecognitionListener(object : RecognitionListener {
            /** True when this callback still belongs to the live recognizer. */
            fun current(): Boolean = session === token

            override fun onReadyForSpeech(params: Bundle?) {
                if (!current()) return
                _state.value = State(listening = true)
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!current()) return
                _state.value = _state.value.copy(listening = false)
            }

            override fun onError(error: Int) {
                if (!current()) return
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Suara tidak terdeteksi. Coba lagi."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Tidak ada suara. Coba lagi."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Koneksi bermasalah saat mengenali suara."
                    else -> "Gagal mengenali suara (kode $error)."
                }
                _state.value = State(error = message)
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                _state.value = State(partial = text)
                if (text.isNotBlank()) onResult(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) _state.value = _state.value.copy(partial = text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "id-ID")
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        runCatching { sr.startListening(intent) }
            .onFailure {
                if (session === token) {
                    _state.value = State(error = "Tidak bisa memulai mikrofon: ${it.message}")
                }
            }
    }

    fun stop() {
        val sr = recognizer
        if (sr != null) {
            // Detach the listener first so no callback from the dying recognizer can
            // land after its session token has been retired.
            runCatching { sr.setRecognitionListener(null) }
            runCatching { sr.stopListening() }
            runCatching { sr.cancel() }
            runCatching { sr.destroy() }
        }
        recognizer = null
        session = null
        _state.value = _state.value.copy(listening = false)
    }

    companion object {
        fun locale(): Locale = Locale.forLanguageTag("id-ID")
    }
}
