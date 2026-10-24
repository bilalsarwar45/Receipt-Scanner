package com.receiptscan.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** Canonical expense row. Money is stored as integer minor units plus an ISO currency code. */
@Entity(tableName = "expenses", indices = [Index("effectiveDate"), Index("state"), Index("createdByKey")])
data class ExpenseRow(
    @PrimaryKey val id: String,
    val amountMinor: Long,
    val currency: String,
    val kind: String,
    /** LocalDate as epoch day. */
    val effectiveDate: Long,
    val recordedAt: Long,
    val zoneId: String,
    val categoryId: String,
    val merchant: String?,
    val note: String?,
    val source: String,
    val state: String,
    val version: Int,
    val originalExpenseId: String?,
    val createdByKey: String?,
    val voidReason: String?,
)

@Entity(tableName = "expense_allocations", indices = [Index("expenseId")])
data class AllocationRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val expenseId: String,
    val categoryId: String,
    val amountMinor: Long,
    val currency: String,
)

/** A receipt or payment can support at most one expense: (kind, targetId) is the primary key. */
@Entity(tableName = "expense_links", primaryKeys = ["kind", "targetId"], indices = [Index("expenseId")])
data class LinkRow(val expenseId: String, val kind: String, val targetId: String)

@Entity(tableName = "expense_revisions", indices = [Index("expenseId")])
data class RevisionRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val expenseId: String,
    val version: Int,
    val changedAt: Long,
    val change: String,
)

@Entity(tableName = "receipts", indices = [Index("status"), Index("purchaseDate")])
data class ReceiptRow(
    @PrimaryKey val id: String,
    val status: String,
    val merchant: String?,
    val purchaseDate: Long?,
    val purchaseTime: String?,
    val currency: String,
    val subtotalMinor: Long?,
    val taxMinor: Long?,
    val discountMinor: Long?,
    val totalMinor: Long?,
    val categoryId: String?,
    val scannedAt: Long,
    val rawText: String,
    val reference: String?,
    val paymentMethod: String?,
    val note: String?,
    /** JSON array of image ids. */
    val sourceImageIds: String,
    val ocrEngine: String?,
    /** JSON array of strings. */
    val reviewReasons: String,
    val isPaymentReceipt: Boolean,
)

@Entity(tableName = "receipt_items", indices = [Index("receiptId")])
data class ReceiptItemRow(
    @PrimaryKey val id: String,
    val receiptId: String,
    val position: Int,
    val description: String,
    /** BigDecimal as plain string. */
    val quantity: String?,
    val unit: String?,
    val unitPriceMinor: Long?,
    val lineTotalMinor: Long,
    val currency: String,
    val categoryId: String?,
    val provenance: String,
    val rawText: String?,
    val uncertain: Boolean,
)

/** Full-text index over receipts: merchant, item names and OCR text (FTS4, handover 12). */
@Fts4
@Entity(tableName = "receipt_fts")
data class ReceiptFts(
    @ColumnInfo(name = "receiptId") val receiptId: String,
    val merchant: String,
    val items: String,
    val body: String,
)

@Entity(tableName = "invoices")
data class InvoiceRow(
    @PrimaryKey val id: String,
    val supplier: String,
    val invoiceNumber: String?,
    val issueDate: Long?,
    val dueDate: Long?,
    val amountDueMinor: Long,
    val currency: String,
    val status: String,
    val needsReview: Boolean,
    val categoryId: String,
    val scannedAt: Long,
    val rawText: String,
    val sourceImageIds: String,
)

@Entity(tableName = "payments", indices = [Index("invoiceId"), Index("expenseId")])
data class PaymentRow(
    @PrimaryKey val id: String,
    val invoiceId: String,
    val amountMinor: Long,
    val currency: String,
    val paidDate: Long,
    val expenseId: String,
    val method: String?,
)

/** Idempotency record: the execution key is the primary key, so a repeated callback cannot write twice. */
@Entity(tableName = "command_executions", indices = [Index("executedAt")])
data class CommandRow(
    @PrimaryKey val executionKey: String,
    val commandType: String,
    val executedAt: Long,
    val affectedExpenseId: String?,
    val summary: String,
    val undone: Boolean,
)

/** Results of scanners other than receipts and bills (Library → Other scans), stored as module-owned envelopes. */
@Entity(tableName = "scans", indices = [Index("capabilityId"), Index("createdAt")])
data class ScanRow(
    @PrimaryKey val id: String,
    val capabilityId: String,
    val payloadVersion: Int,
    val title: String,
    val summary: String,
    val payloadJson: String,
    val sourceIds: String,
    val createdAt: Long,
    val searchText: String,
)

@Fts4
@Entity(tableName = "scan_fts")
data class ScanFts(val scanId: String, val title: String, val body: String)

/** Saved PDF documents. Pages live in app-private storage under [folder]. */
@Entity(tableName = "documents", indices = [Index("createdAt")])
data class DocumentRow(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String,
    val pageCount: Int,
    val pdfPath: String?,
    val folder: String,
    val searchable: Boolean,
    val text: String,
    val createdAt: Long,
    val sizeBytes: Long,
)

@Entity(tableName = "reminders", indices = [Index("dueAt"), Index("recordId")])
data class ReminderRow(
    @PrimaryKey val id: String,
    /** bills | warranty | expiry | receipt | draft */
    val channel: String,
    val recordId: String?,
    val title: String,
    val body: String,
    val dueAt: Long,
    val done: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "warranties", indices = [Index("receiptId")])
data class WarrantyRow(
    @PrimaryKey val id: String,
    val productName: String,
    val merchant: String?,
    val receiptId: String?,
    val model: String?,
    val serial: String?,
    val barcode: String?,
    val purchaseDate: Long?,
    val returnBy: Long?,
    val warrantyUntil: Long?,
    val termsNote: String?,
    val createdAt: Long,
)

@Entity(tableName = "budgets")
data class BudgetRow(
    @PrimaryKey val categoryId: String,
    val amountMinor: Long,
    val currency: String,
)

/** Learned rules: merchant → category, and merchant display aliases. */
@Entity(tableName = "merchant_rules")
data class MerchantRuleRow(
    @PrimaryKey val merchantKey: String,
    val displayName: String?,
    val categoryId: String?,
    val updatedAt: Long,
)

/** Items the person wants to buy again (shopping list built from receipts). */
@Entity(tableName = "shopping_items")
data class ShoppingItemRow(
    @PrimaryKey val id: String,
    val name: String,
    val quantity: String?,
    val fromReceiptId: String?,
    val done: Boolean,
    val createdAt: Long,
)

/** Food diary entries from the calorie estimator. Calories are estimates and stored as such. */
@Entity(tableName = "food_log", indices = [Index("eatenOn")])
data class FoodLogRow(
    @PrimaryKey val id: String,
    val eatenOn: Long,
    val name: String,
    val grams: Double,
    val kcal: Double,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val createdAt: Long,
)
