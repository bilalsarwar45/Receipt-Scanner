package com.receiptscan.money

import java.math.BigDecimal

/** Result of reading an amount out of printed or typed text. The raw text is always kept. */
sealed interface AmountParse {
    val raw: String

    data class Ok(
        override val raw: String,
        val value: BigDecimal,
        /** Currency word/symbol found next to the number, if any (for example "Rs." or "PKR"). */
        val currencyToken: String?,
        /** True when the separators could be read two ways and a default was chosen. */
        val ambiguousSeparators: Boolean = false,
    ) : AmountParse

    data class Invalid(override val raw: String, val reason: Reason) : AmountParse

    enum class Reason { EMPTY, NO_DIGITS, MALFORMED, TOO_LARGE }
}

/** How a lone separator should be read when the text alone cannot decide. */
enum class DecimalStyle {
    /** "1,500.50" — Pakistan, UK, US. */
    POINT,
    /** "1.500,50" — much of Europe. */
    COMMA,
}

/**
 * Locale-tolerant amount reader for OCR text and spoken/typed commands.
 *
 * Handles Unicode digits (Arabic-Indic, Extended Arabic-Indic/Urdu, Devanagari, full-width),
 * Western and South Asian (lakh) grouping, decimal comma or point, currency words/symbols,
 * the Pakistani "/-" suffix, and negative forms "-200", "(200)", "200-" and the minus sign "−".
 * It never rounds: the caller converts to [Money] with the currency's fraction digits.
 */
object AmountParser {

    private val currencyPrefix = Regex(
        """^(?i)(pkr|rs\.?|inr|usd|us\$|eur|gbp|aed|sar|jpy|kwd|bhd|omr|[₨₹$€£¥]|روپے)\s*""",
    )
    private val currencySuffix = Regex(
        """\s*(?i)(pkr|rs\.?|rupees?|inr|usd|eur|gbp|aed|sar|jpy|kwd|bhd|omr|[₨₹$€£¥]|روپے)$""",
    )

