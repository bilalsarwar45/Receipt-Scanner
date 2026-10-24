package com.receiptscan.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * An exact amount in integer minor units of one currency. PKR 300.00 is Money(30_000, PKR).
 * Arithmetic overflows throw instead of wrapping, and mixing currencies throws.
 */
data class Money(val minor: Long, val currency: CurrencySpec) : Comparable<Money> {

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minor, other.minor), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minor, other.minor), currency)
    }

    operator fun unaryMinus(): Money = Money(Math.negateExact(minor), currency)

    /** Multiplies by an exact decimal quantity (for example 0.5 kg), rounding half-even to minor units. */
    fun times(quantity: BigDecimal, rounding: RoundingMode = RoundingMode.HALF_EVEN): Money =
        fromDecimal(toDecimal().multiply(quantity), currency, rounding)

    fun times(count: Long): Money = Money(Math.multiplyExact(minor, count), currency)

    val isZero: Boolean get() = minor == 0L
    val isNegative: Boolean get() = minor < 0L

    fun abs(): Money = if (minor < 0) -this else this

    fun toDecimal(): BigDecimal = BigDecimal.valueOf(minor, currency.fractionDigits)

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minor.compareTo(other.minor)
    }

    override fun toString(): String = MoneyFormat.format(this)

    private fun requireSameCurrency(other: Money) {
        if (other.currency.code != currency.code) {
            throw CurrencyMismatchException(currency, other.currency)
        }
    }

    companion object {
        /** Upper bound shared with the validation rule "≤ 99,999,999.99" for 2-digit currencies. */
        val MAX_MAJOR: BigDecimal = BigDecimal("99999999.99")

        fun zero(currency: CurrencySpec) = Money(0, currency)

        fun ofMinor(minor: Long, currency: CurrencySpec) = Money(minor, currency)

        /**
         * Converts an exact decimal. Throws [ArithmeticException] if the value has more fraction
         * digits than the currency allows, unless a [rounding] mode is supplied.
         */
        fun fromDecimal(value: BigDecimal, currency: CurrencySpec, rounding: RoundingMode = RoundingMode.UNNECESSARY): Money {
            val scaled = value.setScale(currency.fractionDigits, rounding)
            return Money(scaled.unscaledValue().longValueExact(), currency)
        }

        fun parse(text: String, currency: CurrencySpec): Money =
            fromDecimal(BigDecimal(text), currency)
    }
}

class CurrencyMismatchException(a: CurrencySpec, b: CurrencySpec) :
    IllegalArgumentException("Cannot combine ${a.code} with ${b.code} without an explicit conversion")

/** Sums amounts into one total per currency. Different currencies are never added together. */
fun Iterable<Money>.totalsByCurrency(): Map<CurrencySpec, Money> {
    val result = linkedMapOf<String, Money>()
    for (m in this) {
        result[m.currency.code] = result[m.currency.code]?.plus(m) ?: m
    }
    return result.values.associateBy { it.currency }
}

/** Sums amounts that must share [currency]; throws on a mismatch. */
fun Iterable<Money>.sum(currency: CurrencySpec): Money = fold(Money.zero(currency)) { acc, m -> acc + m }
