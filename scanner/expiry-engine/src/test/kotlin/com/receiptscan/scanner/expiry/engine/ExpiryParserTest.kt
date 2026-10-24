package com.receiptscan.scanner.expiry.engine

import com.google.common.truth.Truth.assertThat
import com.receiptscan.ocr.model.SyntheticOcr
import org.junit.Test
import java.time.LocalDate

class ExpiryParserTest {
    private val today = LocalDate.of(2026, 10, 24)

    @Test fun `distinguishes manufacturing and expiry`() {
        val r = ExpiryParser.parse(SyntheticOcr.document("MFG 12/08/2026\nEXP 12/02/2027"), today)
        assertThat(r.candidates.map { it.kind }).containsExactly(DateKind.MANUFACTURED, DateKind.EXPIRY).inOrder()
        assertThat(r.primary?.date).isEqualTo(LocalDate.of(2027, 2, 12))
    }

    @Test fun `ambiguous date offers a choice`() {
        val r = ExpiryParser.parse(SyntheticOcr.document("Best before 04/05/2027"), today)
        assertThat(r.needsChoice).isTrue()
        assertThat(r.primary?.alternatives).containsExactly(LocalDate.of(2027, 4, 5))
        assertThat(r.message).isEqualTo("This date can be read two ways.")
    }

    @Test fun `manufacturing date alone does not become expiry`() {
        val r = ExpiryParser.parse(SyntheticOcr.document("PKD 01/10/2026"), today)
        assertThat(r.primary).isNull()
        assertThat(r.message).contains("shelf life")
    }

    @Test fun `label on previous line and month-only dates`() {
        val r = ExpiryParser.parse(SyntheticOcr.document("USE BY\n11/2027"), today)
        assertThat(r.primary?.kind).isEqualTo(DateKind.USE_BY)
        assertThat(r.primary?.monthOnly).isTrue()
    }
}
