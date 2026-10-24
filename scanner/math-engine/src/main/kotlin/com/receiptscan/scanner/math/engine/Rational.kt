package com.receiptscan.scanner.math.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode

/** Exact rational number. All supported arithmetic stays exact; only square roots of non-squares are approximate. */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {
    companion object {
        val ZERO = of(0)
        val ONE = of(1)
        fun of(n: Long, d: Long = 1) = of(BigInteger.valueOf(n), BigInteger.valueOf(d))
        fun of(n: BigInteger, d: BigInteger): Rational {
            if (d.signum() == 0) throw MathDomainException("Division by zero")
            val g = n.gcd(d).let { if (it.signum() == 0) BigInteger.ONE else it }
            val s = if (d.signum() < 0) -1 else 1
            return Rational(n.divide(g) * BigInteger.valueOf(s.toLong()), d.divide(g).abs())
        }
        fun parse(text: String): Rational {
            val bd = BigDecimal(text)
            return if (bd.scale() > 0) of(bd.unscaledValue(), BigInteger.TEN.pow(bd.scale())) else of(bd.toBigIntegerExact(), BigInteger.ONE)
        }
        private const val MAX_BITS = 4096
    }

    init {
        if (num.bitLength() > MAX_BITS || den.bitLength() > MAX_BITS) throw MathDomainException("Number is too large")
    }

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        if (o.num.signum() == 0) throw MathDomainException("Division by zero")
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = of(num.negate(), den)
    fun pow(e: Int): Rational {
        if (kotlin.math.abs(e) > 512) throw MathDomainException("Exponent is too large")
        return if (e >= 0) of(num.pow(e), den.pow(e)) else ONE / of(num.pow(-e), den.pow(-e))
    }
    val isZero get() = num.signum() == 0
    val isInteger get() = den == BigInteger.ONE
    val signum get() = num.signum()
    fun toBigDecimal(mc: MathContext = MathContext.DECIMAL64): BigDecimal = BigDecimal(num).divide(BigDecimal(den), mc)

    /** Exact square root when numerator and denominator are perfect squares. */
    fun exactSqrt(): Rational? {
        if (signum < 0) return null
        val a = num.sqrt()
        val b = den.sqrt()
        return if (a * a == num && b * b == den) of(a, b) else null
    }

    override fun compareTo(other: Rational) = (num * other.den).compareTo(other.num * den)
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = num.hashCode() * 31 + den.hashCode()

    /** "3/4", "-2", or a terminating decimal like "0.25" when exact. */
    fun display(): String {
        if (isInteger) return num.toString()
        val terminating = run {
            var d = den
            while (d.mod(BigInteger.TWO).signum() == 0) d /= BigInteger.TWO
            while (d.mod(BigInteger.valueOf(5)).signum() == 0) d /= BigInteger.valueOf(5)
            d == BigInteger.ONE
        }
        return if (terminating && den.bitLength() < 64) BigDecimal(num).divide(BigDecimal(den)).stripTrailingZeros().toPlainString() else "$num/$den"
    }

    fun decimal(places: Int = 6): String = BigDecimal(num).divide(BigDecimal(den), places, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

    override fun toString() = display()
}

class MathDomainException(message: String) : ArithmeticException(message)
class UnsupportedExpressionException(message: String) : IllegalArgumentException(message)
class MathSyntaxException(message: String, val position: Int) : IllegalArgumentException(message)
