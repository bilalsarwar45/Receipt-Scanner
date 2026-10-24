package com.receiptscan.scanner.invoice.engine

import com.google.common.truth.Truth.assertThat
import com.receiptscan.ocr.model.SyntheticOcr
import org.junit.Test
import java.time.LocalDate

class InvoiceParserTest {
    private val parser = InvoiceParser(LocalDate.of(2026, 10, 24))

    @Test fun `utility bill with due and after-due amounts`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                CITY POWER COMPANY
                Electricity Bill
                Consumer No: 04 1234 5678 900
                Bill Month: OCT 2026
                Issue Date | 20 Oct 2026
                Due Date | 30 Oct 2026
                Electricity Duty | 120.00
                GST | 1,140.00
                Payable Within Due Date | Rs. 8,240
                Payable After Due Date | Rs. 8,650
                """,
            ),
        )
        assertThat(r.supplier?.value).isEqualTo("CITY POWER COMPANY")
        assertThat(r.dueDate?.value).isEqualTo(LocalDate.of(2026, 10, 30))
        assertThat(r.issueDate?.value).isEqualTo(LocalDate.of(2026, 10, 20))
        assertThat(r.amountDue?.value?.minor).isEqualTo(824_000)
        assertThat(r.amountAfterDue?.value?.minor).isEqualTo(865_000)
        assertThat(r.accountNumber?.value).isEqualTo("04 1234 5678 900")
        assertThat(r.billingPeriod?.value).isEqualTo("OCT 2026")
        assertThat(r.taxes.map { it.second.value.minor }).containsExactly(12_000L, 114_000L)
        assertThat(r.paidEvidence).isNull()
        assertThat(r.issues).isEmpty()
    }

    @Test fun `supplier invoice with number and total`() {
        val r = parser.parse(
            SyntheticOcr.document(
                """
                Nayatel Pvt Ltd
                Invoice No: NT-55120
                Invoice Date: 01/10/2026
                Payment Due: 15/10/2026
                Internet 50 Mbps | 4,500.00
                Sales Tax 19.5% | 877.50
                Total Amount Due | 5,377.50
                """,
            ),
        )
        assertThat(r.invoiceNumber?.value).isEqualTo("NT-55120")
        assertThat(r.amountDue?.value?.minor).isEqualTo(537_750)
        assertThat(r.dueDate?.value).isEqualTo(LocalDate.of(2026, 10, 15))
    }

    @Test fun `paid only with evidence and missing fields reported`() {
        val r = parser.parse(SyntheticOcr.document("Water Board\nStatus: PAID\nTotal | 900.00"))
        assertThat(r.paidEvidence).isNotNull()
        assertThat(r.issues.map { it.code }).contains("due-missing")
        val r2 = parser.parse(SyntheticOcr.document("Water Board\nAmount payable | 900.00"))
        assertThat(r2.paidEvidence).isNull()
    }
}
