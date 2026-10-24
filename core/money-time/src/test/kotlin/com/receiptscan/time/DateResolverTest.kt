package com.receiptscan.time

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DateResolverTest {
    private val karachi = ZoneId.of("Asia/Karachi")
    // Frozen test clock: 24 Oct 2026, 10:30 in Karachi.
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 24, 10, 30, 0, 0, karachi).toInstant(), karachi)
    private val resolver = DateResolver(clock, karachi)

    @Test fun `last 30 days is today plus previous 29 dates`() {
        val r = resolver.resolve(DatePeriod.LastNDays(30))
        assertThat(r.startInclusive).isEqualTo(LocalDate.of(2026, 9, 25))
        assertThat(r.endInclusive).isEqualTo(LocalDate.of(2026, 10, 24))
        assertThat(r.endExclusive).isEqualTo(LocalDate.of(2026, 10, 25))
        assertThat(r.dayCount).isEqualTo(30)
        assertThat(DisplayDates.formatRange(r)).isEqualTo("25 Sep – 24 Oct 2026")
    }

    @Test fun `last month and last 30 days differ`() {
        val lm = resolver.resolve(DatePeriod.LastMonth)
        assertThat(lm).isEqualTo(LocalDateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
        assertThat(lm).isNotEqualTo(resolver.resolve(DatePeriod.LastNDays(30)))
    }

    @Test fun `this month today yesterday and last week`() {
        assertThat(resolver.resolve(DatePeriod.ThisMonth).startInclusive).isEqualTo(LocalDate.of(2026, 10, 1))
        assertThat(resolver.resolve(DatePeriod.Today)).isEqualTo(LocalDateRange.single(LocalDate.of(2026, 10, 24)))
        assertThat(resolver.resolve(DatePeriod.Yesterday)).isEqualTo(LocalDateRange.single(LocalDate.of(2026, 10, 23)))
        // 24 Oct 2026 is a Saturday; last week is Mon 12 Oct – Sun 18 Oct.
        assertThat(resolver.resolve(DatePeriod.LastWeek)).isEqualTo(LocalDateRange.inclusive(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18)))
    }

    @Test fun `late evening UTC is already the next day in Karachi`() {
        val lateUtc = Clock.fixed(java.time.Instant.parse("2026-10-23T20:00:00Z"), ZoneId.of("UTC"))
        assertThat(DateResolver(lateUtc, karachi).today()).isEqualTo(LocalDate.of(2026, 10, 24))
    }

    @Test fun `DST boundaries use local start of day`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // DST ends on 25 Oct 2026 in Berlin: that local day lasts 25 hours.
        val (start, end) = LocalDateRange.single(LocalDate.of(2026, 10, 25)).toInstants(berlin)
        assertThat(java.time.Duration.between(start, end).toHours()).isEqualTo(25)
        val c = Clock.fixed(ZonedDateTime.of(2026, 10, 26, 0, 30, 0, 0, berlin).toInstant(), berlin)
        val r = DateResolver(c, berlin).resolve(DatePeriod.LastNDays(2))
        assertThat(r).isEqualTo(LocalDateRange(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 27)))
        val newYork = ZoneId.of("America/New_York")
        // Spring forward 8 Mar 2026: 23-hour day.
        val (s2, e2) = LocalDateRange.single(LocalDate.of(2026, 3, 8)).toInstants(newYork)
        assertThat(java.time.Duration.between(s2, e2).toHours()).isEqualTo(23)
    }
}
