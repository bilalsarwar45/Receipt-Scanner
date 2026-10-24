package com.receiptscan.scanner.qr.engine

import java.net.IDN
import java.net.URI

/** Barcode symbology as reported by the decoder. */
enum class CodeFormat { QR_CODE, DATA_MATRIX, AZTEC, PDF417, EAN_13, EAN_8, UPC_A, UPC_E, CODE_128, CODE_39, CODE_93, ITF, CODABAR, UNKNOWN }

/** Typed decoded content. Nothing is opened or joined automatically; each type has a deliberate action. */
sealed interface QrPayload {
    val raw: String

    data class Link(override val raw: String, val url: String, val safety: LinkSafety) : QrPayload
    data class Wifi(override val raw: String, val ssid: String, val security: WifiSecurity, val password: String?, val hidden: Boolean) : QrPayload {
        /** Password is masked in UI until the person reveals it. */
        val maskedPassword: String? get() = password?.let { "•".repeat(it.length.coerceIn(4, 12)) }
    }
    data class Email(override val raw: String, val address: String, val subject: String?, val body: String?) : QrPayload
    data class Phone(override val raw: String, val number: String) : QrPayload
    data class Sms(override val raw: String, val number: String, val message: String?) : QrPayload
    data class Geo(override val raw: String, val latitude: Double, val longitude: Double, val query: String?) : QrPayload
    data class Contact(override val raw: String, val name: String?, val phones: List<String>, val emails: List<String>, val organization: String?) : QrPayload
    data class Event(override val raw: String, val summary: String?, val start: String?, val end: String?, val location: String?) : QrPayload
    /** A retail product code. Decoding gives only the number; there is no product database behind it. */
    data class ProductCode(override val raw: String, val format: CodeFormat, val code: String, val checksumValid: Boolean) : QrPayload
    data class Text(override val raw: String) : QrPayload
}

enum class WifiSecurity(val label: String) { OPEN("Open"), WEP("WEP"), WPA("WPA/WPA2"), WPA3("WPA3"), UNKNOWN("Unknown") }

/** Why a link deserves a second look. An empty list does not mean "safe": we never guarantee safety. */
data class LinkSafety(val warnings: List<String>) {
    val needsConfirmation: Boolean get() = warnings.isNotEmpty()
}

object QrPayloadParser {

    fun parse(raw: String, format: CodeFormat = CodeFormat.QR_CODE): QrPayload {
        val text = raw.trim()
        when (format) {
            CodeFormat.EAN_13, CodeFormat.EAN_8, CodeFormat.UPC_A, CodeFormat.UPC_E ->
                if (text.all { it.isDigit() }) return QrPayload.ProductCode(raw, format, text, Gtin.isValid(normalizeUpcE(text, format)))
            else -> Unit
        }
        val upper = text.uppercase()
        return when {
            upper.startsWith("WIFI:") -> parseWifi(raw, text)
            upper.startsWith("MAILTO:") -> parseMailto(raw, text)
            upper.startsWith("MATMSG:") -> parseMatmsg(raw, text)
            upper.startsWith("TEL:") -> QrPayload.Phone(raw, text.substring(4).trim())
            upper.startsWith("SMSTO:") || upper.startsWith("SMS:") -> {
                val body = text.substringAfter(':')
                val number = body.substringBefore(':')
                QrPayload.Sms(raw, number, body.substringAfter(':', "").ifEmpty { null })
            }
            upper.startsWith("GEO:") -> parseGeo(raw, text) ?: QrPayload.Text(raw)
            upper.startsWith("BEGIN:VCARD") -> parseVcard(raw, text)
            upper.startsWith("MECARD:") -> parseMecard(raw, text)
            upper.startsWith("BEGIN:VEVENT") || upper.startsWith("BEGIN:VCALENDAR") -> parseEvent(raw, text)
            upper.startsWith("HTTP://") || upper.startsWith("HTTPS://") -> QrPayload.Link(raw, text, LinkSafetyCheck.check(text))
            Regex("(?i)^www\\.[a-z0-9-]+\\.[a-z]{2,}").containsMatchIn(text) && !text.contains(' ') ->
                QrPayload.Link(raw, "https://$text", LinkSafetyCheck.check("https://$text"))
            text.length in 8..14 && text.all { it.isDigit() } && Gtin.isValid(text) -> QrPayload.ProductCode(raw, format, text, true)
            else -> QrPayload.Text(raw)
        }
    }

    private fun normalizeUpcE(code: String, format: CodeFormat): String = if (format == CodeFormat.UPC_E && code.length == 8) Gtin.upcEToUpcA(code) ?: code else code

    /** Splits "K:v;K:v;;" honouring backslash escapes used by the Wi-Fi and MeCard formats. */
    internal fun fields(body: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val sb = StringBuilder()
        var i = 0
        val parts = mutableListOf<String>()
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { sb.append(body[i + 1]); i += 2; continue }
                c == ';' -> { parts += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty()) parts += sb.toString()
        parts.filter { it.contains(':') }.forEach { p -> out.putIfAbsent(p.substringBefore(':').uppercase(), p.substringAfter(':')) }
        return out
    }

    private fun parseWifi(raw: String, text: String): QrPayload {
        val f = fields(text.substring(5))
        val ssid = f["S"] ?: return QrPayload.Text(raw)
        val security = when (f["T"]?.uppercase()) {
            null, "", "NOPASS", "NONE" -> if (f["P"].isNullOrEmpty()) WifiSecurity.OPEN else WifiSecurity.UNKNOWN
            "WEP" -> WifiSecurity.WEP
            "WPA", "WPA2", "WPA/WPA2" -> WifiSecurity.WPA
            "SAE", "WPA3" -> WifiSecurity.WPA3
            else -> WifiSecurity.UNKNOWN
        }
        return QrPayload.Wifi(raw, ssid, security, f["P"]?.ifEmpty { null }, f["H"].equals("true", ignoreCase = true))
    }

