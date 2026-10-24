package com.receiptscan.insights

import com.receiptscan.expenses.CategoryTotal
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseQueryResult
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import com.receiptscan.time.LocalDateRange
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class InsightPeriod { WEEK, MONTH, YEAR }

data class MerchantTotal(val merchant: String, val total: Money, val count: Int)
data class SourceBreakdown(val source: ExpenseSource, val total: Money, val count: Int)
data class TimeBucket(val label: String, val start: LocalDate, val total: Money)

/** Everything the Insights screen shows for one currency. Values come only from confirmed ledger entries. */
data class InsightsSummary(
    val range: LocalDateRange,
    val currencyCode: String,
    val total: Money,
    val count: Int,
    val categories: List<CategoryTotal>,
    val merchants: List<MerchantTotal>,
    val sources: List<SourceBreakdown>,
    val buckets: List<TimeBucket>,
    val otherCurrencies: List<Money>,
)

object InsightsCalculator {
    fun range(period: InsightPeriod, today: LocalDate): LocalDateRange = when (period) {
        InsightPeriod.WEEK -> LocalDateRange(today.minusDays(6), today.plusDays(1))
        InsightPeriod.MONTH -> LocalDateRange(today.withDayOfMonth(1), today.plusDays(1))
        InsightPeriod.YEAR -> LocalDateRange(today.withDayOfYear(1), today.plusDays(1))
    }

    fun summarize(result: ExpenseQueryResult, range: LocalDateRange, period: InsightPeriod, preferredCurrency: String): InsightsSummary? {
        if (result.isEmpty) return null
        val main = result.totals.firstOrNull { it.total.currency.code == preferredCurrency } ?: result.totals.first()
        val code = main.total.currency.code
        val entries = result.entries.filter { it.amount.currency.code == code }
        val zero = Money.zero(main.total.currency)
        val merchants = entries.filter { it.merchant != null }.groupBy { it.merchant!!.trim() }
            .map { (m, l) -> MerchantTotal(m, l.fold(zero) { a, e -> a + e.signedAmount }, l.size) }
            .sortedByDescending { it.total.minor }.take(5)
        val sources = entries.groupBy { it.source }.map { (s, l) -> SourceBreakdown(s, l.fold(zero) { a, e -> a + e.signedAmount }, l.size) }
        return InsightsSummary(
            range, code, main.total, main.count, result.byCategory[code].orEmpty(), merchants, sources,
            buckets(entries, range, period, zero), result.totals.filter { it.total.currency.code != code }.map { it.total },
        )
    }

    private fun buckets(entries: List<ExpenseEntry>, range: LocalDateRange, period: InsightPeriod, zero: Money): List<TimeBucket> {
        val out = mutableListOf<TimeBucket>()
        when (period) {
            InsightPeriod.WEEK, InsightPeriod.MONTH -> {
                var d = range.startInclusive
                while (d.isBefore(range.endExclusive)) {
                    val day = d
                    out += TimeBucket(day.dayOfMonth.toString(), day, entries.filter { it.effectiveDate == day }.fold(zero) { a, e -> a + e.signedAmount })
                    d = d.plusDays(1)
                }
            }
            InsightPeriod.YEAR -> {
                var m = YearMonth.from(range.startInclusive)
                val end = YearMonth.from(range.endInclusive)
                while (!m.isAfter(end)) {
                    val ym = m
                    out += TimeBucket(ym.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, ym.atDay(1), entries.filter { YearMonth.from(it.effectiveDate) == ym }.fold(zero) { a, e -> a + e.signedAmount })
                    m = m.plusMonths(1)
                }
            }
        }
        return out
    }

    /** Trends unlock after confirmed spending in at least 2 distinct calendar months (handover I-Limited). */
    fun trendProgress(allConfirmed: List<ExpenseEntry>): Pair<Int, Boolean> {
        val months = allConfirmed.map { YearMonth.from(it.effectiveDate) }.distinct().size
        return months.coerceAtMost(2) to (months >= 2)
    }
}

/** A user-set monthly limit, overall or for one category. */
data class Budget(val id: String, val categoryId: String?, val limit: Money, val warnAtPercent: Int = 80)

data class BudgetStatus(val budget: Budget, val spent: Money, val percent: Int, val state: State, val message: String) {
    enum class State { UNDER, WARNING, OVER }
}

object Budgets {
    fun status(budget: Budget, spent: Money): BudgetStatus {
        val pct = if (budget.limit.minor == 0L) 100 else ((spent.minor * 100) / budget.limit.minor).toInt()
        val state = when {
            spent.minor > budget.limit.minor -> BudgetStatus.State.OVER
            pct >= budget.warnAtPercent -> BudgetStatus.State.WARNING
            else -> BudgetStatus.State.UNDER
        }
        val left = budget.limit - spent
        val msg = when (state) {
            BudgetStatus.State.OVER -> "Over by ${MoneyFormat.format(-left)} (recorded expenses)"
            else -> "${MoneyFormat.format(left)} left of ${MoneyFormat.format(budget.limit)}"
        }
        return BudgetStatus(budget, spent, pct, state, msg)
    }

    /** Whether a notification should fire: only when crossing into WARNING or OVER, never repeatedly. */
    fun shouldNotify(previous: BudgetStatus.State?, now: BudgetStatus.State): Boolean =
        now != BudgetStatus.State.UNDER && (previous == null || now.ordinal > previous.ordinal)
}

/**
 * Transparent month-end projection: spent so far + (average daily spend over the last [lookbackDays]
 * recorded days) × days remaining. Needs at least [minHistoryDays] of history; otherwise no forecast.
 */
data class Forecast(val projected: Money, val dailyAverage: Money, val daysRemaining: Long, val formula: String, val note: String)

object Forecaster {
    fun monthEnd(entries: List<ExpenseEntry>, today: LocalDate, firstRecordDate: LocalDate?, lookbackDays: Int = 28, minHistoryDays: Int = 21): Forecast? {
        if (entries.isEmpty() || firstRecordDate == null) return null
        if (ChronoUnit.DAYS.between(firstRecordDate, today) + 1 < minHistoryDays) return null
        val cur = entries.first().amount.currency
        if (entries.any { it.amount.currency != cur }) return null
        val zero = Money.zero(cur)
        val windowStart = today.minusDays(lookbackDays.toLong() - 1)
        val window = entries.filter { !it.effectiveDate.isBefore(windowStart) && !it.effectiveDate.isAfter(today) }
        val windowTotal = window.fold(zero) { a, e -> a + e.signedAmount }
        val daily = Money.fromDecimal(windowTotal.toDecimal().divide(BigDecimal(lookbackDays), cur.fractionDigits, RoundingMode.HALF_EVEN), cur)
        val monthStart = today.withDayOfMonth(1)
        val spent = entries.filter { !it.effectiveDate.isBefore(monthStart) && !it.effectiveDate.isAfter(today) }.fold(zero) { a, e -> a + e.signedAmount }
        val remaining = ChronoUnit.DAYS.between(today, today.withDayOfMonth(today.lengthOfMonth()))
        val projected = spent + daily.times(remaining)
        return Forecast(
            projected, daily, remaining,
            "${MoneyFormat.format(spent, false)} so far + ${MoneyFormat.format(daily, false)}/day × $remaining days",
            "Based on recorded expenses only. Missing receipts make this lower than real spending.",
        )
    }
}
