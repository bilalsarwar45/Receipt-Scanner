package com.receiptscan.scanner.wifi.engine

/** Raw scan entry as Android reports it (ScanResult fields we use). */
data class WifiScanEntry(
    val ssid: String,
    val bssid: String,
    val levelDbm: Int,
    val frequencyMhz: Int,
    val capabilities: String,
    val timestampMillis: Long,
)

enum class Band(val label: String) { GHZ_2_4("2.4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz"), UNKNOWN("Unknown band") }
enum class Security(val label: String, val isOpen: Boolean) { OPEN("Open", true), OWE("Enhanced Open", false), WEP("WEP (outdated)", false), WPA("WPA/WPA2", false), WPA3("WPA3", false), ENTERPRISE("Enterprise", false) }
enum class SignalStrength(val label: String) { EXCELLENT("Excellent"), GOOD("Good"), FAIR("Fair"), WEAK("Weak") }

data class NetworkView(
    val ssid: String,
    val displayName: String,
    val security: Security,
    val band: Band,
    val signal: SignalStrength,
    val levelDbm: Int,
    val accessPoints: Int,
    val connected: Boolean,
    val warning: String?,
)

data class NetworkList(val networks: List<NetworkView>, val lastScanMillis: Long?, val stale: Boolean)

/**
 * Turns raw scan results into the Nearby networks list: one row per network name (strongest access
 * point), signal as words, security and band, open-network warnings, and an honest staleness flag
 * (Android throttles scans, so old results are labelled rather than presented as live).
 */
object WifiNetworks {
    fun band(freq: Int) = when (freq) {
        in 2400..2500 -> Band.GHZ_2_4
        in 4900..5900 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> Band.UNKNOWN
    }

    fun security(cap: String): Security {
        val c = cap.uppercase()
        return when {
            "EAP" in c -> Security.ENTERPRISE
            "SAE" in c -> Security.WPA3
            "OWE" in c -> Security.OWE
            "WPA" in c || "RSN" in c || "PSK" in c -> Security.WPA
            "WEP" in c -> Security.WEP
            else -> Security.OPEN
        }
    }

    fun signal(dbm: Int) = when {
        dbm >= -55 -> SignalStrength.EXCELLENT
        dbm >= -67 -> SignalStrength.GOOD
        dbm >= -78 -> SignalStrength.FAIR
        else -> SignalStrength.WEAK
    }

    fun build(entries: List<WifiScanEntry>, connectedSsid: String?, nowMillis: Long, staleAfterMillis: Long = 120_000): NetworkList {
        val groups = entries.groupBy { it.ssid.ifEmpty { "hidden:" + it.bssid } }
        val views = groups.map { (key, list) ->
            val best = list.maxBy { it.levelDbm }
            val sec = security(best.capabilities)
            val name = if (key.startsWith("hidden:")) "Hidden network" else best.ssid
            NetworkView(
                ssid = best.ssid, displayName = name, security = sec, band = band(best.frequencyMhz), signal = signal(best.levelDbm),
                levelDbm = best.levelDbm, accessPoints = list.size, connected = connectedSsid != null && connectedSsid == best.ssid,
                warning = when {
                    sec.isOpen -> "Open network: others nearby can see unencrypted traffic. Avoid banking here."
                    sec == Security.WEP -> "WEP is outdated and easy to break."
                    else -> null
                },
            )
        }.sortedWith(compareByDescending<NetworkView> { it.connected }.thenByDescending { it.levelDbm })
        val last = entries.maxOfOrNull { it.timestampMillis }
        return NetworkList(views, last, last == null || nowMillis - last > staleAfterMillis)
    }
}
