package dev.skyward.rf.sdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpectrumTest {

    /** 8-bit I/Q of a single tone, the shape an RTL-SDR delivers. */
    private fun tone(n: Int, bin: Int): ByteArray {
        val iq = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = sin(2.0 * PI * bin * i / n)
            iq[i * 2] = (127 + (v * 100.0)).toInt().toByte()
            iq[i * 2 + 1] = 127.toByte()
        }
        return iq
    }

    @Test
    fun `a tone lands in the bar its frequency maps to`() {
        val n = 512
        val toneBin = 64
        val bins = Spectrum.analyse(tone(n, toneBin))

        assertEquals(Spectrum.BINS, bins.size)
        // The transform keeps the last power-of-two window, and only the positive half is
        // drawn, so a tone at FFT bin 64 belongs in bar 64 / (bins per bar). One bar of slack:
        // a Hann-windowed tone always straddles the two bins around it, and here they fall in
        // neighbouring bars, so either one being the highest is correct.
        val perBar = (n / 2) / Spectrum.BINS
        assertTrue(kotlin.math.abs(Spectrum.peakBin(bins) - toneBin / perBar) <= 1)
    }

    @Test
    fun `a buffer with no samples is the floor, not a trace`() {
        val bins = Spectrum.analyse(ByteArray(16))
        assertTrue(bins.all { it == Spectrum.FLOOR })
    }

    @Test
    fun `a flat buffer is the floor once its offset is removed`() {
        // A real dongle sits a count or two off centre; that offset must not become a DC bar
        // at full scale, or every carrier on the band would be hidden behind it.
        val bins = Spectrum.analyse(ByteArray(4096) { 131.toByte() })
        assertTrue(bins.all { it.isFinite() })
        assertTrue(bins.all { it <= Spectrum.FLOOR + 1f })
    }

    @Test
    fun `a bin maps back to the frequency it covers`() {
        val bins = FloatArray(Spectrum.BINS)
        val low = Spectrum.binToMhz(bins, 0, 433.92, 2.4)
        val high = Spectrum.binToMhz(bins, Spectrum.BINS - 1, 433.92, 2.4)
        assertTrue(low < 433.92 && high > 433.92)
        // The span is 2.4 MHz across the bars, so the two ends are one span apart.
        assertEquals(2.4, high - low, 2.4 / Spectrum.BINS)
    }

    @Test
    fun `the transform rejects a length that is not a power of two`() {
        val re = FloatArray(6) { 1f }
        val im = FloatArray(6)
        Spectrum.fft(re, im)
        // Untouched rather than half-transformed: a caller with the wrong window size gets its
        // own buffer back, not plausible-looking garbage.
        assertTrue(re.all { it == 1f })
    }
}
