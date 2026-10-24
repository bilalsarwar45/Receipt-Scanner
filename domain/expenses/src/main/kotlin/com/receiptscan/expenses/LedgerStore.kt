package com.receiptscan.expenses

import com.receiptscan.time.LocalDateRange
import kotlinx.coroutines.flow.Flow

/** Thrown by a store when a unique constraint (execution key, evidence link) is violated. */
class UniqueConstraintException(message: String) : RuntimeException(message)

/** Filter for ledger queries. Defaults match the brief: confirmed entries from every source. */
data class ExpenseFilter(
    val range: LocalDateRange? = null,
    val states: Set<ExpenseState> = setOf(ExpenseState.CONFIRMED),
    val sources: Set<ExpenseSource> = ExpenseSource.entries.toSet(),
    val categoryIds: Set<String>? = null,
    val merchantContains: String? = null,
    val currencyCode: String? = null,
    val kinds: Set<ExpenseKind> = ExpenseKind.entries.toSet(),
) {
    fun matches(e: ExpenseEntry): Boolean =
        (range == null || e.effectiveDate in range) &&
            e.state in states &&
            e.source in sources &&
            e.kind in kinds &&
            (categoryIds == null || e.categoryId in categoryIds) &&
            (merchantContains == null || (e.merchant ?: "").contains(merchantContains, ignoreCase = true)) &&
            (currencyCode == null || e.amount.currency.code == currencyCode)
}

data class ReceiptFilter(
    val statuses: Set<ReceiptStatus> = setOf(ReceiptStatus.NEEDS_REVIEW, ReceiptStatus.CONFIRMED),
    val text: String? = null,
)

/**
 * Persistence port for the ledger. The Android implementation is Room (`:data:local`) with real
 * transactions and unique indexes; [InMemoryLedgerStore] mirrors the same constraints for tests
 * and for hosts without a database.
 */
interface LedgerStore {
    /** Runs [block] atomically: all writes inside it commit together or not at all. */
    suspend fun <T> transaction(block: suspend () -> T): T

    suspend fun insertExpense(e: ExpenseEntry)
    suspend fun updateExpense(e: ExpenseEntry)
    suspend fun expense(id: String): ExpenseEntry?
    suspend fun expenses(filter: ExpenseFilter): List<ExpenseEntry>
    fun observeExpenses(filter: ExpenseFilter): Flow<List<ExpenseEntry>>

    suspend fun replaceAllocations(expenseId: String, allocations: List<ExpenseAllocation>)
    suspend fun allocations(expenseIds: Collection<String>): List<ExpenseAllocation>

    /** Throws [UniqueConstraintException] when the target already supports an expense. */
    suspend fun insertLink(link: ExpenseSourceLink)
    suspend fun deleteLink(link: ExpenseSourceLink)
    suspend fun links(expenseId: String): List<ExpenseSourceLink>
    suspend fun linkForTarget(kind: LinkKind, targetId: String): ExpenseSourceLink?

    suspend fun insertRevision(r: ExpenseRevision)
    suspend fun revisions(expenseId: String): List<ExpenseRevision>

    suspend fun upsertReceipt(r: Receipt)
    suspend fun receipt(id: String): Receipt?
    suspend fun receipts(filter: ReceiptFilter): List<Receipt>
    fun observeReceipts(filter: ReceiptFilter): Flow<List<Receipt>>
    suspend fun hardDeleteReceipt(id: String)

    suspend fun upsertInvoice(i: Invoice)
    suspend fun invoice(id: String): Invoice?
    suspend fun invoices(): List<Invoice>
    fun observeInvoices(): Flow<List<Invoice>>
    suspend fun insertPayment(p: PaymentRecord)
    suspend fun payment(id: String): PaymentRecord?
    suspend fun payments(invoiceId: String): List<PaymentRecord>

    /** Throws [UniqueConstraintException] when the execution key was already used. */
    suspend fun insertCommand(c: CommandExecution)
    suspend fun updateCommand(c: CommandExecution)
    suspend fun command(executionKey: String): CommandExecution?
    suspend fun recentCommands(limit: Int): List<CommandExecution>
}
