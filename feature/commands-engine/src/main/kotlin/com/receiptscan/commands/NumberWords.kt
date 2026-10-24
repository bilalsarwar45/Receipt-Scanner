package com.receiptscan.commands

import java.math.BigDecimal

/**
 * Reads spoken or typed numbers: digits ("1,500", "1.5k"), English words ("three hundred",
 * "one thousand five hundred", "two lakh"), Roman Urdu ("teen sau", "dedh hazaar", "do lakh") and
 * Urdu script ("تین سو"). Supported vocabulary is listed below; anything else is left unparsed rather
 * than guessed. Urdu/Roman Urdu compound numbers 21–99 other than round tens are not yet included.
 */
object NumberWords {
    private val units: Map<String, Int> = buildMap {
        listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
            "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen").forEachIndexed { i, w -> put(w, i) }
        put("a", 1); put("an", 1)
        mapOf("twenty" to 20, "thirty" to 30, "forty" to 40, "fourty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90).forEach { (k, v) -> put(k, v) }
        // Roman Urdu
        mapOf(
            "ek" to 1, "aik" to 1, "do" to 2, "teen" to 3, "tin" to 3, "char" to 4, "chaar" to 4, "panch" to 5, "paanch" to 5, "panj" to 5,
            "chay" to 6, "che" to 6, "chhe" to 6, "chhay" to 6, "saat" to 7, "sat" to 7, "aath" to 8, "ath" to 8, "nau" to 9, "no" to 9,
            "das" to 10, "dus" to 10, "gyarah" to 11, "giyara" to 11, "barah" to 12, "baara" to 12, "terah" to 13, "chaudah" to 14, "pandrah" to 15,
            "solah" to 16, "satrah" to 17, "atharah" to 18, "unnees" to 19, "bees" to 20, "tees" to 30, "chalees" to 40, "chaalees" to 40,
            "pachas" to 50, "pachaas" to 50, "saath" to 60, "sattar" to 70, "assi" to 80, "nabbe" to 90, "nawway" to 90,
        ).forEach { (k, v) -> put(k, v) }
        // Urdu script
        mapOf(
            "ایک" to 1, "دو" to 2, "تین" to 3, "چار" to 4, "پانچ" to 5, "چھ" to 6, "سات" to 7, "آٹھ" to 8, "نو" to 9, "دس" to 10,
            "گیارہ" to 11, "بارہ" to 12, "تیرہ" to 13, "چودہ" to 14, "پندرہ" to 15, "سولہ" to 16, "سترہ" to 17, "اٹھارہ" to 18, "انیس" to 19,
            "بیس" to 20, "تیس" to 30, "چالیس" to 40, "پچاس" to 50, "ساٹھ" to 60, "ستر" to 70, "اسی" to 80, "نوے" to 90,
        ).forEach { (k, v) -> put(k, v) }
    }

    /** Fractional multipliers: "dedh hazaar" = 1,500; "dhai sau" = 250; "sawa sau" = 125; "saadhe teen sau" = 350. */
    private val fractions = mapOf(
        "dedh" to BigDecimal("1.5"), "derh" to BigDecimal("1.5"), "ڈیڑھ" to BigDecimal("1.5"),
        "dhai" to BigDecimal("2.5"), "dhaai" to BigDecimal("2.5"), "ڈھائی" to BigDecimal("2.5"),
        "half" to BigDecimal("0.5"),
    )
    private val plusHalf = setOf("saadhe", "sadhe", "saray", "ساڑھے")
    private val plusQuarter = setOf("sawa", "سوا")

    private val scales: Map<String, Long> = mapOf(
        "hundred" to 100, "sau" to 100, "so" to 100, "سو" to 100,
        "thousand" to 1_000, "hazar" to 1_000, "hazaar" to 1_000, "hajar" to 1_000, "ہزار" to 1_000, "k" to 1_000,
        "lakh" to 100_000, "lac" to 100_000, "lakhs" to 100_000, "لاکھ" to 100_000,
        "million" to 1_000_000,
        "crore" to 10_000_000, "karor" to 10_000_000, "crores" to 10_000_000, "کروڑ" to 10_000_000,
    )

