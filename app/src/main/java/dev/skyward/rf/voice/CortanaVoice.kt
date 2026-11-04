package dev.skyward.rf.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.skyward.rf.core.Cortana
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * The microphone and the speaker behind [Cortana].
 *
 * Android's recogniser is single-shot: it listens for one utterance, answers, and stops. A wake
 * word needs the opposite - a loop that is always listening and throws away everything that is
 * not the name. So this restarts the recogniser after every result and every idle timeout, and
 * gates the commands behind the wake word with a short window in which "Cortana" is still fresh.
 *
 * While the speaker is talking the microphone is cancelled and left off: a recogniser that can
 * hear the phone speak will hear the wake word it just said and answer itself. Text-to-speech is
 * also allowed to be missing - on a phone with no engine the voice is silent but the interface
 * still answers, because every command path goes through the same handler either way.
 */
class CortanaVoice(private val context: Context) {

    data class State(
        val enabled: Boolean = false,
        /** The wake word was heard recently, so a bare command will be obeyed. */
        val awake: Boolean = false,
        val listening: Boolean = false,
        val speaking: Boolean = false,
        val lastHeard: String = "",
        val note: String = "",
    )

    val state = MutableStateFlow(State())

    /** A screen change asked for by voice; the UI reads it and clears it. */
    val nav = MutableStateFlow<Cortana.Nav?>(null)

    /** Where every parsed command goes. Set by the container, which owns the live state. */
    var onCommand: ((Cortana.Command) -> Unit)? = null

    /** The settings switch owns this: off means she still listens, she just does not answer. */
    @Volatile
    var audible: Boolean = true

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var speakerReady = false
    private var running = false
    private var awakeUntil = 0L
    private var utterance = 0
    private var lastSpokeAt = 0L

    /** How long a bare command is accepted after the wake word. */
    private val awakeWindowMs = 20_000L

