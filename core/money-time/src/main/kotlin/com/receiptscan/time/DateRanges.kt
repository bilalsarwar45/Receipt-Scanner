package com.receiptscan.time

import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * A half-open range of local calendar dates: [startInclusive, endExclusive).
 * Expense queries use effective local dates, so "last 30 days" never depends on milliseconds or DST.
 */
data class LocalDateRange(val startInclusive: LocalDate, val endExclusive: LocalDate) {
    init {
        require(!endExclusive.isBefore(startInclusive)) { "Range end before start" }
    }

    val endInclusive: LocalDate get() = endExclusive.minusDays(1)
    val dayCount: Long get() = java.time.temporal.ChronoUnit.DAYS.between(startInclusive, endExclusive)

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(startInclusive) && date.isBefore(endExclusive)

    /** Instant boundaries using start-of-day in [zone], which is correct across DST changes. */
    fun toInstants(zone: ZoneId): Pair<Instant, Instant> =
        startInclusive.atStartOfDay(zone).toInstant() to endExclusive.atStartOfDay(zone).toInstant()

    companion object {
        fun single(date: LocalDate) = LocalDateRange(date, date.plusDays(1))
        fun inclusive(start: LocalDate, endInclusive: LocalDate) = LocalDateRange(start, endInclusive.plusDays(1))
    }
}

/** A relative or explicit period as people say it. Resolved against a clock and reporting zone. */
sealed interface DatePeriod {
    data object Today : DatePeriod
    data object Yesterday : DatePeriod
    /** Today plus the previous n-1 local dates. */
    data class LastNDays(val days: Int) : DatePeriod {
        init { require(days in 1..3660) }
    }
    data object ThisWeek : DatePeriod
    /** The previous calendar week, Monday to Sunday. */
    data object LastWeek : DatePeriod
    data object ThisMonth : DatePeriod
    /** The previous calendar month. */
    data object LastMonth : DatePeriod
    data object ThisYear : DatePeriod
    data class OnDate(val date: LocalDate) : DatePeriod
    data class Between(val range: LocalDateRange) : DatePeriod
    data class Month(val year: Int, val month: Int) : DatePeriod
}

/**
 * Resolves periods using an injectable [Clock] and the user-visible reporting zone
 * (defaults to the device zone; tests use Asia/Karachi).
 */
class DateResolver(private val clock: Clock, val zone: ZoneId) {

    fun today(): LocalDate = LocalDate.now(clock.withZone(zone))

    fun resolve(period: DatePeriod): LocalDateRange {
        val today = today()
        return when (period) {
            DatePeriod.Today -> LocalDateRange.single(today)
            DatePeriod.Yesterday -> LocalDateRange.single(today.minusDays(1))
            is DatePeriod.LastNDays -> LocalDateRange(today.minusDays(period.days.toLong() - 1), today.plusDays(1))
            DatePeriod.ThisWeek -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                LocalDateRange(monday, today.plusDays(1))
            }
            DatePeriod.LastWeek -> {
                val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                LocalDateRange(thisMonday.minusWeeks(1), thisMonday)
            }
            DatePeriod.ThisMonth -> LocalDateRange(today.withDayOfMonth(1), today.plusDays(1))
            DatePeriod.LastMonth -> {
                val first = today.withDayOfMonth(1)
                LocalDateRange(first.minusMonths(1), first)
            }
            DatePeriod.ThisYear -> LocalDateRange(today.withDayOfYear(1), today.plusDays(1))
            is DatePeriod.OnDate -> LocalDateRange.single(period.date)
            is DatePeriod.Between -> period.range
            is DatePeriod.Month -> {
                val first = LocalDate.of(period.year, period.month, 1)
                LocalDateRange(first, first.plusMonths(1))
            }
        }
    }

    /** The local date an instant falls on in the reporting zone. */
    fun localDateOf(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()
}
