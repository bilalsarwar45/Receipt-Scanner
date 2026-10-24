package com.receiptscan.actions

import com.google.common.truth.Truth.assertThat
import com.receiptscan.money.Currencies
import com.receiptscan.money.Money
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ActionsTest {
    private fun pkr(v: String) = Money.fromDecimal(BigDecimal(v), Currencies.PKR)
    private val zero = pkr("0")

    @Test fun `handover corner cafe split three ways`() {
        val r = SplitBill.split(listOf("A", "B", "C"), listOf(SplitLine("1", "Lunch", pkr("1680"))), zero, zero, zero, zero, SplitMode.Equally) as SplitResult.Ok
        assertThat(r.shares.map { it.total }).containsExactly(pkr("560"), pkr("560"), pkr("560"))
    }

    @Test fun `by item split with tax service discount tip adds up exactly`() {
        val lines = listOf(SplitLine("a", "Burger", pkr("1000")), SplitLine("b", "Fries", pkr("333.33")), SplitLine("c", "Shake", pkr("500")))
        val r = SplitBill.split(
            listOf("Ali", "Sara", "Omar"), lines, tax = pkr("293.33"), service = pkr("100"), discount = pkr("50.01"), tip = pkr("77.77"),
            mode = SplitMode.ByItem(mapOf("a" to setOf("Ali"), "b" to setOf("Ali", "Sara", "Omar"), "c" to setOf("Sara"))),
        ) as SplitResult.Ok
        assertThat(r.total).isEqualTo(pkr("2254.42"))
        assertThat(r.shares.fold(zero) { a, s -> a + s.total }).isEqualTo(r.total)
        assertThat(r.shares.first { it.person == "Omar" }.items).isEqualTo(pkr("111.11"))
    }

    @Test fun `custom split must assign everything`() {
        val r = SplitBill.split(listOf("A", "B"), listOf(SplitLine("1", "x", pkr("100"))), zero, zero, zero, zero, SplitMode.Custom(mapOf("A" to pkr("60"))))
        assertThat((r as SplitResult.Invalid).message).isEqualTo("PKR 40.00 left to assign")
    }

    @Test fun `unassigned item is reported`() {
        val r = SplitBill.split(listOf("A"), listOf(SplitLine("1", "Tea", pkr("100"))), zero, zero, zero, zero, SplitMode.ByItem(emptyMap()))
        assertThat((r as SplitResult.Invalid).message).isEqualTo("Assign “Tea” to someone")
    }

    @Test fun `price comparison normalises pack size`() {
        val old = PricePoint("r0", "FreshMart", LocalDate.of(2026, 7, 14), "Rice 5kg", pkr("1150"), PackSize.from("Rice 5kg"))
        val new = PricePoint("r1", "FreshMart", LocalDate.of(2026, 10, 23), "Rice 5kg", pkr("1200"), PackSize.from("Rice 5kg"))
        val c = PriceCompare.compare(listOf(new, old)) as Comparison.Change
        assertThat(c.percent).isEqualTo(BigDecimal("4.3"))
        assertThat(c.unitLabel).isEqualTo("per kg")
        assertThat(c.latestPerUnit).isEqualTo(pkr("240"))
        // A different pack size is not reported as a price rise.
        val bigger = PricePoint("r2", "X", LocalDate.of(2026, 10, 24), "Rice 10kg", pkr("2300"), PackSize.from("Rice 10kg"))
        val c2 = PriceCompare.compare(listOf(new, bigger)) as Comparison.Change
        assertThat(c2.percent).isEqualTo(BigDecimal("-4.2"))
        val unknown = PricePoint("r3", "X", LocalDate.of(2026, 10, 25), "Rice", pkr("2300"), null)
        assertThat(PriceCompare.compare(listOf(new, unknown))).isInstanceOf(Comparison.NotComparable::class.java)
        assertThat(PriceCompare.compare(listOf(new))).isInstanceOf(Comparison.NoHistory::class.java)
    }

    @Test fun `csv neutralises formulas and quotes`() {
        val e = com.receiptscan.expenses.ExpenseEntry("1", pkr("1500"), com.receiptscan.expenses.ExpenseKind.PURCHASE, LocalDate.of(2026, 10, 1), java.time.Instant.EPOCH, "UTC", "groceries", "=HYPERLINK(\"x\")", "a, b", com.receiptscan.expenses.ExpenseSource.MANUAL, com.receiptscan.expenses.ExpenseState.CONFIRMED)
        val csv = CsvExport.expenses(listOf(e))
        assertThat(csv).contains("1500.00,PKR")
        assertThat(csv).contains("\"'=HYPERLINK(\"\"x\"\")\"")
        assertThat(csv).contains("\"a, b\"")
    }
}
