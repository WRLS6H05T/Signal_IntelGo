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
import android.speech.tts.Voice
import dev.skyward.rf.core.VoiceStyle
import dev.skyward.rf.core.VoiceStyles
import dev.skyward.rf.core.Watcher
import dev.skyward.rf.core.WatcherGender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * The microphone and the speaker behind [Watcher].
 *
 * It is push-to-talk, not a wake-word listener: a recogniser session starts only when the status
 * box under the clock is tapped, takes one utterance, does what was asked, says what it has to
 * say, and goes quiet. Nothing holds the microphone between taps - a wardriving app has no
 * business listening to a car all day, and a permanent listener is also a permanent battery cost
 * and a permanent permission.
 *
 * One command per tap, so a session is also one chance: if the recogniser hears nothing, the box
 * says so and waits for the next tap. While the speaker is talking the microphone is cancelled and
 * left off, because a recogniser that can hear the phone speak will answer itself. Text-to-speech
 * is allowed to be missing - on a phone with no engine the voice is silent but the interface still
 * answers, because every command path goes through the same handler either way.
 */
class WatcherVoice(private val context: Context) {

    data class State(
        val enabled: Boolean = false,
        val listening: Boolean = false,
        val speaking: Boolean = false,
        val lastHeard: String = "",
        val note: String = "",
    )

    val state = MutableStateFlow(State())

    /** A screen change asked for by voice; the UI reads it and clears it. */
    val nav = MutableStateFlow<Watcher.Nav?>(null)

    /** Where every parsed command goes. Set by the container, which owns the live state. */
    var onCommand: ((Watcher.Command) -> Unit)? = null

    /** The settings switch owns this: off means it will not answer at all. */
    @Volatile
    var audible: Boolean = true

    /** Which machine voice speaks; the settings picker owns this. */
    @Volatile
    var style: VoiceStyle = VoiceStyles.of("female")

    private val main = Handler(Looper.getMainLooper())
    /** The channel: squelch either side of every transmission, and the radio's own call framing. */
    private val radio by lazy { RadioChatter(context) }
    private var recognizer: SpeechRecognizer? = null
    private var speaker: TextToSpeech? = null
    private var speakerReady = false
    private var enabled = false
    private var session = false
    private var utterance = 0
    private var lastSpokeAt = 0L

