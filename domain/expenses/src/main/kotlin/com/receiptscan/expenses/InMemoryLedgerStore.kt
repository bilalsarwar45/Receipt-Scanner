package com.receiptscan.expenses

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

/**
 * In-memory [LedgerStore] with the same unique constraints and all-or-nothing transactions as the
 * Room store. A failed transaction restores the snapshot taken when it began.
 */
class InMemoryLedgerStore : LedgerStore {

    private data class State(
        val expenses: Map<String, ExpenseEntry> = emptyMap(),
        val allocations: Map<String, List<ExpenseAllocation>> = emptyMap(),
        val links: Set<ExpenseSourceLink> = emptySet(),
        val revisions: List<ExpenseRevision> = emptyList(),
        val receipts: Map<String, Receipt> = emptyMap(),
        val invoices: Map<String, Invoice> = emptyMap(),
        val payments: Map<String, PaymentRecord> = emptyMap(),
        val commands: Map<String, CommandExecution> = emptyMap(),
    )

    private val mutex = Mutex()
    private var state = State()
    private val published = MutableStateFlow(state)

    private object InTransaction : AbstractCoroutineContextElement(Key) {
        object Key : CoroutineContext.Key<InTransaction>
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        if (currentCoroutineContext()[InTransaction.Key] != null) return block()
        return mutex.withLock {
            val snapshot = state
            try {
                val result = kotlinx.coroutines.withContext(InTransaction) { block() }
                published.value = state
                result
            } catch (t: Throwable) {
                state = snapshot
                throw t
            }
        }
    }

    private suspend fun <T> write(block: () -> T): T =
        if (currentCoroutineContext()[InTransaction.Key] != null) block() else transaction { block() }

    /** Inside a transaction reads see its own writes; outside they see only committed data. */
    private suspend fun read(): State = if (currentCoroutineContext()[InTransaction.Key] != null) state else published.value

    override suspend fun insertExpense(e: ExpenseEntry) = write {
        check(!state.expenses.containsKey(e.id)) { "Expense ${e.id} exists" }
        state = state.copy(expenses = state.expenses + (e.id to e))
    }

    override suspend fun updateExpense(e: ExpenseEntry) = write {
        check(state.expenses.containsKey(e.id)) { "Expense ${e.id} missing" }
        state = state.copy(expenses = state.expenses + (e.id to e))
    }

    override suspend fun expense(id: String) = read().expenses[id]

    override suspend fun expenses(filter: ExpenseFilter) =
        read().expenses.values.filter(filter::matches).sortedWith(expenseOrder)

    override fun observeExpenses(filter: ExpenseFilter): Flow<List<ExpenseEntry>> =
        published.map { s -> s.expenses.values.filter(filter::matches).sortedWith(expenseOrder) }

    override suspend fun replaceAllocations(expenseId: String, allocations: List<ExpenseAllocation>) = write {
        state = state.copy(allocations = state.allocations + (expenseId to allocations))
    }

    override suspend fun allocations(expenseIds: Collection<String>) =
        expenseIds.flatMap { read().allocations[it].orEmpty() }

    override suspend fun insertLink(link: ExpenseSourceLink) = write {
        if (state.links.any { it.kind == link.kind && it.targetId == link.targetId }) {
            throw UniqueConstraintException("${link.kind} ${link.targetId} already linked")
        }
        check(state.expenses.containsKey(link.expenseId)) { "Link to missing expense" }
        state = state.copy(links = state.links + link)
    }

    override suspend fun deleteLink(link: ExpenseSourceLink) = write {
        state = state.copy(links = state.links - link)
    }

    override suspend fun links(expenseId: String) = read().links.filter { it.expenseId == expenseId }

    override suspend fun linkForTarget(kind: LinkKind, targetId: String) =
        read().links.firstOrNull { it.kind == kind && it.targetId == targetId }

    override suspend fun insertRevision(r: ExpenseRevision) = write {
        state = state.copy(revisions = state.revisions + r)
    }

    override suspend fun revisions(expenseId: String) = read().revisions.filter { it.expenseId == expenseId }

    override suspend fun upsertReceipt(r: Receipt) = write {
        state = state.copy(receipts = state.receipts + (r.id to r))
    }

    override suspend fun receipt(id: String) = read().receipts[id]

    override suspend fun receipts(filter: ReceiptFilter) = filterReceipts(read(), filter)

    override fun observeReceipts(filter: ReceiptFilter): Flow<List<Receipt>> = published.map { filterReceipts(it, filter) }

    private fun filterReceipts(s: State, filter: ReceiptFilter) = s.receipts.values
        .filter { it.status in filter.statuses }
        .filter { r ->
            val q = filter.text?.trim()?.lowercase()
            q.isNullOrEmpty() || (r.merchant ?: "").lowercase().contains(q) || r.rawText.lowercase().contains(q) ||
                r.items.any { it.description.lowercase().contains(q) }
        }
        .sortedWith(compareByDescending<Receipt> { it.purchaseDate }.thenByDescending { it.scannedAt })

    override suspend fun hardDeleteReceipt(id: String) = write {
        check(state.links.none { it.kind == LinkKind.RECEIPT && it.targetId == id }) { "Receipt still linked" }
        state = state.copy(receipts = state.receipts - id)
    }

    override suspend fun upsertInvoice(i: Invoice) = write {
        state = state.copy(invoices = state.invoices + (i.id to i))
    }

    override suspend fun invoice(id: String) = read().invoices[id]
    override suspend fun invoices() = read().invoices.values.sortedBy { it.dueDate }
    override fun observeInvoices(): Flow<List<Invoice>> = published.map { s -> s.invoices.values.sortedBy { it.dueDate } }

    override suspend fun insertPayment(p: PaymentRecord) = write {
        check(state.invoices.containsKey(p.invoiceId)) { "Payment for missing invoice" }
        check(!state.payments.containsKey(p.id)) { "Payment exists" }
        state = state.copy(payments = state.payments + (p.id to p))
    }

    override suspend fun payment(id: String) = read().payments[id]
    override suspend fun payments(invoiceId: String) = read().payments.values.filter { it.invoiceId == invoiceId }.sortedBy { it.paidDate }

    override suspend fun insertCommand(c: CommandExecution) = write {
        if (state.commands.containsKey(c.executionKey)) throw UniqueConstraintException("Execution key ${c.executionKey} used")
        state = state.copy(commands = state.commands + (c.executionKey to c))
    }

    override suspend fun updateCommand(c: CommandExecution) = write {
        state = state.copy(commands = state.commands + (c.executionKey to c))
    }

    override suspend fun command(executionKey: String) = read().commands[executionKey]

    override suspend fun recentCommands(limit: Int) =
        read().commands.values.sortedByDescending { it.executedAt }.take(limit)

    private companion object {
        val expenseOrder = compareByDescending<ExpenseEntry> { it.effectiveDate }.thenByDescending { it.recordedAt }
    }
}
