package com.receiptscan.scanner.product.engine

import com.google.common.truth.Truth.assertThat
import com.receiptscan.ocr.model.SyntheticOcr
import org.junit.Test
import java.time.LocalDate
import java.time.Period

class ProductLabelTest {
    @Test fun `reads model serial barcode and warranty`() {
        val r = ProductLabelParser.parse(
            SyntheticOcr.document(
                """
                TechHub Wireless Headphones
                Model: TH-8842
                S/N: TH-8842-11A
                5901234123457
                12 month limited warranty
                14-day return
                """,
            ),
        )
        assertThat(r.model?.value).isEqualTo("TH-8842")
        assertThat(r.serial?.value).isEqualTo("TH-8842-11A")
        assertThat(r.barcode?.value).isEqualTo("5901234123457")
        assertThat(r.warrantyPeriod).isEqualTo(Period.ofMonths(12))
        assertThat(r.returnPeriod).isEqualTo(Period.ofDays(14))
    }

    @Test fun `handover warranty dates`() {
        val t = WarrantyTerms(LocalDate.of(2026, 10, 22), Period.ofMonths(12), Period.ofDays(14))
        assertThat(t.warrantyUntil).isEqualTo(LocalDate.of(2027, 10, 22))
        assertThat(t.returnBy).isEqualTo(LocalDate.of(2026, 11, 5))
        assertThat(WarrantyTerms(LocalDate.of(2026, 10, 22), null, null).warrantyUntil).isNull()
    }
}
