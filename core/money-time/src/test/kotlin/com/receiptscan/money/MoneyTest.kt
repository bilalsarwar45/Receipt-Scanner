package com.receiptscan.money

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class MoneyTest {
    private val pkr = Currencies.PKR

    @Test fun `PKR 300 is 30000 minor units`() {
        assertThat(Money.fromDecimal(BigDecimal("300"), pkr).minor).isEqualTo(30_000)
        assertThat(MoneyFormat.format(Money(30_000, pkr))).isEqualTo("PKR 300.00")
    }

    @Test fun `fraction digits follow the currency`() {
        assertThat(Money.fromDecimal(BigDecimal("1500"), Currencies.JPY).minor).isEqualTo(1500)
        assertThat(Money.fromDecimal(BigDecimal("1.250"), Currencies.KWD).minor).isEqualTo(1250)
        assertThat(MoneyFormat.format(Money(1250, Currencies.KWD))).isEqualTo("KWD 1.250")
        assertThat(MoneyFormat.format(Money(1500, Currencies.JPY))).isEqualTo("JPY 1,500")
        assertThrows(ArithmeticException::class.java) { Money.fromDecimal(BigDecimal("1.005"), pkr) }
    }

    @Test fun `mixed currencies never add`() {
        assertThrows(CurrencyMismatchException::class.java) { Money(100, pkr) + Money(100, Currencies.USD) }
        val totals = listOf(Money(100, pkr), Money(250, Currencies.USD), Money(50, pkr)).totalsByCurrency()
        assertThat(totals[pkr]).isEqualTo(Money(150, pkr))
        assertThat(totals[Currencies.USD]).isEqualTo(Money(250, Currencies.USD))
    }

    @Test fun `overflow throws instead of wrapping`() {
        assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE, pkr) + Money(1, pkr) }
    }

    @Test fun `quantity multiplication is exact decimal`() {
        // 0.5 kg at PKR 333.33 = 166.665 -> half-even 166.66
        assertThat(Money(33_333, pkr).times(BigDecimal("0.5")).minor).isEqualTo(16_666)
        assertThat(Money(32_000, pkr).times(2).minor).isEqualTo(64_000)
    }

    @Test fun `short format drops zero fraction`() {
        assertThat(MoneyFormat.formatShort(Money(30_000, pkr))).isEqualTo("PKR 300")
        assertThat(MoneyFormat.formatShort(Money(30_050, pkr))).isEqualTo("PKR 300.50")
    }
}

class AmountParserTest {
    private fun ok(text: String, style: DecimalStyle = DecimalStyle.POINT, max: Int = 2): BigDecimal {
        val r = AmountParser.parse(text, style, max)
        check(r is AmountParse.Ok) { "Expected Ok for '$text' but was $r" }
        return r.value
    }

    @Test fun `western grouping and decimals`() {
        assertThat(ok("3,800.00")).isEqualTo(BigDecimal("3800.00"))
        assertThat(ok("1,234,567.5")).isEqualTo(BigDecimal("1234567.5"))
        assertThat(ok("1,500")).isEqualTo(BigDecimal("1500"))
        assertThat(ok("640")).isEqualTo(BigDecimal("640"))
    }

    @Test fun `lakh grouping`() {
        assertThat(ok("1,50,000")).isEqualTo(BigDecimal("150000"))
        assertThat(ok("12,34,567.25")).isEqualTo(BigDecimal("1234567.25"))
    }

    @Test fun `decimal comma`() {
        assertThat(ok("1.500,50", DecimalStyle.COMMA)).isEqualTo(BigDecimal("1500.50"))
        assertThat(ok("12,50")).isEqualTo(BigDecimal("12.50"))
        assertThat(ok("1 500,50", DecimalStyle.COMMA)).isEqualTo(BigDecimal("1500.50"))
    }

    @Test fun `lone point with three digits is grouping for two-digit currencies and flagged`() {
        val r = AmountParser.parse("1.500", DecimalStyle.POINT, 2) as AmountParse.Ok
        assertThat(r.value).isEqualTo(BigDecimal("1500"))
        assertThat(r.ambiguousSeparators).isTrue()
        assertThat(ok("1.500", max = 3)).isEqualTo(BigDecimal("1.500"))
    }

