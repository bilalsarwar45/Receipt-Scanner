package com.receiptscan.insights

import com.google.common.truth.Truth.assertThat
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseKind
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.ExpenseState
import com.receiptscan.money.Currencies
import com.receiptscan.money.Money
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class InsightsTest {
    private fun pkr(v: String) = Money.fromDecimal(BigDecimal(v), Currencies.PKR)
    private fun e(date: LocalDate, amt: String) = ExpenseEntry(date.toString() + amt, pkr(amt), ExpenseKind.PURCHASE, date, Instant.EPOCH, "UTC", "groceries", null, null, ExpenseSource.MANUAL, ExpenseState.CONFIRMED)

    @Test fun `budget states and single notifications`() {
        val b = Budget("b", null, pkr("10000"))
        assertThat(Budgets.status(b, pkr("5000")).state).isEqualTo(BudgetStatus.State.UNDER)
        assertThat(Budgets.status(b, pkr("8500")).state).isEqualTo(BudgetStatus.State.WARNING)
        val over = Budgets.status(b, pkr("10500"))
        assertThat(over.state).isEqualTo(BudgetStatus.State.OVER)
        assertThat(over.message).isEqualTo("Over by PKR 500.00 (recorded expenses)")
        assertThat(Budgets.shouldNotify(BudgetStatus.State.WARNING, BudgetStatus.State.WARNING)).isFalse()
        assertThat(Budgets.shouldNotify(BudgetStatus.State.WARNING, BudgetStatus.State.OVER)).isTrue()
    }

    @Test fun `forecast needs history and shows its formula`() {
        val today = LocalDate.of(2026, 10, 24)
        val entries = (0 until 28).map { e(today.minusDays(it.toLong()), "100") }
        assertThat(Forecaster.monthEnd(entries, today, today.minusDays(5))).isNull()
        val f = Forecaster.monthEnd(entries, today, today.minusDays(27))!!
        assertThat(f.dailyAverage).isEqualTo(pkr("100"))
        assertThat(f.daysRemaining).isEqualTo(7)
        assertThat(f.projected).isEqualTo(pkr("3100")) // 24 days × 100 so far + 7 × 100
    }

    @Test fun `trends unlock after two months`() {
        assertThat(InsightsCalculator.trendProgress(listOf(e(LocalDate.of(2026, 10, 1), "1")))).isEqualTo(1 to false)
        assertThat(InsightsCalculator.trendProgress(listOf(e(LocalDate.of(2026, 10, 1), "1"), e(LocalDate.of(2026, 9, 1), "1"))).second).isTrue()
    }
}
