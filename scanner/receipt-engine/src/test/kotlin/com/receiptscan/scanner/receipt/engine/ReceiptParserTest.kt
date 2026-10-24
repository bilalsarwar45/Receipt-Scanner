package com.receiptscan.scanner.receipt.engine

import com.google.common.truth.Truth.assertThat
import com.receiptscan.money.Currencies
import com.receiptscan.money.Money
import com.receiptscan.ocr.model.SyntheticOcr
import com.receiptscan.scanner.contracts.ValueSource
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class ReceiptParserTest {
    private val parser = ReceiptParser(ReceiptParserConfig(today = LocalDate.of(2026, 10, 24)))
    private fun pkr(v: String) = Money.fromDecimal(BigDecimal(v), Currencies.PKR)

    private val freshMart = """
        FRESHMART
        Plot 12, Main Boulevard, Lahore
        Tel 042-1234567
        Date: 23/10/2026 18:42   Inv# FM-20931
        Milk 1L 2 x 320.00 | 640.00
        Bread | 240.00
        Rice 5kg 1 x 1,200.00 | 1,200.00
        Eggs 12 | 550.00
        Cooking oil | 750.00
        Apples | 460.00
        Subtotal | 3,840.00
        GST 16% on taxable | 160.00
        Discount | -200.00
        TOTAL | 3,800.00
        Cash | 4,000.00
        Change | 200.00
        Thank you for shopping!
    """

    @Test fun `handover FreshMart receipt parses with exact reconciliation`() {
        val r = parser.parse(SyntheticOcr.document(freshMart))
        assertThat(r.merchant?.value).isEqualTo("Freshmart")
        assertThat(r.date?.value).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(r.time?.value.toString()).isEqualTo("18:42")
        assertThat(r.reference?.value).isEqualTo("FM-20931")
        assertThat(r.currency.source).isEqualTo(ValueSource.SUGGESTED)
        assertThat(r.items.map { it.description.value }).containsExactly("Milk 1L", "Bread", "Rice 5kg", "Eggs 12", "Cooking oil", "Apples").inOrder()
        assertThat(r.items[0].quantity?.value).isEqualTo(BigDecimal("2"))
        assertThat(r.items[0].unitPrice?.value).isEqualTo(pkr("320"))
        assertThat(r.items.map { it.lineTotal.value.minor }).containsExactly(64_000L, 24_000L, 120_000L, 55_000L, 75_000L, 46_000L).inOrder()
        assertThat(r.subtotal?.value).isEqualTo(pkr("3840"))
        assertThat(r.total?.value).isEqualTo(pkr("3800"))
        assertThat(r.tendered?.value).isEqualTo(pkr("4000"))
        assertThat(r.change?.value).isEqualTo(pkr("200"))
        assertThat(r.reconciliation.matches).isTrue()
        assertThat(r.reconciliation.formula).isEqualTo("Subtotal 3,840.00 + Tax 160.00 − Discount 200.00 = PKR 3,800.00")
        assertThat(r.adjustments.first { it.role == RowRole.TAX }.percent).isEqualTo(BigDecimal("16"))
        assertThat(r.issues.map { it.code }).isEmpty()
        assertThat(r.suggestedCategory?.value).isEqualTo("groceries")
        assertThat(r.items.first().suggestedCategory).isEqualTo("groceries")
    }

    @Test fun `cash tendered and change are never items or totals`() {
        val r = parser.parse(SyntheticOcr.document(freshMart))
        assertThat(r.items.none { it.description.value.contains("Cash", true) || it.description.value.contains("Change", true) }).isTrue()
    }

    @Test fun `repeated total lines are one total`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                CORNER CAFE
                23 Oct 2026
                Latte | 560.00
                Club sandwich | 1,120.00
                Total | 1,680.00
                Grand Total | 1,680.00
                Card | 1,680.00
                Total | 1,680.00
                """,
            ),
        )
        assertThat(r.total?.value).isEqualTo(pkr("1680"))
        assertThat(r.items).hasSize(2)
        assertThat(r.reconciliation.matches).isTrue()
        assertThat(r.paymentMethod?.value).isEqualTo("Card")
    }

    @Test fun `missing total produces a partial result and an issue`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                DAILY BAKE
                21/09/2026
                Bun | 150.00
                Cake slice | 300.00
                """,
            ),
        )
        assertThat(r.total).isNull()
        assertThat(r.items).hasSize(2)
        assertThat(r.reconciliation.itemsSum).isEqualTo(pkr("450"))
        assertThat(r.issues.map { it.code }).contains("total-missing")
    }

    @Test fun `totals mismatch is reported with the difference not hidden`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                SHOP
                01/10/2026
                Item A | 100.00
                Item B | 200.00
                Total | 350.00
                """,
            ),
        )
        assertThat(r.reconciliation.matches).isFalse()
        assertThat(r.reconciliation.difference).isEqualTo(pkr("50"))
        assertThat(r.issues.first { it.code == "totals-mismatch" }.message).isEqualTo("Items add up to PKR 300.00, total says PKR 350.00")
    }

    @Test fun `tax included in prices is inferred only when arithmetic proves it`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                SHOP
                01/10/2026
                Item A | 1,160.00
                Sales Tax 16% | 160.00
                Total | 1,160.00
                """,
            ),
        )
        assertThat(r.reconciliation.matches).isTrue()
        assertThat(r.reconciliation.taxesIncluded).isEqualTo(pkr("160"))
        assertThat(r.issues.map { it.code }).contains("tax-included-inferred")
    }

    @Test fun `wrapped descriptions and column quantities`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                TECH HUB
                22-10-2026
                Wireless headphones
                Model TH-8842 | 1 | 6,500.00 | 6,500.00
                Charger | 2 | 750.00 | 1,500.00
                Total | 8,000.00
                """,
            ),
        )
        assertThat(r.items.map { it.description.value }).containsExactly("Wireless headphones Model TH-8842", "Charger").inOrder()
        assertThat(r.items[1].quantity?.value).isEqualTo(BigDecimal("2"))
        assertThat(r.items[1].unitPrice?.value).isEqualTo(pkr("750"))
        assertThat(r.reconciliation.matches).isTrue()
    }

    @Test fun `quantity arithmetic mismatch flags the line`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                SHOP
                01/10/2026
                Milk 2 x 320.00 | 600.00
                Total | 600.00
                """,
            ),
        )
        assertThat(r.items.single().uncertain).isTrue()
        assertThat(r.issues.map { it.code }).contains("item-uncertain")
    }

    @Test fun `long receipt parts with an overlapping line count once`() {
        val doc = SyntheticOcr.document(
            """
            FRESHMART
            23/10/2026
            Milk | 640.00
            Bread | 240.00
            Rice | 1,200.00
            """,
            """
            Rice | 1,200.00
            Eggs | 550.00
            Total | 2,630.00
            """,
        )
        val r = parser.parse(doc)
        assertThat(r.overlapRowsDropped).isEqualTo(1)
        assertThat(r.items).hasSize(4)
        assertThat(r.reconciliation.matches).isTrue()
    }

    @Test fun `explicit currency and lakh amounts`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                CITY MOTORS
                05/10/2026
                Service package | Rs. 1,25,000
                Total Payable | Rs. 1,25,000/-
                """,
            ),
        )
        assertThat(r.currency.value).isEqualTo(Currencies.PKR)
        assertThat(r.currency.source).isEqualTo(ValueSource.FROM_PHOTO)
        assertThat(r.total?.value).isEqualTo(pkr("125000"))
    }

    @Test fun `ambiguous date asks instead of guessing`() {
        val r = parser.parse(SyntheticOcr.document("SHOP\n04/05/2026\nTea | 100.00\nTotal | 100.00"))
        assertThat(r.date?.value).isEqualTo(LocalDate.of(2026, 5, 4))
        assertThat(r.dateAlternatives).containsExactly(LocalDate.of(2026, 4, 5))
        assertThat(r.issues.map { it.code }).contains("date-ambiguous")
    }

    @Test fun `empty or noise input does not invent items`() {
        val r = parser.parse(SyntheticOcr.document("Thank you\nVisit again"))
        assertThat(r.items).isEmpty()
        assertThat(r.total).isNull()
    }

    @Test fun `low confidence lines are highlighted`() {
        val page = SyntheticOcr.page("SHOP\n01/10/2026\nMilk | 640.00\nTotal | 640.00")
        val lowered = page.copy(blocks = page.blocks.map { b -> if (b.text == "640.00") b.copy(lines = b.lines.map { it.copy(confidence = 0.3f) }) else b })
        val r = parser.parse(com.receiptscan.ocr.model.OcrDocument(listOf(lowered), "t", "1"))
        assertThat(r.items.single().uncertainReasons).contains("Hard to read · check price")
    }
}
