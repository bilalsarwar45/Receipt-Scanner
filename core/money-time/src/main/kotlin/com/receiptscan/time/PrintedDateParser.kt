package com.receiptscan.time

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.YearMonth

/** Order to prefer when a numeric date such as 04/05/2026 can be read both ways. */
enum class DateOrder { DMY, MDY, YMD }

/** A date found in printed text. [alternatives] is non-empty when the text could mean another date. */
data class PrintedDate(
    val date: LocalDate,
    val raw: String,
    val alternatives: List<LocalDate> = emptyList(),
    /** True when the year was missing or two-digit and was completed by a rule. */
    val yearInferred: Boolean = false,
    /** True for month-only dates such as "EXP 11/2027"; [date] is the last day of that month. */
    val monthOnly: Boolean = false,
) {
    val isAmbiguous: Boolean get() = alternatives.isNotEmpty()
}

/**
 * Finds dates and times in OCR text. Never silently resolves an ambiguous day/month order:
 * the preferred reading comes first and the other is listed in [PrintedDate.alternatives].
 */
object PrintedDateParser {

    private val months = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3, "apr" to 4, "april" to 4,
        "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7, "aug" to 8, "august" to 8, "sep" to 9, "sept" to 9,
        "september" to 9, "oct" to 10, "october" to 10, "nov" to 11, "november" to 11, "dec" to 12, "december" to 12,
    )
    private const val MON = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"

    private val ymd = Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)""")
    private val numeric = Regex("""(?<!\d)(\d{1,2})[-/.](\d{1,2})[-/.](\d{2}|\d{4})(?!\d)""")
    private val dMonY = Regex("""(?i)(?<!\d)(\d{1,2})(?:st|nd|rd|th)?[\s\-/.]*$MON[\s\-/.,]*(\d{2}|\d{4})?(?![a-z\d])""")
    private val monDY = Regex("""(?i)(?<![a-z])$MON[\s\-/.]*(\d{1,2})(?:st|nd|rd|th)?,?[\s\-/.]+(\d{4})(?!\d)""")
    private val monY = Regex("""(?i)(?<![a-z])$MON[\s\-/.,]*(\d{4})(?!\d)""")
    private val numMonthYear = Regex("""(?<![\d/.-])(\d{1,2})[/.-](\d{4})(?![\d/.-])""")
    private val time = Regex("""(?i)(?<!\d)([01]?\d|2[0-3])[:.]([0-5]\d)(?::([0-5]\d))?\s*(am|pm)?(?!\d)""")

    /**
     * @param reference today's date; used to complete two-digit years and to reject dates far in the future
     * when [maxFutureDays] is set.
     */
    fun findAll(text: String, reference: LocalDate, order: DateOrder = DateOrder.DMY, maxFutureDays: Long? = null): List<PrintedDate> {
        val out = mutableListOf<Pair<IntRange, PrintedDate>>()
        fun overlaps(r: IntRange) = out.any { it.first.first <= r.last && r.first <= it.first.last }
        fun add(r: IntRange, d: PrintedDate?) {
            if (d != null && !overlaps(r)) out += r to d
        }
        ymd.findAll(text).forEach { m ->
            val (y, mo, d) = m.destructured
            add(m.range, safe(y.toInt(), mo.toInt(), d.toInt())?.let { PrintedDate(it, m.value) })
        }
        dMonY.findAll(text).forEach { m ->
            val day = m.groupValues[1].toInt()
            val mon = monthOf(m.groupValues[2])
            val (year, inferred) = completeYear(m.groupValues[3], mon, day, reference)
            add(m.range, safe(year, mon, day)?.let { PrintedDate(it, m.value.trim(), yearInferred = inferred) })
        }
        monDY.findAll(text).forEach { m ->
            val mon = monthOf(m.groupValues[1])
            add(m.range, safe(m.groupValues[3].toInt(), mon, m.groupValues[2].toInt())?.let { PrintedDate(it, m.value.trim()) })
        }
        numeric.findAll(text).forEach { m ->
            if (overlaps(m.range)) return@forEach
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val (year, inferred) = completeYear(m.groupValues[3], null, null, reference)
            val dmy = safe(year, b, a)
            val mdy = safe(year, a, b)
            val preferred = if (order == DateOrder.MDY) mdy ?: dmy else dmy ?: mdy
            val other = if (preferred == dmy) mdy else dmy
            if (preferred != null) {
                val alts = if (other != null && other != preferred) listOf(other) else emptyList()
                add(m.range, PrintedDate(preferred, m.value, alts, yearInferred = inferred))
            }
        }
        monY.findAll(text).forEach { m ->
            if (overlaps(m.range)) return@forEach
            val ym = YearMonth.of(m.groupValues[2].toInt(), monthOf(m.groupValues[1]))
            add(m.range, PrintedDate(ym.atEndOfMonth(), m.value.trim(), monthOnly = true))
        }
        numMonthYear.findAll(text).forEach { m ->
            if (overlaps(m.range)) return@forEach
            val mo = m.groupValues[1].toInt()
            if (mo in 1..12) {
                add(m.range, PrintedDate(YearMonth.of(m.groupValues[2].toInt(), mo).atEndOfMonth(), m.value, monthOnly = true))
            }
        }
        return out.sortedBy { it.first.first }.map { it.second }.filter { d ->
            maxFutureDays == null || !d.date.isAfter(reference.plusDays(maxFutureDays))
        }.filter { it.date.year in 1990..2100 }
    }

    fun findTime(text: String): LocalTime? {
        val m = time.find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        when (m.groupValues[4].lowercase()) {
            "pm" -> if (h < 12) h += 12
            "am" -> if (h == 12) h = 0
        }
        return runCatching { LocalTime.of(h, min) }.getOrNull()
    }

    private fun monthOf(s: String): Int {
        val k = s.lowercase()
        return months[k] ?: months[k.take(3)] ?: error("month $s")
    }

    private fun completeYear(text: String, month: Int?, day: Int?, reference: LocalDate): Pair<Int, Boolean> = when {
        text.isEmpty() -> {
            // No year: the most recent such date not after the reference date.
            val candidate = if (month != null && day != null) safe(reference.year, month, day) else null
            if (candidate != null && candidate.isAfter(reference)) (reference.year - 1) to true else reference.year to true
        }
        text.length == 2 -> (2000 + text.toInt()) to true
        else -> text.toInt() to false
    }

    private fun safe(y: Int, m: Int, d: Int): LocalDate? = try {
        if (m !in 1..12 || d !in 1..31) null else LocalDate.of(y, Month.of(m), d)
    } catch (_: DateTimeException) {
        null
    }
}
