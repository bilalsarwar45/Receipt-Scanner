package com.receiptscan.scanner.text.engine

import com.receiptscan.time.PrintedDateParser
import java.time.LocalDate

enum class EntityType { URL, EMAIL, PHONE, DATE }

/** An actionable span in recognised text ("smart chip"). [start]/[end] index into the text. */
data class TextEntity(val type: EntityType, val text: String, val start: Int, val end: Int, val normalized: String)

enum class Script(val label: String) { LATIN("Latin"), ARABIC("Arabic/Urdu"), DEVANAGARI("Devanagari"), CJK("Chinese/Japanese/Korean"), OTHER("Other"), NONE("None") }

/**
 * Finds links, emails, phone numbers and dates in recognised text, and reports the dominant script.
 * The script check lets the UI say "Urdu text isn't supported by the on-device recogniser" honestly
 * rather than showing garbled Latin output as if it were correct.
 */
object TextEntities {
    private val url = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"']+[^\s<>"'.,;:!?)\]])""")
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
    // International (+92 300 1234567), Pakistani mobile (0300-1234567) and landline (042-1234567) forms.
    private val phone = Regex("""(?<![\d+])(\+\d{1,3}[\s-]?\(?\d{2,4}\)?[\s-]?\d{3,4}[\s-]?\d{3,4}|0\d{2,3}[\s-]?\d{7,8}|0\d{3}[\s-]?\d{3}[\s-]?\d{4})(?!\d)""")

    fun find(text: String, today: LocalDate): List<TextEntity> {
        val out = mutableListOf<TextEntity>()
        fun free(r: IntRange) = out.none { it.start <= r.last && r.first < it.end }
        url.findAll(text).forEach { m ->
            val v = m.value
            out += TextEntity(EntityType.URL, v, m.range.first, m.range.last + 1, if (v.startsWith("www.", true)) "https://$v" else v)
        }
        email.findAll(text).forEach { m -> if (free(m.range)) out += TextEntity(EntityType.EMAIL, m.value, m.range.first, m.range.last + 1, m.value.lowercase()) }
        phone.findAll(text).forEach { m ->
            if (free(m.range)) {
                val digits = m.value.filter { it.isDigit() || it == '+' }
                if (digits.count { it.isDigit() } in 9..15) out += TextEntity(EntityType.PHONE, m.value, m.range.first, m.range.last + 1, digits)
            }
        }
        PrintedDateParser.findAll(text, today).forEach { d ->
            val idx = text.indexOf(d.raw)
            if (idx >= 0 && free(idx until idx + d.raw.length)) out += TextEntity(EntityType.DATE, d.raw, idx, idx + d.raw.length, d.date.toString())
        }
        return out.sortedBy { it.start }
    }

    fun dominantScript(text: String): Script {
        val counts = mutableMapOf<Script, Int>()
        text.forEach { c ->
            if (!c.isLetter()) return@forEach
            val s = when (Character.UnicodeScript.of(c.code)) {
                Character.UnicodeScript.LATIN -> Script.LATIN
                Character.UnicodeScript.ARABIC -> Script.ARABIC
                Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> Script.CJK
                else -> Script.OTHER
            }
            counts[s] = (counts[s] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key ?: Script.NONE
    }

    /** All match positions of [query] (case-insensitive) for find-in-text highlighting. */
    fun occurrences(text: String, query: String): List<IntRange> {
        if (query.isBlank()) return emptyList()
        val out = mutableListOf<IntRange>()
        var i = text.indexOf(query, ignoreCase = true)
        while (i >= 0) {
            out += i until i + query.length
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        return out
    }
}