    @Test fun `currency tokens and suffixes`() {
        val r = AmountParser.parse("Rs. 1,500/-") as AmountParse.Ok
        assertThat(r.value).isEqualTo(BigDecimal("1500"))
        assertThat(r.currencyToken).isEqualTo("Rs.")
        assertThat(ok("PKR 300")).isEqualTo(BigDecimal("300"))
        assertThat(ok("₨300")).isEqualTo(BigDecimal("300"))
        assertThat(ok("300 rupees")).isEqualTo(BigDecimal("300"))
    }

    @Test fun `negative forms`() {
        assertThat(ok("-200.00")).isEqualTo(BigDecimal("-200.00"))
        assertThat(ok("(200.00)")).isEqualTo(BigDecimal("-200.00"))
        assertThat(ok("200.00-")).isEqualTo(BigDecimal("-200.00"))
        assertThat(ok("−200")).isEqualTo(BigDecimal("-200"))
    }

    @Test fun `unicode digits`() {
        assertThat(ok("۳۰۰")).isEqualTo(BigDecimal("300"))
        assertThat(ok("٣٬٨٠٠٫٠٠")).isEqualTo(BigDecimal("3800.00"))
    }

    @Test fun `malformed input is rejected not guessed`() {
        assertThat(AmountParser.parse("12a4")).isInstanceOf(AmountParse.Invalid::class.java)
        assertThat(AmountParser.parse("1,5,00")).isInstanceOf(AmountParse.Invalid::class.java)
        assertThat(AmountParser.parse("")).isInstanceOf(AmountParse.Invalid::class.java)
        assertThat(AmountParser.parse("Total")).isInstanceOf(AmountParse.Invalid::class.java)
        assertThat(AmountParser.parse("1.2345", maxFractionDigits = 2)).isInstanceOf(AmountParse.Invalid::class.java)
    }
}

class AmountInputTest {
    @Test fun `validation rules from the handover`() {
        val pkr = Currencies.PKR
        assertThat(AmountInput.validate("550.00", pkr)).isEqualTo(AmountInput.Result.Valid(Money(55_000, pkr)))
        assertThat(AmountInput.validate("1,500", pkr)).isEqualTo(AmountInput.Result.Valid(Money(150_000, pkr)))
        assertThat(AmountInput.validate("5.505", pkr)).isInstanceOf(AmountInput.Result.Error::class.java)
        assertThat(AmountInput.validate("1.2.3", pkr)).isInstanceOf(AmountInput.Result.Error::class.java)
        assertThat(AmountInput.validate("100000000", pkr)).isInstanceOf(AmountInput.Result.Error::class.java)
        assertThat(AmountInput.validate("99999999.99", pkr)).isInstanceOf(AmountInput.Result.Valid::class.java)
        assertThat(AmountInput.validate("0", pkr)).isInstanceOf(AmountInput.Result.Error::class.java)
    }
}

class AllocationTest {
    private val pkr = Currencies.PKR

    @Test fun `equal split adds up exactly`() {
        assertThat(Allocation.equal(Money(168_000, pkr), 3)).containsExactly(Money(56_000, pkr), Money(56_000, pkr), Money(56_000, pkr))
        val parts = Allocation.equal(Money(10_000, pkr), 3)
        assertThat(parts.map { it.minor }).containsExactly(3334L, 3333L, 3333L).inOrder()
        assertThat(parts.sum(pkr)).isEqualTo(Money(10_000, pkr))
    }

    @Test fun `weighted split with zero weights and negative totals`() {
        val parts = Allocation.allocate(Money(-1000, pkr), listOf(1, 0, 2))
        assertThat(parts.map { it.minor }).containsExactly(-333L, 0L, -667L).inOrder()
    }

    @Test fun `randomized splits always sum to total`() {
        val rnd = java.util.Random(7)
        repeat(500) {
            val total = Money(rnd.nextInt(1_000_000).toLong(), pkr)
            val weights = List(1 + rnd.nextInt(7)) { rnd.nextInt(1000).toLong() }.let { if (it.all { w -> w == 0L }) it.mapIndexed { i, w -> if (i == 0) 1L else w } else it }
            assertThat(Allocation.allocate(total, weights).sum(pkr)).isEqualTo(total)
        }
    }
}
