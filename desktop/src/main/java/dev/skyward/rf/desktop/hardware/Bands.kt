package dev.skyward.rf.desktop.hardware

/**
 * Which Wi-Fi band a channel number belongs to.
 *
 * This is the one number that proves a C5 is doing its job: a single-band radio never prints a
 * channel above 14, so a 5 GHz count of zero on a dual-band board means the second pass is not
 * running, not that there is nothing up there.
 */
object Bands {

    const val W24: String = "2.4G"
    const val W5: String = "5G"

    /** 5 GHz channels start at 36; everything below is 2.4 GHz, including channel 14. */
    fun of(channel: Int): String = if (channel >= 36) W5 else W24

    /** The channel a centre frequency sits on, for a sweep that prints MHz instead. */
    fun channelOf(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz >= 5000 -> (freqMhz - 5000) / 5
        else -> 0
    }
}
