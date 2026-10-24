package com.receiptscan.expenses

import com.receiptscan.money.Money
import java.time.Instant
import java.time.LocalDate

/** How an expense was recorded. Shown as a label in lists and Insights. */
enum class ExpenseSource(val label: String) {
    RECEIPT("Scanned receipt"),
    VOICE("Voice"),
    MANUAL("Entered by you"),
    BILL_PAYMENT("Bill payment"),
}

enum class ExpenseState { DRAFT, CONFIRMED, VOIDED }

/** Sign semantics: purchases and bill payments count positive, refunds count negative. */
enum class ExpenseKind(val sign: Int) { PURCHASE(1), BILL_PAYMENT(1), REFUND(-1) }

/**
 * One entry in the canonical expense ledger. Every total in the app is computed from these rows,
 * so a purchase evidenced by both a voice entry and a receipt is still counted exactly once.
 * [amount] is always positive; [signedAmount] applies [kind].
 */
data class ExpenseEntry(
    val id: String,
    val amount: Money,
    val kind: ExpenseKind,
    val effectiveDate: LocalDate,
    val recordedAt: Instant,
    /** Zone the effective date was resolved in, kept for audit. */
    val zoneId: String,
    val categoryId: String,
    val merchant: String?,
    val note: String?,
    val source: ExpenseSource,
    val state: ExpenseState,
    val version: Int = 1,
    /** For refunds: the purchase being refunded, when known. */
    val originalExpenseId: String? = null,
    /** Execution key of the command that created this entry, for idempotency and undo. */
    val createdByKey: String? = null,
    val voidReason: String? = null,
) {
    init {
        require(amount.minor > 0) { "Expense amount must be positive; use kind=REFUND for refunds" }
        require(merchant == null || merchant.length <= 120) { "Merchant too long" }
    }

    val signedAmount: Money get() = if (kind.sign < 0) -amount else amount
}

/** Category split of one expense (from receipt items). Sums must equal the expense amount. */
data class ExpenseAllocation(val expenseId: String, val categoryId: String, val amount: Money)

enum class LinkKind { RECEIPT, BILL_PAYMENT }

/**
 * Evidence attached to an expense. A target (receipt or payment) can support at most one expense,
 * which the store enforces as a unique constraint.
 */
data class ExpenseSourceLink(val expenseId: String, val kind: LinkKind, val targetId: String)

/** An audit entry written whenever an expense changes. */
data class ExpenseRevision(
    val expenseId: String,
    val version: Int,
    val changedAt: Instant,
    val change: String,
)

// ---- Evidence records ----

enum class ReceiptStatus { NEEDS_REVIEW, CONFIRMED, DELETED }

data class ReceiptItem(
    val id: String,
    val receiptId: String,
    val position: Int,
    val description: String,
    val quantity: java.math.BigDecimal?,
    val unit: String?,
    val unitPrice: Money?,
    val lineTotal: Money,
    val categoryId: String?,
    /** Where the description/amount came from: "photo", "edited". */
    val provenance: String = "photo",
    val rawText: String? = null,
    val uncertain: Boolean = false,
)

/**
 * Extracted receipt. NEEDS_REVIEW receipts are never counted in totals.
 * [purchaseDate] is the date printed on the receipt; [scannedAt] is when it was captured.
 */
data class Receipt(
    val id: String,
    val status: ReceiptStatus,
    val merchant: String?,
    val purchaseDate: LocalDate?,
    val purchaseTime: String?,
    val currency: com.receiptscan.money.CurrencySpec,
    val subtotal: Money?,
    val tax: Money?,
    val discount: Money?,
    val total: Money?,
    val categoryId: String?,
    val scannedAt: Instant,
    val rawText: String,
    val reference: String? = null,
    val paymentMethod: String? = null,
    val note: String? = null,
    val sourceImageIds: List<String> = emptyList(),
    val ocrEngine: String? = null,
    val reviewReasons: List<String> = emptyList(),
    val items: List<ReceiptItem> = emptyList(),
    /** Set when the receipt is evidence for a bill payment rather than a purchase. */
    val isPaymentReceipt: Boolean = false,
)

enum class InvoiceStatus { UNPAID, PARTIALLY_PAID, PAID, VOID }

/** A bill. Capturing it never creates an expense; only recorded payments do. */
data class Invoice(
    val id: String,
    val supplier: String,
    val invoiceNumber: String?,
    val issueDate: LocalDate?,
    val dueDate: LocalDate?,
    val amountDue: Money,
    val status: InvoiceStatus,
    val needsReview: Boolean,
    val categoryId: String,
    val scannedAt: Instant,
    val rawText: String = "",
    val sourceImageIds: List<String> = emptyList(),
)

data class PaymentRecord(
    val id: String,
    val invoiceId: String,
    val amount: Money,
    val paidDate: LocalDate,
    val expenseId: String,
    val method: String? = null,
)

/** Record of an executed command; the unique [executionKey] makes repeated callbacks harmless. */
data class CommandExecution(
    val executionKey: String,
    val commandType: String,
    val executedAt: Instant,
    val affectedExpenseId: String?,
    val summary: String,
    val undone: Boolean = false,
)
