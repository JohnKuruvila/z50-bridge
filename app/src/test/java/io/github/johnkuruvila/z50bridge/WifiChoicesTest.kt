package io.github.johnkuruvila.z50bridge

import io.github.johnkuruvila.z50bridge.data.SeenWifiStore
import io.github.johnkuruvila.z50bridge.net.NearbyWifi
import io.github.johnkuruvila.z50bridge.net.buildWifiChoices
import io.github.johnkuruvila.z50bridge.net.isCameraSsid
import io.github.johnkuruvila.z50bridge.net.toggleSsid
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WifiChoicesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun mergesSourcesIntoOneRowPerNameInAStableOrder() {
        val choices = buildWifiChoices(
            allowlist = setOf("homenet", "Office 5G"),
            connected = "HomeNet",
            seen = mapOf("Cafe" to 1_000L, "HomeNet" to 3_000L, "Library" to 2_000L),
            nearby = listOf(NearbyWifi("Library", -80), NearbyWifi("Neighbour", -50)),
        )
        assertEquals(listOf("HomeNet", "Library", "Cafe", "Neighbour", "Office 5G"), choices.map { it.ssid })
        val home = choices.first()
        assertTrue(home.connected && home.selected)
        assertEquals(3_000L, home.lastSeenMs)
        assertEquals(-80, choices[1].nearbyLevel)
        assertFalse(choices[1].selected)
        val office = choices.last()
        assertTrue(office.selected)
        assertEquals(null, office.nearbyLevel)
        assertEquals(null, office.lastSeenMs)
    }

    @Test
    fun cameraNetworksAreLeftOutUnlessAlreadyListed() {
        val nearby = listOf(NearbyWifi("DDPAI_Z50_xxxx", -40), NearbyWifi("MyCam", -45), NearbyWifi("HomeNet", -60))
        assertEquals(listOf("HomeNet"), buildWifiChoices(emptySet(), null, emptyMap(), nearby, cameraSsid = "MyCam").map { it.ssid })
        assertTrue(isCameraSsid("ddpai_z50_1234"))
        val listed = buildWifiChoices(setOf("MyCam"), null, emptyMap(), nearby, cameraSsid = "MyCam")
        assertEquals(setOf("HomeNet", "MyCam"), listed.map { it.ssid }.toSet())
    }

    @Test
    fun hiddenAndUnknownNamesAreSkipped() {
        val choices = buildWifiChoices(emptySet(), "<unknown ssid>", emptyMap(), listOf(NearbyWifi("", -40), NearbyWifi("\"Quoted\"", -50)))
        assertEquals(listOf("Quoted"), choices.map { it.ssid })
    }

    @Test
    fun toggleReplacesDifferentlyCasedCopies() {
        assertEquals(setOf("Office", "HomeNet"), toggleSsid(setOf("homenet", "Office"), "HomeNet", on = true))
        assertEquals(setOf("Office"), toggleSsid(setOf("homenet", "Office"), "HomeNet", on = false))
        assertEquals(setOf("Office"), toggleSsid(setOf("Office"), "  ", on = true))
    }

    @Test
    fun seenStoreKeepsNewestPerNameAndSurvivesReload() {
        val file = File(temp.root, "seen-wifi.txt")
        val store = SeenWifiStore(file, limit = 2)
        store.record("HomeNet", 1_000L)
        store.record("Cafe, Upstairs", 2_000L)
        store.record("homenet", 3_000L)
        store.record("Bad\tName", 4_000L)
        assertEquals(mapOf("homenet" to 3_000L, "Cafe, Upstairs" to 2_000L), store.networks.value)
        store.record("Library", 5_000L)
        assertEquals(mapOf("Library" to 5_000L, "homenet" to 3_000L), SeenWifiStore(file, limit = 2).networks.value)
    }
}
