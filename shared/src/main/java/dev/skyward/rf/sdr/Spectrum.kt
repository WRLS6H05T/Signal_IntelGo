package dev.skyward.rf.sdr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns RTL-SDR I/Q bytes into a spectrum.
 *
 * An RTL2832U delivers 8-bit unsigned I and Q interleaved, at whatever rate the tuner was set
 * to, which is a stream no screen can draw directly. This is the only place the samples are
 * touched: one window, one transform, then a fixed number of bars, so the cost per frame is the
 * same whether the dongle is delivering 32 kB or 3 MB.
 *
 * Kept free of Android types so the maths can be checked on the JVM; nothing here throws, and a
 * short or silent buffer yields the noise floor rather than a trace that invents peaks.
 */
object Spectrum {

    /** Bars in one frame. Enough for a phone-width trace, coarse enough to redraw four a second. */
    const val BINS: Int = 128

    /** Largest transform: anything longer is measured from its most recent samples. */
    const val FFT_SIZE: Int = 2048

    /** Below any real signal, and what a buffer with no samples in it reports. */
    const val FLOOR: Float = -120f

    /**
     * One frame of dBFS, one value per bar, lowest frequency first.
     *
     * Each bar is the mean power of the FFT bins that fall in it, not the largest: a peak is
     * what an operator is looking for, and averaging keeps a single strong bin from painting a
     * bar that is wider than the signal it came from.
     */
    fun analyse(iq: ByteArray, bins: Int = BINS): FloatArray {
        val frames = iq.size / 2
        val n = pow2Floor(minOf(frames, FFT_SIZE))
        if (n < 64) return FloatArray(bins) { FLOOR }
        val start = frames - n
        val re = FloatArray(n)
        val im = FloatArray(n)
        // The mean is removed before the window. An RTL-SDR's I/Q sits a fraction of a count
        // off centre, and a window sums that offset into a DC bar at full scale that buries
        // every real carrier - the first thing every waterfall has to do is subtract it.
        var meanI = 0f
        var meanQ = 0f
        for (i in 0 until n) {
            val at = (start + i) * 2
            meanI += (iq[at].toInt() and 0xFF)
            meanQ += (iq[at + 1].toInt() and 0xFF)
        }
        meanI /= n
        meanQ /= n
        // Hann: a rectangular window smears a carrier across every bar, which is the one thing
        // a spectrum is read for. The ends of the window are tapered instead.
        val denom = (n - 1).coerceAtLeast(1)
        for (i in 0 until n) {
            val at = (start + i) * 2
            val w = 0.5f - 0.5f * cos(2.0 * PI * i / denom).toFloat()
            re[i] = (((iq[at].toInt() and 0xFF) - meanI) / 128f) * w
            im[i] = (((iq[at + 1].toInt() and 0xFF) - meanQ) / 128f) * w
        }
        fft(re, im)

        val out = FloatArray(bins)
        val half = n / 2
        val per = (half / bins).coerceAtLeast(1)
        for (b in 0 until bins) {
            var sum = 0.0
            var count = 0
            for (k in 0 until per) {
                val i = b * per + k
                if (i >= half) break
                val m = sqrt((re[i] * re[i] + im[i] * im[i]).toDouble())
                sum += m * m
                count++
            }
            val power = if (count == 0) 0.0 else sum / count
            out[b] = (10.0 * log10(power + 1e-12)).toFloat().coerceIn(FLOOR, 0f)
        }
        return out
    }

    /** Index of the strongest bar: where the carrier is, as far as this frame can tell. */
    fun peakBin(bins: FloatArray): Int {
        var best = 0
        for (i in bins.indices) if (bins[i] > bins[best]) best = i
        return best
    }

    /** Frequency of a bar, given the centre of the sweep and the width it covers. */
    fun binToMhz(bins: FloatArray, index: Int, centerMhz: Double, spanMhz: Double): Double {
        if (bins.isEmpty()) return centerMhz
        val t = (index.toDouble() + 0.5) / bins.size
        return centerMhz - spanMhz / 2 + t * spanMhz
    }

    /** In-place iterative radix-2 FFT. [re] and [im] must be the same power-of-two length. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        if (n < 2 || (n and (n - 1)) != 0 || im.size != n) return
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val vr = re[b] * curR - im[b] * curI
                    val vi = re[b] * curI + im[b] * curR
                    re[b] = re[a] - vr
                    im[b] = im[a] - vi
                    re[a] += vr
                    im[a] += vi
                    val nr = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun pow2Floor(v: Int): Int {
        if (v < 1) return 0
        var p = 1
        while (p shl 1 <= v) p = p shl 1
        return p
    }
}