    fun normalizeDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '०'..'९' -> '0' + (ch - '०')
                    in '０'..'９' -> '0' + (ch - '０')
                    '٫' -> '.' // Arabic decimal separator
                    '٬', '،' -> ',' // Arabic thousands separator / Arabic comma
                    '−', '–', '‒' -> '-'
                    ' ', ' ', ' ', ' ' -> ' '
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    fun parse(text: String, style: DecimalStyle = DecimalStyle.POINT, maxFractionDigits: Int = 3): AmountParse {
        val raw = text
        var s = normalizeDigits(text).trim()
        if (s.isEmpty()) return AmountParse.Invalid(raw, AmountParse.Reason.EMPTY)

        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1).trim()
        }
        if (s.endsWith("/-")) s = s.dropLast(2).trim()
        if (s.endsWith("/=")) s = s.dropLast(2).trim()

        var token: String? = null
        currencyPrefix.find(s)?.let { m ->
            token = m.groupValues[1]
            s = s.substring(m.range.last + 1).trim()
        }
        if (s.startsWith("-")) {
            negative = !negative
            s = s.substring(1).trim()
            currencyPrefix.find(s)?.let { m ->
                token = token ?: m.groupValues[1]
                s = s.substring(m.range.last + 1).trim()
            }
        }
        currencySuffix.find(s)?.let { m ->
            token = token ?: m.groupValues[1]
            s = s.substring(0, m.range.first).trim()
        }
        if (s.endsWith("-")) {
            negative = !negative
            s = s.dropLast(1).trim()
        }
        if (s.endsWith("/-")) s = s.dropLast(2).trim()
        if (s.none { it.isDigit() }) return AmountParse.Invalid(raw, AmountParse.Reason.NO_DIGITS)
        if (s.any { !(it.isDigit() && it in '0'..'9') && it != ',' && it != '.' && it != ' ' && it != '\'' }) {
            return AmountParse.Invalid(raw, AmountParse.Reason.MALFORMED)
        }
        if (!s.first().isDigit() && s.first() != '.' && s.first() != ',') return AmountParse.Invalid(raw, AmountParse.Reason.MALFORMED)
        if (!s.last().isDigit()) {
            // "1,500." is accepted as "1500"; other trailing separators are malformed
            if (s.last() == '.' && s.dropLast(1).lastOrNull()?.isDigit() == true && s.count { it == '.' } == 1) {
                s = s.dropLast(1)
            } else {
                return AmountParse.Invalid(raw, AmountParse.Reason.MALFORMED)
            }
        }

        val result = splitNumber(s, style, maxFractionDigits) ?: return AmountParse.Invalid(raw, AmountParse.Reason.MALFORMED)
        val (intPart, fracPart, ambiguous) = result
        if (intPart.length > 15) return AmountParse.Invalid(raw, AmountParse.Reason.TOO_LARGE)
        var value = BigDecimal(if (fracPart.isEmpty()) intPart.ifEmpty { "0" } else "${intPart.ifEmpty { "0" }}.$fracPart")
        if (negative) value = value.negate()
        return AmountParse.Ok(raw, value, token, ambiguous)
    }

    private data class Split(val integer: String, val fraction: String, val ambiguous: Boolean)

    private fun splitNumber(input: String, style: DecimalStyle, maxFractionDigits: Int): Split? {
        // Spaces and apostrophes are only valid as digit-group separators.
        val s = input
        val lastDot = s.lastIndexOf('.')
        val lastComma = s.lastIndexOf(',')
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }

        val decimalSep: Char?
        var ambiguous = false
        when {
            dots > 0 && commas > 0 -> decimalSep = if (lastDot > lastComma) '.' else ','
            dots == 0 && commas == 0 -> decimalSep = null
            else -> {
                val sep = if (dots > 0) '.' else ','
                val count = if (dots > 0) dots else commas
                val after = s.length - s.lastIndexOf(sep) - 1
                decimalSep = if (count > 1) {
                    null
                } else if (after == 3) {
                    // "1,500" or "1.500": grouping unless the style says this separator is decimal.
                    val styleDecimal = if (style == DecimalStyle.POINT) '.' else ','
                    ambiguous = true
                    if (sep == styleDecimal && maxFractionDigits >= 3) sep else null
                } else if (after in 1..maxFractionDigits) {
                    sep
                } else {
                    return null
                }
            }
        }

        val intRaw: String
        val frac: String
        if (decimalSep != null) {
            val idx = s.lastIndexOf(decimalSep)
            intRaw = s.substring(0, idx)
            frac = s.substring(idx + 1)
            if (frac.isEmpty() || frac.any { !it.isDigit() } || frac.length > maxFractionDigits) return null
        } else {
            intRaw = s
            frac = ""
        }
        val groupSep = setOf(',', '.', ' ', '\'') - setOfNotNull(decimalSep)
        if (intRaw.any { it in setOfNotNull(decimalSep) }) return null
        val groups = intRaw.split(*groupSep.toCharArray())
        if (groups.size > 1 && !validGrouping(groups)) return null
        val integer = groups.joinToString("")
        if (integer.any { !it.isDigit() }) return null
        return Split(integer.trimStart('0').ifEmpty { if (integer.isEmpty()) "" else "0" }, frac, ambiguous)
    }

    /** Accepts Western (1,234,567) and South Asian lakh (12,34,567) grouping. */
    private fun validGrouping(groups: List<String>): Boolean {
        if (groups.any { it.isEmpty() }) return false
        if (groups.first().length > 3) return false
        if (groups.last().length != 3) return false
        val middle = groups.drop(1).dropLast(1)
        val western = middle.all { it.length == 3 }
        val lakh = middle.all { it.length == 2 } && groups.first().length <= 2
        return western || lakh
    }
}
