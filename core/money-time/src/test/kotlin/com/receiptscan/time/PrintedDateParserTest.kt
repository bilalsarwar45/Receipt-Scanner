package com.receiptscan.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PrintedDateParserTest {
    private val ref = LocalDate.of(2026, 10, 24)
    private fun first(t: String, order: DateOrder = DateOrder.DMY) = PrintedDateParser.findAll(t, ref, order).first()

    @Test fun `common receipt formats`() {
        assertThat(first("Date: 23/10/2026 18:42").date).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(first("2026-10-23").date).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(first("23-Oct-26").date).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(first("23 October 2026").date).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(first("Oct 23, 2026").date).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(first("23.10.26").date).isEqualTo(LocalDate.of(2026, 10, 23))
    }

    @Test fun `ambiguous day month is offered both ways`() {
        val d = first("04/05/2026")
        assertThat(d.date).isEqualTo(LocalDate.of(2026, 5, 4))
        assertThat(d.alternatives).containsExactly(LocalDate.of(2026, 4, 5))
        assertThat(first("04/05/2026", DateOrder.MDY).date).isEqualTo(LocalDate.of(2026, 4, 5))
        assertThat(first("23/10/2026").isAmbiguous).isFalse()
        assertThat(first("05/05/2026").isAmbiguous).isFalse()
    }

    @Test fun `month only expiry dates`() {
        val d = first("EXP 11/2027")
        assertThat(d.monthOnly).isTrue()
        assertThat(d.date).isEqualTo(LocalDate.of(2027, 11, 30))
        assertThat(first("BEST BEFORE MAR 2027").date).isEqualTo(LocalDate.of(2027, 3, 31))
    }

    @Test fun `missing year uses the most recent past date`() {
        val d = first("Date 28 Dec")
        assertThat(d.date).isEqualTo(LocalDate.of(2025, 12, 28))
        assertThat(d.yearInferred).isTrue()
    }

    @Test fun `times`() {
        assertThat(PrintedDateParser.findTime("23/10/2026 18:42")).isEqualTo(LocalTime.of(18, 42))
        assertThat(PrintedDateParser.findTime("6:42 PM")).isEqualTo(LocalTime.of(18, 42))
    }

    @Test fun `invalid dates are ignored`() {
        assertThat(PrintedDateParser.findAll("31/02/2026", ref)).isEmpty()
        assertThat(PrintedDateParser.findAll("Total 3,800.00", ref)).isEmpty()
    }
}
