package com.receiptscan.scanner.wifi.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WifiTest {
    @Test fun `groups by ssid sorts connected first and warns on open`() {
        val e = listOf(
            WifiScanEntry("Home", "a", -70, 2437, "[WPA2-PSK-CCMP][ESS]", 1000),
            WifiScanEntry("Home", "b", -50, 5180, "[RSN-SAE-CCMP][ESS]", 1000),
            WifiScanEntry("Cafe", "c", -40, 2412, "[ESS]", 1000),
            WifiScanEntry("", "d", -80, 5955, "[WPA2-PSK]", 1000),
        )
        val l = WifiNetworks.build(e, "Home", 2000)
        assertThat(l.networks.map { it.displayName }).containsExactly("Home", "Cafe", "Hidden network").inOrder()
        val home = l.networks.first()
        assertThat(home.accessPoints).isEqualTo(2)
        assertThat(home.band).isEqualTo(Band.GHZ_5)
        assertThat(home.security).isEqualTo(Security.WPA3)
        assertThat(home.signal).isEqualTo(SignalStrength.EXCELLENT)
        assertThat(l.networks[1].warning).contains("Open network")
        assertThat(l.networks[2].band).isEqualTo(Band.GHZ_6)
        assertThat(l.stale).isFalse()
        assertThat(WifiNetworks.build(e, null, 1000 + 200_000).stale).isTrue()
    }
}
