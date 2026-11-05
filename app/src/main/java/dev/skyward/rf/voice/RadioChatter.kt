package dev.skyward.rf.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.sin

/**
 * The net's own sound.
 *
 * A phone speaker reading a sentence sounds like a phone. A radio sounds like a radio because of
 * what happens around the words: the squelch that opens before a transmission, the squelch and
 * roger beep that close it, and the way a call is addressed. None of that is available from a
 * text-to-speech engine, so it is added here - the words by [frame], the sound by
 * [RadioChatter] - and both are applied to every Watcher reply and every watch alert.
 *
 * The static is synthesised: 150 ms of low-passed noise with a fade at each end, played on its
 * own short-lived track so it can never wedge the speaker the TTS engine is using.
 */
object Radio {

    /**
     * Wraps a line in a radio call.
     *
     * An alert opens with the call sign - "Watcher to operator" - because it is the Watcher
     * hailing; a reply does not, it is just the Watcher keying up. Both close on "Watcher out",
     * the sign-off that ends a transmission on the net.
     */
    fun frame(text: String, alert: Boolean = false): String {
        val body = text.trim()
        if (body.isEmpty()) return body
        val opener = if (alert) "Watcher to operator. " else ""
        return "$opener$body Watcher out."
    }
}

/** The squelch: a burst of static as the channel opens, and again as it closes. */
class RadioChatter(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Opening the channel: a longer burst, doubled on an alert so it reads as urgent. */
    fun open(alert: Boolean = false) {
        burst(150, if (alert) 0.40f else 0.30f, beep = false)
        if (alert) main.postDelayed({ burst(90, 0.30f, beep = false) }, 190)
    }

    /** Closing it: a short burst with the roger beep behind it. */
    fun close() = burst(110, 0.26f, beep = true)

    /**
     * One burst of channel noise.
     *
     * The noise is low-passed in the loop rather than filtered afterwards: a single running
     * average is enough to turn white noise into the hiss a handheld makes, and it costs one
     * multiply per sample on a buffer that is a few thousand samples long.
     */
    private fun burst(ms: Int, gain: Float, beep: Boolean) {
        val rate = 22_050
        val count = rate * ms / 1000
        if (count <= 0) return
        val pcm = ShortArray(count)
        val rnd = java.util.Random()
        var low = 0f
        for (i in 0 until count) {
            val edge = when {
                i < count * 0.15f -> i / (count * 0.15f)
                i > count * 0.78f -> (count - i) / (count * 0.22f)
                else -> 1f
            }
            low = low * 0.55f + (rnd.nextFloat() - 0.5f) * 0.45f
            val hiss = low * 2.2f
            val tone = if (beep && i > count * 0.6f) {
                sin(i * 2.0 * Math.PI * 1_180.0 / rate).toFloat() * 0.30f
            } else {
                0f
            }
            pcm[i] = ((hiss + tone) * edge * gain * Short.MAX_VALUE)
                .toInt()
                .coerceIn(-32_767, 32_767)
                .toShort()
        }
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return
        runCatching {
            track.write(pcm, 0, pcm.size)
            track.setVolume(0.55f)
            track.play()
        }
        // The track is released under the app's own handler: a static track that is never
        // released holds an audio session, and one per transmission would leak a session a
        // sentence.
        main.postDelayed(
            {
                runCatching { track.stop() }
                runCatching { track.release() }
            },
            (ms + 150).toLong(),
        )
    }
}
