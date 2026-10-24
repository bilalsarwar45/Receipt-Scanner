package com.receiptscan.time

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display formats used in the design: "24 Oct 2026" and ranges "25 Sep – 24 Oct 2026". */
object DisplayDates {
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun format(date: LocalDate): String = full.format(date)

    fun formatRange(range: LocalDateRange): String {
        val a = range.startInclusive
        val b = range.endInclusive
        return when {
            a == b -> format(a)
            a.year == b.year -> "${dayMonth.format(a)} – ${full.format(b)}"
            else -> "${full.format(a)} – ${full.format(b)}"
        }
    }

    /** "Today", "Yesterday" or the date, relative to [today]. */
    fun relative(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> format(date)
    }
}
