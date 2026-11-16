package dev.skyward.rf.desktop

import dev.skyward.rf.core.BleAddress
import dev.skyward.rf.core.BleIdentity
import dev.skyward.rf.core.BleKind
import dev.skyward.rf.core.BleVendor
import dev.skyward.rf.core.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three labels the logged table and the map card read out.
 *
 * Each is a read of text a radio wrote, so they are tested on the shapes firmware actually prints: a
 * network is `[WPA2-PSK-CCMP][ESS]` as often as it is `WPA2`, and an address arrives with colons,
 * dashes or none at all.
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
    fun `the address prefix names the maker, in any notation`() {
        assertEquals("F0:18:98", BleAddress.oui("f0:18:98:1a:2b:3c"))
        assertEquals("F0:18:98", BleAddress.oui("F0-18-98-1A-2B-3C"))
        assertEquals("F0:18:98", BleAddress.oui("f018981a2b3c"))
        assertNull(BleAddress.oui(""))
        assertNull(BleAddress.oui("BT_LE"))

        assertEquals("apple", BleVendor.of("F0:18:98:1a:2b:3c"))
        assertEquals("bose", BleVendor.of("c8:7b:23:00:11:22"))
        assertNull(BleVendor.of("D5:1F:48:AC:EC:37"))
    }

    @Test
    fun `the address categorises an advertiser the name leaves blank`() {
        // A Bose prefix is audio even with nothing in the name; an Apple prefix is only a vendor,
        // because Apple ships phones, watches and earbuds off the same pool.
        assertEquals("bose \u00B7 audio", BleIdentity.label("", "C8:7B:23:12:34:56"))
        assertEquals("apple", BleIdentity.label("", "F0:18:98:12:34:56"))
        // A randomised address says nothing, and the label must not invent a vendor from its bits.
        assertEquals("unknown", BleIdentity.label("", "D5:1F:48:AC:EC:37"))
        assertEquals("unknown", BleIdentity.label("BLE", "CC:89:70:5B:BC:9D"))
        // A name that does claim a kind beats the prefix.
        assertEquals("apple \u00B7 audio", BleIdentity.label("Jabra Elite", "F0:18:98:12:34:56"))
    }

    @Test
    fun `the advertisement names the maker when the address says nothing`() {
        // A made-up address, which is what phones, watches and earbuds hand out.
        val random = "D5:1F:48:AC:EC:37"
        assertEquals("garmin \u00B7 wearable", BleIdentity.label("", random, 0x0087))
        assertEquals("plantronics \u00B7 audio", BleIdentity.label("BLE", random, 0x0055))
        assertEquals("apple", BleIdentity.label("", random, 0x004C))

        // A service UUID settles a kind the company id leaves open - Apple's id ships every kind.
        val leAudio = "00001851-0000-1000-8000-00805f9b34fb"
        assertEquals("apple \u00B7 audio", BleIdentity.label("", random, 0x004C, listOf(leAudio)))
        assertEquals("wearable", BleIdentity.label("", random, 0, listOf("0000180d-0000-1000-8000-00805f9b34fb")))
        assertEquals("beacon", BleIdentity.label("BLE", random, 0, listOf("FEAA")))
        assertEquals("google", BleIdentity.label("", random, 0, listOf("FE2C")))

        // Battery and device-information ride on nearly everything, so they must not categorise.
        assertEquals("unknown", BleIdentity.label("", random, 0x7FFF, listOf("180F")))
        assertEquals("unknown", BleIdentity.label("", random))
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
