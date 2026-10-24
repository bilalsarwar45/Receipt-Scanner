package com.receiptscan.money

import java.math.BigInteger

/**
 * Exact proportional allocation (largest remainder method).
 * The parts always add up to [total] to the minor unit. Ties in remainder go to the earliest share,
 * so the result is deterministic and the same input always gives the same split.
 */
object Allocation {

    fun allocate(total: Money, weights: List<Long>): List<Money> {
        require(weights.isNotEmpty()) { "Need at least one share" }
        require(weights.all { it >= 0 }) { "Weights must not be negative" }
        val sumW = weights.fold(BigInteger.ZERO) { a, w -> a + BigInteger.valueOf(w) }
        require(sumW.signum() > 0) { "At least one weight must be positive" }

        val sign = if (total.minor < 0) -1L else 1L
        val absTotal = BigInteger.valueOf(total.minor).abs()
        val base = LongArray(weights.size)
        val remainders = arrayOfNulls<BigInteger>(weights.size)
        var allocated = BigInteger.ZERO
        weights.forEachIndexed { i, w ->
            val product = absTotal * BigInteger.valueOf(w)
            val qr = product.divideAndRemainder(sumW)
            base[i] = qr[0].longValueExact()
            remainders[i] = qr[1]
            allocated += qr[0]
        }
        var left = (absTotal - allocated).longValueExact()
        val order = weights.indices.sortedWith(compareByDescending<Int> { remainders[it]!! }.thenBy { it })
        var k = 0
        while (left > 0) {
            val idx = order[k % order.size]
            if (weights[idx] > 0) {
                base[idx] += 1
                left--
            }
            k++
        }
        return base.map { Money(it * sign, total.currency) }
    }

    /** Equal split: PKR 1,680.00 between 3 people is 560.00 each; PKR 100.00 between 3 is 33.34, 33.33, 33.33. */
    fun equal(total: Money, people: Int): List<Money> {
        require(people > 0) { "Need at least one person" }
        return allocate(total, List(people) { 1L })
    }
}