    /** Words that may join number words without ending the number. */
    private val joiners = setOf("and", "aur", "or", "اور")

    fun isNumberWord(token: String): Boolean =
        token in units || token in scales || token in fractions || token in plusHalf || token in plusQuarter

    data class Match(val value: BigDecimal, val startToken: Int, val endTokenExclusive: Int, val fromWords: Boolean)

    /**
     * Finds every number in [tokens]. Digits may be followed by a scale word ("2 lakh", "1.5k").
     * Words like "do" (two / do) are only treated as numbers when followed by a scale word or another number word,
     * so "add kar do" is not read as "add 2".
     */
    fun findAll(tokens: List<String>): List<Match> {
        val out = mutableListOf<Match>()
        var i = 0
        while (i < tokens.size) {
            val m = readAt(tokens, i)
            if (m != null) {
                out += m
                i = m.endTokenExclusive
            } else {
                i++
            }
        }
        return out
    }

    private val ambiguousWords = setOf("do", "no", "a", "an", "sat", "so", "nau", "tin", "che", "دو", "نو", "سو", "ستر", "اسی", "half")

    private fun readAt(tokens: List<String>, start: Int): Match? {
        val first = tokens[start]
        parseDigits(first)?.let { base ->
            var value = base
            var end = start + 1
            while (end < tokens.size && tokens[end] in scales && tokens[end] != "k") {
                value = value.multiply(BigDecimal(scales.getValue(tokens[end])))
                end++
            }
            return Match(value, start, end, fromWords = false)
        }
        if (!isNumberWord(first) || first == "k" || first == "so") return null
        var total = BigDecimal.ZERO
        var current = BigDecimal.ZERO
        var pendingFraction: BigDecimal? = null
        var addHalf = false
        var addQuarter = false
        var end = start
        var consumedWords = 0
        var sawScale = false
        while (end < tokens.size) {
            val t = tokens[end]
            when {
                t in fractions -> pendingFraction = fractions.getValue(t)
                t in plusHalf -> addHalf = true
                t in plusQuarter -> addQuarter = true
                t in units -> current += BigDecimal(units.getValue(t))
                t in scales && (consumedWords > 0 || t != "k") -> {
                    sawScale = true
                    val scale = BigDecimal(scales.getValue(t))
                    var base = when {
                        pendingFraction != null -> pendingFraction
                        current.signum() == 0 -> BigDecimal.ONE
                        else -> current
                    }
                    if (addHalf) base += BigDecimal("0.5")
                    if (addQuarter) base += BigDecimal("0.25")
                    pendingFraction = null; addHalf = false; addQuarter = false
                    if (scale >= BigDecimal(1000)) {
                        total += (base * scale)
                        current = BigDecimal.ZERO
                    } else {
                        current = base * scale
                    }
                }
                t in joiners && consumedWords > 0 && end + 1 < tokens.size && isNumberWord(tokens[end + 1]) -> Unit
                else -> break
            }
            consumedWords++
            end++
        }
        if (consumedWords == 0) return null
        if (pendingFraction != null || addHalf || addQuarter) return null // "dedh" alone is not an amount
        val value = total + current
        // A lone ambiguous word ("do", "no", "a") is not a number without a scale or another number word.
        val words = tokens.subList(start, end).filter { it !in joiners }
        if (words.size == 1 && words[0] in ambiguousWords) return null
        if (words.size == 1 && !sawScale && words[0] in setOf("a", "an")) return null
        return Match(value, start, end, fromWords = true)
    }

    /** "1,500", "1500.50", "1.5k", "300rs" style digit tokens. */
    fun parseDigits(token: String): BigDecimal? {
        val m = Regex("""^(\d{1,3}(?:,\d{2,3})+|\d+)(\.\d+)?(k)?$""").matchEntire(token) ?: return null
        val intPart = m.groupValues[1].replace(",", "")
        var v = BigDecimal(intPart + m.groupValues[2])
        if (m.groupValues[3].isNotEmpty()) v = v.multiply(BigDecimal(1000))
        return v
    }
}
