package com.receiptscan.actions

import com.receiptscan.money.Allocation
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat

/** A line that can be assigned to people. */
data class SplitLine(val id: String, val label: String, val amount: Money)

sealed interface SplitMode {
    data object Equally : SplitMode
    /** Item → people sharing it. Shared items are split equally among their people. */
    data class ByItem(val assignments: Map<String, Set<String>>) : SplitMode
    /** Exact amounts typed per person; must add up to the total. */
    data class Custom(val shares: Map<String, Money>) : SplitMode
}

data class PersonShare(val person: String, val items: Money, val tax: Money, val service: Money, val tip: Money, val discount: Money, val total: Money)

sealed interface SplitResult {
    data class Ok(val shares: List<PersonShare>, val total: Money) : SplitResult {
        /** Text for the share sheet. */
        fun summary(): String = shares.joinToString("\n") { "${it.person}: ${MoneyFormat.format(it.total)}" } + "\nTotal: ${MoneyFormat.format(total)}"
    }
    data class Invalid(val message: String) : SplitResult
}

/**
 * Exact bill splitting. Items are split per assignment; tax, service charge and discounts are allocated
 * in proportion to each person's item share; the tip is split the same way. Every allocation uses the
 * largest-remainder method, so people's totals add up to the final bill to the minor unit.
 */
object SplitBill {
    fun split(
        people: List<String>,
        lines: List<SplitLine>,
        tax: Money,
        service: Money,
        discount: Money,
        tip: Money,
        mode: SplitMode,
    ): SplitResult {
        require(people.isNotEmpty())
        if (people.toSet().size != people.size) return SplitResult.Invalid("Each person needs a different name")
        val cur = tax.currency
        val zero = Money.zero(cur)
        val itemsTotal = lines.fold(zero) { a, l -> a + l.amount }
        val finalTotal = itemsTotal + tax + service - discount + tip
        if (finalTotal.isNegative) return SplitResult.Invalid("Discount is larger than the bill")

        if (mode is SplitMode.Custom) {
            val sum = people.fold(zero) { a, p -> a + (mode.shares[p] ?: zero) }
            val left = finalTotal - sum
            if (!left.isZero) {
                return SplitResult.Invalid(if (left.isNegative) "${MoneyFormat.format(-left)} too much assigned" else "${MoneyFormat.format(left)} left to assign")
            }
            return SplitResult.Ok(people.map { p -> val m = mode.shares[p] ?: zero; PersonShare(p, m, zero, zero, zero, zero, m) }, finalTotal)
        }

        val itemShare: Map<String, Money> = when (mode) {
            SplitMode.Equally -> people.zip(Allocation.equal(itemsTotal, people.size)).toMap()
            is SplitMode.ByItem -> {
                val acc = people.associateWith { zero }.toMutableMap()
                for (l in lines) {
                    val who = mode.assignments[l.id].orEmpty().filter { it in people }
                    if (who.isEmpty()) return SplitResult.Invalid("Assign “${l.label}” to someone")
                    val ordered = people.filter { it in who }
                    Allocation.equal(l.amount, ordered.size).forEachIndexed { i, m -> acc[ordered[i]] = acc.getValue(ordered[i]) + m }
                }
                acc
            }
            is SplitMode.Custom -> error("handled")
        }
        val weights = people.map { itemShare.getValue(it).minor }
        fun spread(amount: Money): List<Money> =
            if (weights.all { it == 0L }) Allocation.equal(amount, people.size) else Allocation.allocate(amount, weights)
        val taxes = spread(tax); val services = spread(service); val discounts = spread(discount); val tips = spread(tip)
        val shares = people.mapIndexed { i, p ->
            val items = itemShare.getValue(p)
            PersonShare(p, items, taxes[i], services[i], tips[i], discounts[i], items + taxes[i] + services[i] + tips[i] - discounts[i])
        }
        check(shares.fold(zero) { a, s -> a + s.total } == finalTotal) { "Split does not add up" }
        return SplitResult.Ok(shares, finalTotal)
    }
}