    private fun parseMailto(raw: String, text: String): QrPayload {
        val rest = text.substring(7)
        val address = rest.substringBefore('?')
        val query = rest.substringAfter('?', "").split('&').associate { it.substringBefore('=').lowercase() to decode(it.substringAfter('=', "")) }
        return QrPayload.Email(raw, decode(address), query["subject"], query["body"])
    }

    private fun parseMatmsg(raw: String, text: String): QrPayload {
        val f = fields(text.substring(7))
        return QrPayload.Email(raw, f["TO"].orEmpty(), f["SUB"], f["BODY"])
    }

    private fun parseGeo(raw: String, text: String): QrPayload.Geo? {
        val body = text.substring(4)
        val coords = body.substringBefore('?').split(',')
        val lat = coords.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = coords.getOrNull(1)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val q = body.substringAfter("?q=", "").ifEmpty { null }?.let(::decode)
        return QrPayload.Geo(raw, lat, lon, q)
    }

    private fun parseVcard(raw: String, text: String): QrPayload {
        val lines = text.replace("\r\n ", "").lines()
        fun values(key: String) = lines.filter { it.uppercase().startsWith(key) && (it.length == key.length || it[key.length] in ":;") }.map { it.substringAfter(':').trim() }
        val name = values("FN").firstOrNull() ?: values("N").firstOrNull()?.split(';')?.filter { it.isNotBlank() }?.reversed()?.joinToString(" ")
        return QrPayload.Contact(raw, name, values("TEL"), values("EMAIL"), values("ORG").firstOrNull())
    }

    private fun parseMecard(raw: String, text: String): QrPayload {
        val body = text.substring(7)
        val parts = body.split(';').filter { it.contains(':') }
        fun all(k: String) = parts.filter { it.substringBefore(':').equals(k, true) }.map { it.substringAfter(':') }
        val name = all("N").firstOrNull()?.split(',')?.reversed()?.joinToString(" ")?.trim()
        return QrPayload.Contact(raw, name, all("TEL"), all("EMAIL"), all("ORG").firstOrNull())
    }

    private fun parseEvent(raw: String, text: String): QrPayload {
        val lines = text.lines()
        fun v(k: String) = lines.firstOrNull { it.uppercase().startsWith(k) }?.substringAfter(':')?.trim()
        return QrPayload.Event(raw, v("SUMMARY"), v("DTSTART"), v("DTEND"), v("LOCATION"))
    }

    private fun decode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}

/** GTIN (EAN/UPC) check digit validation. */
object Gtin {
    fun isValid(code: String): Boolean {
        if (code.length !in setOf(8, 12, 13, 14) || !code.all { it.isDigit() }) return false
        val digits = code.map { it - '0' }
        val check = digits.last()
        val sum = digits.dropLast(1).reversed().mapIndexed { i, d -> if (i % 2 == 0) d * 3 else d }.sum()
        return (10 - sum % 10) % 10 == check
    }

    fun upcEToUpcA(upce: String): String? {
        if (upce.length != 8 || !upce.all { it.isDigit() }) return null
        val n = upce[0]
        val d = upce.substring(1, 7)
        val check = upce[7]
        val body = when (d[5]) {
            '0', '1', '2' -> "${d[0]}${d[1]}${d[5]}0000${d[2]}${d[3]}${d[4]}"
            '3' -> "${d[0]}${d[1]}${d[2]}00000${d[3]}${d[4]}"
            '4' -> "${d[0]}${d[1]}${d[2]}${d[3]}00000${d[4]}"
            else -> "${d[0]}${d[1]}${d[2]}${d[3]}${d[4]}0000${d[5]}"
        }
        return "$n$body$check"
    }
}

/**
 * Heuristics that decide whether to show "Open this link?" with a warning. They cannot prove a link
 * is safe, so the UI never says it is.
 */
object LinkSafetyCheck {
    private val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "rb.gy")

    fun check(url: String): LinkSafety {
        val warnings = mutableListOf<String>()
        val uri = runCatching { URI(url.replace(" ", "%20")) }.getOrNull()
            ?: return LinkSafety(listOf("This address is malformed"))
        val host = uri.host?.lowercase() ?: return LinkSafety(listOf("No website name in this address"))
        if (uri.scheme.equals("http", true)) warnings += "Not secure (no HTTPS)"
        if (Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(host) || host.startsWith("[")) warnings += "Uses a raw IP address instead of a name"
        if (host.split('.').any { it.startsWith("xn--") } || IDN.toUnicode(host) != host || host.any { it.code > 127 }) warnings += "Contains look-alike characters"
        if (uri.rawUserInfo != null) warnings += "Hides the real address after an @"
        if (host in shorteners) warnings += "Short link hides where it goes"
        if (url.length > 200) warnings += "Very long address"
        if (host.count { it == '.' } >= 4) warnings += "Unusually many subdomains"
        return LinkSafety(warnings)
    }
}

/**
 * Stops the camera analyzer from reporting the same code again and again. A value is reported once,
 * then suppressed until it has been out of view for [cooldownMillis].
 */
class RepeatGuard(private val cooldownMillis: Long = 2_500) {
    private val lastSeen = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldReport(value: String, nowMillis: Long): Boolean {
        val prev = lastSeen[value]
        lastSeen[value] = nowMillis
        lastSeen.entries.removeAll { nowMillis - it.value > cooldownMillis * 4 }
        return prev == null || nowMillis - prev > cooldownMillis
    }
}
