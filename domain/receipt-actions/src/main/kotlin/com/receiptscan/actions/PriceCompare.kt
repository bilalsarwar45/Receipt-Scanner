package com.receiptscan.actions

import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Pack size in a base unit: grams, millilitres or pieces. */
data class PackSize(val amount: BigDecimal, val unit: String) {
    companion object {
        private val rx = Regex("""(?i)(\d+(?:\.\d+)?)\s*(kg|g|gm|gram|grams|l|ltr|litre|liter|ml|pcs|pc|pieces|dozen)\b""")

        /** Reads "Rice 5kg", "Milk 1L", "Eggs 12 pcs" from an item description. */
        fun from(description: String): PackSize? {
            val m = rx.find(description) ?: Regex("""(?i)\b(?:eggs?)\s+(\d{1,3})\b""").find(description)?.let { return PackSize(it.groupValues[1].toBigDecimal(), "pc") } ?: return null
            val n = m.groupValues[1].toBigDecimal()
            return when (m.groupValues[2].lowercase()) {
                "kg" -> PackSize(n * BigDecimal(1000), "g")
                "g", "gm", "gram", "grams" -> PackSize(n, "g")
                "l", "ltr", "litre", "liter" -> PackSize(n * BigDecimal(1000), "ml")
                "ml" -> PackSize(n, "ml")
                "dozen" -> PackSize(n * BigDecimal(12), "pc")
                else -> PackSize(n, "pc")
            }
        }
    }
}

/** One confirmed purchase of an item from the person's own receipts. */
data class PricePoint(val receiptId: String, val merchant: String?, val date: LocalDate, val description: String, val unitPrice: Money, val pack: PackSize?)

sealed interface Comparison {
    data class Change(
        val previous: PricePoint,
        val latest: PricePoint,
        /** Price per base unit (per kg / per L / per piece) when pack sizes are known, else per item. */
        val previousPerUnit: Money,
        val latestPerUnit: Money,
        val unitLabel: String,
        val percent: BigDecimal,
        val formula: String,
    ) : Comparison
    data class NoHistory(val latest: PricePoint) : Comparison
    /** Pack sizes differ and one is unknown: comparing would misreport a size change as a price change. */
    data class NotComparable(val reason: String) : Comparison
}

object PriceCompare {
    fun compare(history: List<PricePoint>): Comparison {
        val sorted = history.sortedBy { it.date }
        val latest = sorted.lastOrNull() ?: throw IllegalArgumentException("No purchases")
        val previous = sorted.dropLast(1).lastOrNull() ?: return Comparison.NoHistory(latest)
        if (previous.unitPrice.currency != latest.unitPrice.currency) return Comparison.NotComparable("Bought in different currencies")
        val (pPer, lPer, label) = when {
            previous.pack != null && latest.pack != null && previous.pack.unit == latest.pack.unit -> {
                val base = if (latest.pack.unit == "pc") BigDecimal.ONE else BigDecimal(1000)
                Triple(previous.unitPrice.times(base.divide(previous.pack.amount, 10, RoundingMode.HALF_EVEN)), latest.unitPrice.times(base.divide(latest.pack.amount, 10, RoundingMode.HALF_EVEN)), when (latest.pack.unit) { "g" -> "per kg"; "ml" -> "per L"; else -> "each" })
            }
            previous.pack == null && latest.pack == null -> Triple(previous.unitPrice, latest.unitPrice, "per item")
            previous.pack != null && latest.pack != null && previous.pack == latest.pack -> Triple(previous.unitPrice, latest.unitPrice, "per item")
            else -> return Comparison.NotComparable("Pack sizes differ or are missing; fix the match to compare")
        }
        if (pPer.isZero) return Comparison.NotComparable("Previous price is zero")
        val pct = (lPer.toDecimal() - pPer.toDecimal()).multiply(BigDecimal(100)).divide(pPer.toDecimal(), 1, RoundingMode.HALF_EVEN)
        val formula = "(${MoneyFormat.format(lPer, false)} − ${MoneyFormat.format(pPer, false)}) ÷ ${MoneyFormat.format(pPer, false)} × 100 = ${pct.toPlainString()}%"
        return Comparison.Change(previous, latest, pPer, lPer, label, pct, formula)
    }
}