    /** Create the recogniser and the speaker. Nothing is listened to until [wake]. */
    fun start() {
        if (enabled) return
        val available = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        if (!available) {
            state.update { it.copy(enabled = true, note = "Speech recognition is not available on this phone") }
            return
        }
        enabled = true
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
        }
    }

    fun stop() {
        val was = enabled
        enabled = false
        session = false
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

    /** One listening session, started by a tap on the status box. */
    fun wake() {
        if (!enabled || session) return
        if (state.value.speaking) return
        session = true
        state.update { it.copy(listening = true, note = "", lastHeard = "") }
        main.post { listen() }
    }

    fun speak(text: String, glitch: Boolean = false, intel: Boolean = false) {
        if (text.isBlank()) return
        val engine = speaker
        if (!enabled || !audible || engine == null || !speakerReady) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSpokeAt < 120) return
        lastSpokeAt = now

        val voice = style
        // Every line goes out as a radio call, not a phone notification: the words are addressed
        // to the net and closed with "over", and a squelch opens the channel before the engine
        // starts and closes it afterwards.
        val line = Radio.frame(voice.render(text, glitch), alert = glitch, intel = intel)

        state.update { it.copy(speaking = true) }
        runCatching { recognizer?.cancel() }
        radio.open(alert = glitch)

        // Pitch and rate are the performance the engine actually offers; the glitched alert drags
        // below whichever voice is chosen.
        runCatching { engine.setPitch(if (glitch) voice.alertPitch else voice.pitch) }
        runCatching { engine.setSpeechRate(if (glitch) voice.alertRate else voice.rate) }
        applyLocale(engine, voice)

        val id = "watcher-${utterance++}"
        val queued = runCatching {
            engine.speak(line, TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            state.update { it.copy(speaking = false) }
            return
        }
        // Some engines never deliver the callback; this clears the speaking state either way.
        main.postDelayed(watchdog, (1_400 + line.length * 65L).coerceAtMost(14_000L))
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
                    session = false
                    state.update { it.copy(note = "Microphone permission missing") }
                    return
                }
                // ERROR_CLIENT is what a cancel produces, which is us pausing for speech.
                SpeechRecognizer.ERROR_CLIENT -> return
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    session = false
                    state.update { it.copy(note = "Recogniser busy - tap to try again") }
                    return
                }
            }
            // NO_MATCH and SPEECH_TIMEOUT mean the tap produced nothing. The session is over:
            // there is no always-on loop to restart, by design.
            session = false
            state.update {
                it.copy(
                    listening = false,
                    note = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        "No command heard - tap to talk"
                    } else {
                        "Recogniser error - tap to try again"
                    },
                )
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = first(partialResults)
            if (heard.isNotBlank()) state.update { it.copy(lastHeard = heard.trim()) }
        }

        override fun onResults(results: Bundle?) {
            state.update { it.copy(listening = false) }
            session = false
            handleFinal(first(results))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun first(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    /** One utterance, one command. Anything that is answered speaks; everything else is silent. */
    private fun handleFinal(spoken: String) {
        if (spoken.isBlank()) {
            state.update { it.copy(note = "No command heard - tap to talk") }
            return
        }
        state.update { it.copy(lastHeard = spoken.trim()) }

        val command = Watcher.parse(spoken)
        if (command == null) {
            speak("I did not catch a command.")
            return
        }
        if (command == Watcher.Command.SLEEP) {
            speak("Standing down.")
            return
        }
        onCommand?.invoke(command)
    }

    private fun listen() {
        val engine = recognizer ?: run {
            session = false
            state.update { it.copy(listening = false, note = "Speech recognition could not start") }
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // A short silence ends the utterance: a tap is a command, not a dictation session.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        }
        runCatching { engine.startListening(intent) }.onFailure {
            session = false
            state.update { it.copy(listening = false, note = "Could not open the microphone") }
        }
    }

    // ---------------- speaker ----------------

    /**
     * The base language is the engine's own; the voice is pitched in [speak]. A missing language
     * falls back to en-US rather than leaving the engine mute.
     */
    private fun applyLocale(engine: TextToSpeech, voice: VoiceStyle) {
        val wanted = runCatching { Locale.forLanguageTag(voice.locale) }.getOrDefault(Locale.US)
        val available = runCatching { engine.isLanguageAvailable(wanted) }
            .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        runCatching {
            engine.language = if (available >= TextToSpeech.LANG_AVAILABLE) wanted else Locale.US
        }
        // The pitch is the main instrument, but a man should use a man's engine voice when the
        // phone has one installed: pitching a woman's recordings down far enough is what made
        // the old male preset sound like a machine impersonating one.
        runCatching { pickVoice(engine, voice)?.let { engine.voice = it } }
    }

    /**
     * The deepest installed voice for this gender, or null to leave the engine's default alone.
     *
     * Android does not expose gender as a property, so this is the names the engines ship with:
     * "en-us-x-iom" and friends on Google's engine carry "male" or "guy" in their name, and a
     * handful of OEM engines still do. When nothing matches, the pitch still does the work.
     */
    private fun pickVoice(engine: TextToSpeech, style: VoiceStyle): Voice? {
        val wanted = if (style.gender == WatcherGender.MALE) {
            listOf("male", "guy", "david", "george", "james", "daniel")
        } else {
            listOf("female", "woman", "samantha", "victoria", "karen")
        }
        val english = runCatching { engine.voices.orEmpty().toList() }.getOrDefault(emptyList())
            .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }
        return english.firstOrNull { v ->
            val name = v.name.lowercase(Locale.US)
            wanted.any { name.contains(it) }
        }
    }

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
        // The channel closes after the last word, the way a handheld does.
        radio.close()
    }
}
