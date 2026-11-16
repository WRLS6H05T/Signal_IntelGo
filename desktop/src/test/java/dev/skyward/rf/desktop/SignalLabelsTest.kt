package dev.skyward.rf.desktop

import dev.skyward.rf.core.BleKind
import dev.skyward.rf.core.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two labels the logged table and the map card read out.
 *
 * Both are keyword reads of text a radio wrote, so they are tested on the shapes firmware actually
 * prints rather than on tidy values: a network is `[WPA2-PSK-CCMP][ESS]` as often as it is `WPA2`.
 */
class SignalLabelsTest {

    @Test
    fun `a BLE advertiser's name decides its kind`() {
        assertEquals(BleKind.AUDIO, BleKind.of("Jabra Elite 75t"))
        assertEquals(BleKind.AUDIO, BleKind.of("WH-1000XM4"))
        assertEquals(BleKind.WEARABLE, BleKind.of("Galaxy Watch5"))
        assertEquals(BleKind.TRACKER, BleKind.of("AirTag"))
        assertEquals(BleKind.INPUT, BleKind.of("Keychron K2"))
        assertEquals(BleKind.PHONE, BleKind.of("Pixel 8 Pro"))
        assertEquals(BleKind.BEACON, BleKind.of("ESP32 beacon"))
        // Nothing to go on: an unnamed advertiser must not be guessed into a category.
        assertEquals(BleKind.UNKNOWN, BleKind.of(""))
        assertEquals(BleKind.UNKNOWN, BleKind.of("BLE"))
    }

    @Test
    fun `firmware security strings collapse to one label`() {
        assertEquals(WifiSecurity.WPA2, WifiSecurity.label("WPA2_PSK"))
        assertEquals(WifiSecurity.WPA2, WifiSecurity.label("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.label("SAE"))
        assertEquals(WifiSecurity.WPA3, WifiSecurity.label("WPA3-SAE"))
        assertEquals(WifiSecurity.WEP, WifiSecurity.label("WEP"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.label("OPEN"))
        assertEquals(WifiSecurity.OPEN, WifiSecurity.label("none"))
        assertEquals(WifiSecurity.UNKNOWN, WifiSecurity.label(""))
    }

    @Test
    fun `open means no key, and OWE counts as open`() {
        assertTrue(WifiSecurity.isOpen("OPEN"))
        assertTrue(WifiSecurity.isOpen("NONE"))
        assertTrue(WifiSecurity.isOpen("OWE"))
        assertFalse(WifiSecurity.isOpen("WPA2_PSK"))
        assertFalse(WifiSecurity.isOpen(""))
    }
}
