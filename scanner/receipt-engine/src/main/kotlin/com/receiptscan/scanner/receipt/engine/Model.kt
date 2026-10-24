package com.receiptscan.scanner.receipt.engine

import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.Money
import com.receiptscan.scanner.contracts.Issue
import com.receiptscan.scanner.contracts.Value
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** What a receipt row turned out to be. */
enum class RowRole { HEADER, ITEM, SUBTOTAL, TAX, DISCOUNT, SERVICE, ROUNDING, TOTAL, TENDERED, CHANGE, PAYMENT_INFO, IGNORED, TEXT }

data class ExtractedItem(
    val description: Value<String>,
    val quantity: Value<BigDecimal>?,
    val unit: String?,
    val unitPrice: Value<Money>?,
    val lineTotal: Value<Money>,
    /** Short reasons shown on the review screen, e.g. "Hard to read · check price". */
    val uncertainReasons: List<String> = emptyList(),
    val suggestedCategory: String? = null,
    val rowIndex: Int,
) {
    val uncertain: Boolean get() = uncertainReasons.isNotEmpty()
}

/** Tax, discount, service charge or rounding printed on the receipt. */
data class Adjustment(
    val label: String,
    val role: RowRole,
    val amount: Value<Money>,
    /** For taxes: printed as already included in prices ("incl. GST"). */
    val included: Boolean = false,
    /** Percent printed next to it, if any (e.g. 16 for "GST 16%"). */
    val percent: BigDecimal? = null,
)

/**
 * The arithmetic check shown on the review screen.
 * base (subtotal or item sum) + taxes not included + service − discounts ± rounding = computed total.
 */
data class Reconciliation(
    val itemsSum: Money?,
    val subtotalPrinted: Money?,
    val base: Money?,
    val taxesAdded: Money,
    val taxesIncluded: Money,
    val discounts: Money,
    val service: Money,
    val rounding: Money,
    val computedTotal: Money?,
    val printedTotal: Money?,
    /** printed − computed; null when either side is missing. */
    val difference: Money?,
    /** True when printed and computed totals agree within 0.01; null when either is missing. */
    val matches: Boolean?,
    val formula: String,
)

/** Partial, evidence-linked receipt extraction. Nothing here is saved until the person confirms. */
data class ReceiptExtraction(
    val merchant: Value<String>?,
    val date: Value<LocalDate>?,
    val dateAlternatives: List<LocalDate>,
    val time: Value<LocalTime>?,
    val reference: Value<String>?,
    val paymentMethod: Value<String>?,
    val currency: Value<CurrencySpec>,
    val items: List<ExtractedItem>,
    val subtotal: Value<Money>?,
    val adjustments: List<Adjustment>,
    val total: Value<Money>?,
    val tendered: Value<Money>?,
    val change: Value<Money>?,
    val reconciliation: Reconciliation,
    val issues: List<Issue>,
    val suggestedCategory: Value<String>?,
    val rawText: String,
    val lineCount: Int,
    /** Rows repeated across overlapping long-receipt parts that were counted once. */
    val overlapRowsDropped: Int,
    val rows: List<ClassifiedRow>,
) {
    val hasTotal: Boolean get() = total != null
}

data class ClassifiedRow(val index: Int, val pageIndex: Int, val text: String, val role: RowRole, val amount: Money?)
