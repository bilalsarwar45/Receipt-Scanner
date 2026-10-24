package com.receiptscan.scanner.receipt

import kotlinx.serialization.Serializable

/**
 * What the receipt scanner hands to its host. Amounts are minor units in [currency]. The host decides
 * how to store it; this module never writes to a ledger.
 */
@Serializable
data class ReceiptDraftPayload(
    val merchant: String?,
    val date: String?,
    val time: String?,
    val currency: String,
    val subtotalMinor: Long?,
    val taxMinor: Long?,
    val discountMinor: Long?,
    val totalMinor: Long?,
    val categoryId: String?,
    val reference: String?,
    val paymentMethod: String?,
    val items: List<ReceiptItemPayload>,
    val rawText: String,
    val sourceImageIds: List<String>,
    val reviewReasons: List<String>,
    val ocrEngine: String,
    /** True when the merchant, date, total or category was changed on the review screen. */
    val merchantEdited: Boolean = false,
    val categoryEdited: Boolean = false,
    /** The person asked to scan another receipt right after this one. */
    val queueAnother: Boolean = false,
)

@Serializable
data class ReceiptItemPayload(
    val description: String,
    val quantity: String?,
    val unit: String?,
    val unitPriceMinor: Long?,
    val lineTotalMinor: Long,
    val categoryId: String?,
    val edited: Boolean,
    val uncertain: Boolean,
    val rawText: String?,
)

/** Category choices offered on review. Ids match the app's built-in categories. */
object ReceiptCategories {
    val all: List<Pair<String, String>> = listOf(
        "groceries" to "Groceries", "dining" to "Dining", "fuel" to "Fuel", "transport" to "Transport",
        "health" to "Health", "shopping" to "Shopping", "electronics" to "Electronics", "utilities" to "Utilities",
        "bills" to "Bills", "household" to "Household", "personal-care" to "Personal care", "education" to "Education",
        "entertainment" to "Entertainment", "travel" to "Travel", "gifts" to "Gifts", "uncategorized" to "Uncategorized",
    )
    fun name(id: String?) = all.firstOrNull { it.first == id }?.second ?: "Uncategorized"
}
