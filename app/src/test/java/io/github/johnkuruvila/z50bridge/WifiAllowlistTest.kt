package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.net.NetworkHint
import io.github.johnkuruvila.z50bridge.net.normalizeSsid
import io.github.johnkuruvila.z50bridge.net.offloadAllowed
import io.github.johnkuruvila.z50bridge.net.decodeSsidAllowlist
import io.github.johnkuruvila.z50bridge.net.ssidAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAllowlistTest {
    @Test
    fun decoderSplitsOnNewlinesOnlySoNamesKeepCommas() {
        val parsed = decodeSsidAllowlist("HomeNet\nCafe, Upstairs\nGarage;2\nHomeNet")
        assertEquals(setOf("HomeNet", "Cafe, Upstairs", "Garage;2"), parsed)
    }

    @Test
    fun decoderTrimsQuotesAndDropsBlanks() {
        val parsed = decodeSsidAllowlist(" \"HomeNet\" \n\n  \n")
        assertEquals(setOf("HomeNet"), parsed)
    }

    @Test
    fun normalizerRejectsBlankAndUnknownSsids() {
        assertNull(normalizeSsid(null))
        assertNull(normalizeSsid(""))
        assertNull(normalizeSsid("   "))
        assertNull(normalizeSsid("<unknown ssid>"))
        assertEquals("HomeNet", normalizeSsid("\"HomeNet\""))
    }

    @Test
    fun emptyAllowlistAllowsEveryNetwork() {
        val allowlist = emptySet<String>()
        assertTrue(offloadAllowed(NetworkHint(wifi = true, internet = true, ssid = "AnyNet"), allowlist))
        assertTrue(offloadAllowed(NetworkHint(wired = true, internet = true), allowlist))
    }

    @Test
    fun mobileDataIsNeverAllowedEvenThroughVpn() {
        listOf(emptySet(), setOf("HomeNet")).forEach { allowlist ->
            assertFalse(offloadAllowed(NetworkHint(cellular = true, internet = true), allowlist))
            assertFalse(offloadAllowed(NetworkHint(vpn = true, cellular = true, internet = true), allowlist))
        }
    }

    @Test
    fun vpnOverWifiIsExemptFromTheAllowlist() {
        val allowlist = setOf("HomeNet")
        assertTrue(offloadAllowed(NetworkHint(vpn = true, internet = true), allowlist))
        assertTrue(offloadAllowed(NetworkHint(vpn = true, wifi = true, ssid = "HotelWiFi", internet = true), allowlist))
    }

    @Test
    fun wifiMustMatchAnAllowlistedSsid() {
        val allowlist = setOf("HomeNet", "Office 5G")
        assertTrue(offloadAllowed(NetworkHint(wifi = true, internet = true, ssid = "HomeNet"), allowlist))
        assertTrue(offloadAllowed(NetworkHint(wifi = true, internet = true, ssid = "office 5g"), allowlist))
        assertFalse(offloadAllowed(NetworkHint(wifi = true, internet = true, ssid = "HotelWiFi"), allowlist))
    }

    @Test
    fun unknownSsidCellularAndWiredAreBlockedWhenAllowlistIsSet() {
        val allowlist = setOf("HomeNet")
        assertFalse(offloadAllowed(NetworkHint(wifi = true, internet = true), allowlist))
        assertFalse(offloadAllowed(NetworkHint(wifi = true, internet = true, ssid = "<unknown ssid>"), allowlist))
        assertFalse(offloadAllowed(NetworkHint(cellular = true, internet = true), allowlist))
        assertFalse(offloadAllowed(NetworkHint(wired = true, internet = true), allowlist))
    }

    @Test
    fun ssidAllowedIgnoresCase() {
        assertTrue(ssidAllowed("HomeNet", setOf("homenet")))
        assertFalse(ssidAllowed(null, setOf("homenet")))
    }
}