    fun start() {
        if (running) return
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        if (!available) {
            state.update { it.copy(enabled = true, note = "Speech recognition is not available on this phone") }
            return
        }
        running = true
        state.update { it.copy(enabled = true, note = "") }
        main.post {
            runCatching {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context).also {
                    it.setRecognitionListener(listener)
                }
            }.onFailure {
                state.update { s -> s.copy(note = "Speech recognition could not start") }
            }
            prepareSpeaker()
            restart(150)
        }
    }

    fun stop() {
        val was = running
        running = false
        awakeUntil = 0L
        main.removeCallbacks(restartTask)
        main.removeCallbacks(watchdog)
        if (was) {
            main.post {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
                recognizer = null
                runCatching { speaker?.stop() }
                runCatching { speaker?.shutdown() }
                speaker = null
                speakerReady = false
            }
        }
        state.value = State()
    }

    /** Wake without speaking: what a tap on the pill does, and what "Cortana" is worth. */
    fun wake() {
        if (!state.value.enabled) return
        awakeUntil = SystemClock.elapsedRealtime() + awakeWindowMs
        state.update { it.copy(awake = true, note = "") }
        restart(0)
    }

    fun sleep() {
        awakeUntil = 0L
        state.update { it.copy(awake = false) }
    }

    fun speak(text: String, glitch: Boolean = false) {
        if (text.isBlank()) return
        val engine = speaker
        if (!running || !audible || engine == null || !speakerReady) {
            if (running) restart(400)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastSpokeAt < 120) return
        lastSpokeAt = now

        state.update { it.copy(speaking = true) }
        // Pause the microphone instead of letting it hear the reply.
        main.removeCallbacks(restartTask)
        runCatching { recognizer?.cancel() }

        // Cortana speaks high and quick; the watch alert drops two octaves and drags, which is
        // as close as a TTS engine gets to a corrupted fragment of her voice.
        runCatching { engine.setPitch(if (glitch) 0.45f else 1.16f) }
        runCatching { engine.setSpeechRate(if (glitch) 0.80f else 1.02f) }

        val id = "cortana-${utterance++}"
        val queued = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            state.update { it.copy(speaking = false) }
            restart(500)
            return
        }
        // Some engines never deliver the callback; this puts the listener back either way.
        main.postDelayed(watchdog, (1_400 + text.length * 65L).coerceAtMost(14_000L))
    }

    // ---------------- recogniser ----------------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            state.update { it.copy(listening = true) }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            state.update { it.copy(listening = false) }
        }

        override fun onError(error: Int) {
            state.update { it.copy(listening = false) }
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    state.update { it.copy(note = "Microphone permission missing") }
                    return
                }
                // ERROR_CLIENT is what a cancel produces, which is us pausing for speech.
                SpeechRecognizer.ERROR_CLIENT -> return
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    restart(1_200)
                    return
                }
            }
            // NO_MATCH and SPEECH_TIMEOUT are the idle state of a listener that never stops.
            restart(500)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = first(partialResults)
            if (heard.isBlank()) return
            // Only the wake word is acted on before the utterance is final, so the pill can light
            // up while the user is still speaking.
            if (Cortana.hasWake(heard)) {
                awakeUntil = SystemClock.elapsedRealtime() + awakeWindowMs
                state.update { it.copy(awake = true, lastHeard = heard.trim()) }
            }
        }

        override fun onResults(results: Bundle?) {
            state.update { it.copy(listening = false) }
            val heard = first(results)
            if (handleFinal(heard)) return
            restart(350)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun first(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /**
     * True when the utterance was worth acting on, in which case the caller does not restart the
     * listener - speaking does, or the next idle restart will.
     */
    private fun handleFinal(spoken: String): Boolean {
        if (spoken.isBlank()) return false
        state.update { it.copy(lastHeard = spoken.trim()) }

        val now = SystemClock.elapsedRealtime()
        val woke = Cortana.hasWake(spoken)
        if (!woke && now >= awakeUntil) return false

        awakeUntil = now + awakeWindowMs
        state.update { it.copy(awake = true) }

        val rest = Cortana.wakeOnly(spoken)
        if (rest.isBlank()) {
            if (woke) speak("Yes?")
            return true
        }

        val command = Cortana.parse(spoken)
        if (command == null) {
            speak("I did not catch a command.")
            return true
        }
        if (command == Cortana.Command.SLEEP) {
            sleep()
            speak("Going quiet.")
            return true
        }
        onCommand?.invoke(command)
        return true
    }

    private val restartTask = Runnable {
        if (!running) return@Runnable
        if (state.value.speaking) return@Runnable
        if (state.value.awake && SystemClock.elapsedRealtime() > awakeUntil) {
            state.update { it.copy(awake = false) }
        }
        listen()
    }

    private fun restart(delayMs: Long) {
        if (!running) return
        main.removeCallbacks(restartTask)
        main.postDelayed(restartTask, delayMs)
    }

    private fun listen() {
        val engine = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // A short silence ends the utterance: a wake word should not hold the microphone
            // open for the recogniser's default two seconds before the command is read.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        }
        runCatching { engine.startListening(intent) }.onFailure { restart(1_000) }
    }

    // ---------------- speaker ----------------

    private fun prepareSpeaker() {
        if (speaker != null) return
        speaker = TextToSpeech(context) { status ->
            speakerReady = status == TextToSpeech.SUCCESS
            if (speakerReady) {
                runCatching { speaker?.language = Locale.US }
                speaker?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            main.post { finishedSpeaking() }
                        }

                        @Deprecated("required by the platform", ReplaceWith(""))
                        override fun onError(utteranceId: String?) {
                            main.post { finishedSpeaking() }
                        }
                    },
                )
            } else {
                state.update { it.copy(note = "No text-to-speech engine on this phone") }
            }
        }
    }

    private val watchdog = Runnable {
        if (state.value.speaking) finishedSpeaking()
    }

    private fun finishedSpeaking() {
        main.removeCallbacks(watchdog)
        if (!state.value.speaking) return
        state.update { it.copy(speaking = false) }
        restart(300)
    }
}
