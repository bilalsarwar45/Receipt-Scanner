package com.receiptscan.expenses

import com.receiptscan.money.Allocation
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import com.receiptscan.money.totalsByCurrency
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random = IdGenerator { UUID.randomUUID().toString() }
    }
}

/** A user-facing validation problem. [field] matches the form field to highlight. */
data class FieldError(val field: String, val message: String)

class LedgerValidationException(val errors: List<FieldError>) :
    IllegalArgumentException(errors.joinToString("; ") { "${it.field}: ${it.message}" })

data class NewExpense(
    val amount: Money,
    val categoryId: String,
    val effectiveDate: LocalDate,
    val source: ExpenseSource,
    val merchant: String? = null,
    val note: String? = null,
    val kind: ExpenseKind = ExpenseKind.PURCHASE,
    val originalExpenseId: String? = null,
)

data class ExpenseEdit(
    val amount: Money? = null,
    val categoryId: String? = null,
    val effectiveDate: LocalDate? = null,
    val merchant: String? = null,
    val note: String? = null,
    val clearMerchant: Boolean = false,
    val clearNote: Boolean = false,
)

sealed interface AddResult {
    val expense: ExpenseEntry
    data class Added(override val expense: ExpenseEntry) : AddResult
    /** The execution key was used before: nothing new was written. */
    data class AlreadyExecuted(override val expense: ExpenseEntry) : AddResult
}

sealed interface UndoResult {
    data class Undone(val expense: ExpenseEntry) : UndoResult
    data object AlreadyUndone : UndoResult
    data object NotFound : UndoResult
    data class NotUndoable(val reason: String) : UndoResult
}

/** What to do when a receipt is attached to an expense whose amount differs. */
enum class AmountChoice { USE_RECEIPT_TOTAL, KEEP_EXPENSE_AMOUNT }

sealed interface ConfirmResult {
    data class Confirmed(val receipt: Receipt, val expense: ExpenseEntry, val createdExpense: Boolean) : ConfirmResult
    /** The receipt total differs from the expense it is being attached to; nothing was written. */
    data class NeedsAmountChoice(val receiptTotal: Money, val expenseAmount: Money) : ConfirmResult
}

/** Remembers what a delete changed so Undo within the snackbar window can restore it exactly. */
data class DeleteToken(
    val receiptId: String,
    val previousStatus: ReceiptStatus,
    val voidedExpenseId: String?,
    val removedLink: ExpenseSourceLink?,
)

data class InvoiceBalance(val invoice: Invoice, val paid: Money, val remaining: Money, val payments: List<PaymentRecord>)

/**
 * Application use cases for the canonical ledger. Every write runs in one store transaction, and
 * every total is derived from [ExpenseEntry] rows, so receipts, voice and manual entries are counted
 * once each. See docs/ARCHITECTURE.md for the reconciliation rules this enforces.
 */
class LedgerService(
    private val store: LedgerStore,
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val ids: IdGenerator = IdGenerator.Random,
) {
    private fun now() = clock.instant()
    private fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    // ---------------- Manual / voice / typed expenses ----------------

    fun validate(new: NewExpense): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (new.amount.minor <= 0) errors += FieldError("amount", "Must be more than 0")
        if (new.amount.toDecimal() > Money.MAX_MAJOR) errors += FieldError("amount", "Amount is too large")
        if (new.effectiveDate.isAfter(today().plusDays(1))) errors += FieldError("date", "This date is in the future")
        if (new.categoryId.isBlank()) errors += FieldError("category", "Pick a category")
        if ((new.merchant?.length ?: 0) > 60) errors += FieldError("merchant", "Use 60 characters or fewer")
        if ((new.note?.length ?: 0) > 500) errors += FieldError("note", "Use 500 characters or fewer")
        if (new.source == ExpenseSource.RECEIPT || new.source == ExpenseSource.BILL_PAYMENT) {
            errors += FieldError("source", "Receipt and bill expenses are created from their records")
        }
        return errors
    }

    /**
     * Adds a confirmed expense exactly once per [executionKey]. Repeated calls with the same key
     * (duplicate speech callbacks, rotation, retries) return the original entry and write nothing.
     */
    suspend fun addExpense(new: NewExpense, executionKey: String, commandType: String = "AddExpense"): AddResult {
        require(executionKey.isNotBlank()) { "Execution key required" }
        val errors = validate(new)
        if (errors.isNotEmpty()) throw LedgerValidationException(errors)
        store.command(executionKey)?.let { return AddResult.AlreadyExecuted(existingFor(it)) }
        return try {
            store.transaction {
                store.command(executionKey)?.let { return@transaction AddResult.AlreadyExecuted(existingFor(it)) }
                if (new.kind == ExpenseKind.REFUND && new.originalExpenseId != null) checkRefundAllowed(new)
                val entry = ExpenseEntry(
                    id = ids.newId(),
                    amount = new.amount,
                    kind = new.kind,
                    effectiveDate = new.effectiveDate,
                    recordedAt = now(),
                    zoneId = zone().id,
                    categoryId = new.categoryId,
                    merchant = new.merchant?.trim()?.takeIf { it.isNotEmpty() },
                    note = new.note?.trim()?.takeIf { it.isNotEmpty() },
                    source = new.source,
                    state = ExpenseState.CONFIRMED,
                    originalExpenseId = new.originalExpenseId,
                    createdByKey = executionKey,
                )
                store.insertExpense(entry)
                store.insertCommand(
                    CommandExecution(executionKey, commandType, now(), entry.id, describe(entry)),
                )
                store.insertRevision(ExpenseRevision(entry.id, 1, now(), "Created (${entry.source.label})"))
                AddResult.Added(entry)
            }
        } catch (e: UniqueConstraintException) {
            val existing = store.command(executionKey) ?: throw e
            AddResult.AlreadyExecuted(existingFor(existing))
        }
    }

    private suspend fun existingFor(c: CommandExecution): ExpenseEntry =
        c.affectedExpenseId?.let { store.expense(it) } ?: error("Command ${c.executionKey} has no expense")

    private suspend fun checkRefundAllowed(new: NewExpense) {
        val original = store.expense(new.originalExpenseId!!)
            ?: throw LedgerValidationException(listOf(FieldError("original", "Original purchase not found")))
        if (original.kind == ExpenseKind.REFUND) throw LedgerValidationException(listOf(FieldError("original", "Cannot refund a refund")))
        if (original.amount.currency != new.amount.currency) throw LedgerValidationException(listOf(FieldError("amount", "Refund must use ${original.amount.currency.code}")))
        val priorRefunds = store.expenses(ExpenseFilter(kinds = setOf(ExpenseKind.REFUND)))
            .filter { it.originalExpenseId == original.id }
            .fold(Money.zero(original.amount.currency)) { a, e -> a + e.amount }
        if (priorRefunds + new.amount > original.amount) {
            throw LedgerValidationException(listOf(FieldError("amount", "Refunds would exceed the original ${MoneyFormat.format(original.amount)}")))
        }
    }

    /** Records a refund on its own effective date, linked to the original purchase when known. */
    suspend fun recordRefund(amount: Money, date: LocalDate, executionKey: String, originalExpenseId: String?, categoryId: String? = null, note: String? = null): AddResult {
        val original = originalExpenseId?.let { store.expense(it) }
        return addExpense(
            NewExpense(
                amount = amount,
                categoryId = categoryId ?: original?.categoryId ?: Categories.UNCATEGORIZED,
                effectiveDate = date,
                source = ExpenseSource.MANUAL,
                merchant = original?.merchant,
                note = note,
                kind = ExpenseKind.REFUND,
                originalExpenseId = originalExpenseId,
            ),
            executionKey,
            commandType = "RecordRefund",
        )
    }

    suspend fun editExpense(id: String, edit: ExpenseEdit, reason: String = "Edited"): ExpenseEntry = store.transaction {
        val current = store.expense(id) ?: throw NoSuchElementException("Expense $id")
        check(current.state != ExpenseState.VOIDED) { "Cannot edit a voided expense" }
        val updated = current.copy(
            amount = edit.amount ?: current.amount,
            categoryId = edit.categoryId ?: current.categoryId,
            effectiveDate = edit.effectiveDate ?: current.effectiveDate,
            merchant = if (edit.clearMerchant) null else edit.merchant?.trim() ?: current.merchant,
            note = if (edit.clearNote) null else edit.note?.trim() ?: current.note,
            version = current.version + 1,
        )
        if (edit.amount != null && edit.amount.currency != current.amount.currency) {
            throw LedgerValidationException(listOf(FieldError("amount", "Currency cannot change; void and re-add instead")))
        }
        if (edit.amount != null && edit.amount.minor <= 0) throw LedgerValidationException(listOf(FieldError("amount", "Must be more than 0")))
        if (updated.effectiveDate.isAfter(today().plusDays(1))) throw LedgerValidationException(listOf(FieldError("date", "This date is in the future")))
        if (updated == current.copy(version = updated.version)) return@transaction current
        store.updateExpense(updated)
        if (edit.amount != null || edit.categoryId != null) store.replaceAllocations(id, emptyList())
        store.insertRevision(ExpenseRevision(id, updated.version, now(), "$reason: ${diff(current, updated)}"))
        updated
    }

    suspend fun voidExpense(id: String, reason: String): ExpenseEntry = store.transaction {
        val current = store.expense(id) ?: throw NoSuchElementException("Expense $id")
        if (current.state == ExpenseState.VOIDED) return@transaction current
        val updated = current.copy(state = ExpenseState.VOIDED, version = current.version + 1, voidReason = reason)
        store.updateExpense(updated)
        store.insertRevision(ExpenseRevision(id, updated.version, now(), "Voided: $reason"))
        updated
    }

    suspend fun restoreExpense(id: String): ExpenseEntry = store.transaction {
        val current = store.expense(id) ?: throw NoSuchElementException("Expense $id")
        if (current.state != ExpenseState.VOIDED) return@transaction current
        val updated = current.copy(state = ExpenseState.CONFIRMED, version = current.version + 1, voidReason = null)
        store.updateExpense(updated)
        store.insertRevision(ExpenseRevision(id, updated.version, now(), "Restored"))
        updated
    }

    /**
     * Undoes the recorded effect of one command. Undoing twice is harmless; totals change exactly once.
     */
    suspend fun undo(executionKey: String): UndoResult = store.transaction {
        val cmd = store.command(executionKey) ?: return@transaction UndoResult.NotFound
        if (cmd.undone) return@transaction UndoResult.AlreadyUndone
        val expenseId = cmd.affectedExpenseId ?: return@transaction UndoResult.NotUndoable("Nothing to undo")
        val expense = store.expense(expenseId) ?: return@transaction UndoResult.NotFound
        if (store.links(expenseId).isNotEmpty()) {
            return@transaction UndoResult.NotUndoable("A receipt or payment is attached; remove it first")
        }
        val voided = voidExpense(expense.id, "Undo")
        store.updateCommand(cmd.copy(undone = true))
        UndoResult.Undone(voided)
    }

    // ---------------- Receipts ----------------

    /** Saves a receipt for later review. It is excluded from totals until confirmed. */
    suspend fun saveReceiptDraft(receipt: Receipt): Receipt = store.transaction {
        val existing = store.receipt(receipt.id)
        check(existing?.status != ReceiptStatus.CONFIRMED) { "Confirmed receipts are edited through confirmReceipt" }
        val draft = receipt.copy(status = ReceiptStatus.NEEDS_REVIEW)
        store.upsertReceipt(draft)
        draft
    }

    fun validateForConfirm(r: Receipt): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        val merchant = r.merchant?.trim().orEmpty()
        if (merchant.isEmpty()) errors += FieldError("merchant", "Add a merchant name")
        if (merchant.length > 60) errors += FieldError("merchant", "Use 60 characters or fewer")
        if (r.purchaseDate == null) errors += FieldError("date", "Add the purchase date")
        else if (r.purchaseDate.isAfter(today().plusDays(1))) errors += FieldError("date", "This date is in the future")
        val total = r.total
        if (total == null) errors += FieldError("total", "Add the total")
        else if (total.minor <= 0) errors += FieldError("total", "Must be more than 0")
        else if (total.currency != r.currency) errors += FieldError("total", "Total currency differs from receipt currency")
        return errors
    }

    /**
     * Confirms a purchase receipt. Creates one linked expense, or updates the one it already supports,
     * or (with [attachToExpenseId]) enriches an existing voice/manual expense instead of adding another.
     */
    suspend fun confirmReceipt(
        receipt: Receipt,
        attachToExpenseId: String? = null,
        amountChoice: AmountChoice? = null,
    ): ConfirmResult {
        val errors = validateForConfirm(receipt)
        if (errors.isNotEmpty()) throw LedgerValidationException(errors)
        require(!receipt.isPaymentReceipt) { "Payment receipts attach to bill payments" }
        val total = receipt.total!!
        return store.transaction {
            val confirmed = receipt.copy(status = ReceiptStatus.CONFIRMED, merchant = receipt.merchant!!.trim(), reviewReasons = emptyList())
            val existingLink = store.linkForTarget(LinkKind.RECEIPT, receipt.id)

            if (existingLink != null && (attachToExpenseId == null || attachToExpenseId == existingLink.expenseId)) {
                // Editing an already confirmed receipt updates its canonical expense exactly once.
                val expense = store.expense(existingLink.expenseId)!!
                val newAmount = when {
                    expense.source == ExpenseSource.RECEIPT -> total
                    total == expense.amount -> total
                    amountChoice == null -> return@transaction ConfirmResult.NeedsAmountChoice(total, expense.amount)
                    amountChoice == AmountChoice.USE_RECEIPT_TOTAL -> total
                    else -> expense.amount
                }
                store.upsertReceipt(confirmed)
                val updated = applyReceiptToExpense(expense, confirmed, newAmount, "Receipt edited")
                writeAllocations(updated, confirmed)
                return@transaction ConfirmResult.Confirmed(confirmed, updated, createdExpense = false)
            }

            if (attachToExpenseId != null) {
                val target = store.expense(attachToExpenseId) ?: throw NoSuchElementException("Expense $attachToExpenseId")
                check(target.state == ExpenseState.CONFIRMED) { "Can only attach to a confirmed expense" }
                check(target.kind == ExpenseKind.PURCHASE) { "Receipts attach to purchases" }
                if (target.amount.currency != total.currency) {
                    throw LedgerValidationException(listOf(FieldError("total", "Expense is in ${target.amount.currency.code}")))
                }
                if (target.amount != total && amountChoice == null) {
                    return@transaction ConfirmResult.NeedsAmountChoice(total, target.amount)
                }
                if (store.links(target.id).any { it.kind == LinkKind.RECEIPT }) {
                    throw LedgerValidationException(listOf(FieldError("receipt", "That expense already has a receipt")))
                }
                if (existingLink != null) {
                    // The receipt previously created its own expense: merge into the target, void the old entry.
                    store.deleteLink(existingLink)
                    voidExpense(existingLink.expenseId, "Merged into ${target.id}")
                }
                store.upsertReceipt(confirmed)
                store.insertLink(ExpenseSourceLink(target.id, LinkKind.RECEIPT, receipt.id))
                val amount = if (amountChoice == AmountChoice.USE_RECEIPT_TOTAL) total else target.amount
                val updated = applyReceiptToExpense(target, confirmed, amount, "Receipt attached")
                writeAllocations(updated, confirmed)
                return@transaction ConfirmResult.Confirmed(confirmed, updated, createdExpense = false)
            }

            store.upsertReceipt(confirmed)
            val entry = ExpenseEntry(
                id = ids.newId(),
                amount = total,
                kind = ExpenseKind.PURCHASE,
                effectiveDate = confirmed.purchaseDate!!,
                recordedAt = now(),
                zoneId = zone().id,
                categoryId = confirmed.categoryId ?: Categories.UNCATEGORIZED,
                merchant = confirmed.merchant,
                note = confirmed.note,
                source = ExpenseSource.RECEIPT,
                state = ExpenseState.CONFIRMED,
            )
            store.insertExpense(entry)
            store.insertLink(ExpenseSourceLink(entry.id, LinkKind.RECEIPT, receipt.id))
            store.insertRevision(ExpenseRevision(entry.id, 1, now(), "Created from receipt"))
            writeAllocations(entry, confirmed)
            ConfirmResult.Confirmed(confirmed, entry, createdExpense = true)
        }
    }

    private suspend fun applyReceiptToExpense(expense: ExpenseEntry, r: Receipt, amount: Money, reason: String): ExpenseEntry {
        val updated = expense.copy(
            amount = amount,
            effectiveDate = r.purchaseDate ?: expense.effectiveDate,
            merchant = r.merchant ?: expense.merchant,
            categoryId = if (expense.source == ExpenseSource.RECEIPT) (r.categoryId ?: expense.categoryId) else expense.categoryId,
            version = expense.version + 1,
        )
        if (updated == expense.copy(version = updated.version)) return expense
        store.updateExpense(updated)
        store.insertRevision(ExpenseRevision(expense.id, updated.version, now(), "$reason: ${diff(expense, updated)}"))
        return updated
    }

    /**
     * Splits the expense amount over item categories in proportion to item line totals, so tax and
     * discounts are shared exactly and category totals still add up to the expense.
     */
    private suspend fun writeAllocations(expense: ExpenseEntry, r: Receipt) {
        val items = r.items.filter { it.categoryId != null && it.lineTotal.minor > 0 && it.lineTotal.currency == expense.amount.currency }
        val categories = items.map { it.categoryId!! }.distinct()
        if (items.isEmpty() || categories.size < 2) {
            store.replaceAllocations(expense.id, emptyList())
            return
        }
        val weights = categories.map { c -> items.filter { it.categoryId == c }.sumOf { it.lineTotal.minor } }
        val parts = Allocation.allocate(expense.amount, weights)
        store.replaceAllocations(expense.id, categories.zip(parts).map { (c, m) -> ExpenseAllocation(expense.id, c, m) })
    }

    /**
     * Soft-deletes a receipt. A receipt-created expense is voided; a voice/manual expense it supported
     * stays and only loses the attachment. The token restores everything if the person taps Undo.
     */
    suspend fun deleteReceipt(receiptId: String): DeleteToken = store.transaction {
        val r = store.receipt(receiptId) ?: throw NoSuchElementException("Receipt $receiptId")
        val link = store.linkForTarget(LinkKind.RECEIPT, receiptId)
        var voided: String? = null
        var removed: ExpenseSourceLink? = null
        if (link != null) {
            val e = store.expense(link.expenseId)!!
            if (e.source == ExpenseSource.RECEIPT) {
                voidExpense(e.id, "Receipt deleted")
                voided = e.id
            } else {
                store.deleteLink(link)
                removed = link
                store.insertRevision(ExpenseRevision(e.id, e.version, now(), "Receipt removed"))
            }
        }
        store.upsertReceipt(r.copy(status = ReceiptStatus.DELETED))
        DeleteToken(receiptId, r.status, voided, removed)
    }

    suspend fun undoDelete(token: DeleteToken) = store.transaction {
        val r = store.receipt(token.receiptId) ?: return@transaction
        if (r.status != ReceiptStatus.DELETED) return@transaction
        store.upsertReceipt(r.copy(status = token.previousStatus))
        token.voidedExpenseId?.let { restoreExpense(it) }
        token.removedLink?.let { if (store.linkForTarget(it.kind, it.targetId) == null) store.insertLink(it) }
    }

    /** Permanently removes a soft-deleted receipt once the undo window has passed. */
    suspend fun purgeReceipt(receiptId: String) = store.transaction {
        val r = store.receipt(receiptId) ?: return@transaction
        check(r.status == ReceiptStatus.DELETED) { "Only deleted receipts can be purged" }
        store.linkForTarget(LinkKind.RECEIPT, receiptId)?.let { store.deleteLink(it) }
        store.hardDeleteReceipt(receiptId)
    }

    /**
     * Possible duplicates: same merchant and date with totals within 0.01 (handover 6.1 step 9), plus
     * voice/manual expenses with the same amount within a day. Suggestions only; nothing is merged.
     */
    suspend fun possibleDuplicates(r: Receipt): List<ExpenseEntry> {
        val total = r.total ?: return emptyList()
        val date = r.purchaseDate ?: return emptyList()
        val ownLink = store.linkForTarget(LinkKind.RECEIPT, r.id)?.expenseId
        val candidates = store.expenses(
            ExpenseFilter(range = com.receiptscan.time.LocalDateRange(date.minusDays(1), date.plusDays(2)), currencyCode = total.currency.code),
        )
        val merchantKey = r.merchant?.let(::merchantKey)
        val tolerance = total.currency.minorPerMajor / 100 // 0.01 in major units
        return candidates.filter { e ->
            e.id != ownLink && e.kind == ExpenseKind.PURCHASE && kotlin.math.abs(e.amount.minor - total.minor) <= tolerance &&
                when (e.source) {
                    ExpenseSource.RECEIPT -> e.effectiveDate == date && merchantKey != null && e.merchant?.let(::merchantKey) == merchantKey
                    else -> e.merchant == null || merchantKey == null || merchantKey(e.merchant) == merchantKey
                }
        }
    }

    private fun merchantKey(m: String) = m.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    // ---------------- Bills and payments ----------------

    /** Saves a captured bill. This never creates an expense. */
    suspend fun saveInvoice(invoice: Invoice): Invoice = store.transaction {
        val payments = store.payments(invoice.id)
        val saved = invoice.copy(status = statusFor(invoice, payments))
        store.upsertInvoice(saved)
        saved
    }

    suspend fun balance(invoiceId: String): InvoiceBalance {
        val inv = store.invoice(invoiceId) ?: throw NoSuchElementException("Invoice $invoiceId")
        val payments = activePayments(inv.id)
        val paid = payments.fold(Money.zero(inv.amountDue.currency)) { a, p -> a + p.amount }
        val remaining = (inv.amountDue - paid).let { if (it.isNegative) Money.zero(it.currency) else it }
        return InvoiceBalance(inv, paid, remaining, payments)
    }

    private suspend fun activePayments(invoiceId: String) = store.payments(invoiceId).filter { p ->
        store.expense(p.expenseId)?.state == ExpenseState.CONFIRMED
    }

    private suspend fun statusFor(inv: Invoice, all: List<PaymentRecord>): InvoiceStatus {
        if (inv.status == InvoiceStatus.VOID) return InvoiceStatus.VOID
        val paid = all.filter { store.expense(it.expenseId)?.state == ExpenseState.CONFIRMED }
            .fold(Money.zero(inv.amountDue.currency)) { a, p -> a + p.amount }
        return when {
            paid.isZero -> InvoiceStatus.UNPAID
            paid < inv.amountDue -> InvoiceStatus.PARTIALLY_PAID
            else -> InvoiceStatus.PAID
        }
    }

    /**
     * Records a (possibly partial) payment. Creates exactly one linked bill-payment expense per
     * [executionKey]; the payment cannot exceed the remaining balance.
     */
    suspend fun recordPayment(invoiceId: String, amount: Money, paidDate: LocalDate, executionKey: String, method: String? = null): PaymentRecord {
        store.command(executionKey)?.let { c -> return paymentForExpense(invoiceId, c.affectedExpenseId) }
        return store.transaction {
            store.command(executionKey)?.let { c -> return@transaction paymentForExpense(invoiceId, c.affectedExpenseId) }
            val bal = balance(invoiceId)
            val inv = bal.invoice
            if (inv.status == InvoiceStatus.VOID) throw LedgerValidationException(listOf(FieldError("invoice", "This bill was voided")))
            if (amount.currency != inv.amountDue.currency) throw LedgerValidationException(listOf(FieldError("amount", "Use ${inv.amountDue.currency.code}")))
            if (amount.minor <= 0) throw LedgerValidationException(listOf(FieldError("amount", "Must be more than 0")))
            if (amount > bal.remaining) throw LedgerValidationException(listOf(FieldError("amount", "Only ${MoneyFormat.format(bal.remaining)} is left to pay")))
            if (paidDate.isAfter(today().plusDays(1))) throw LedgerValidationException(listOf(FieldError("date", "This date is in the future")))
            val expense = ExpenseEntry(
                id = ids.newId(),
                amount = amount,
                kind = ExpenseKind.BILL_PAYMENT,
                effectiveDate = paidDate,
                recordedAt = now(),
                zoneId = zone().id,
                categoryId = inv.categoryId,
                merchant = inv.supplier,
                note = inv.invoiceNumber?.let { "Invoice $it" },
                source = ExpenseSource.BILL_PAYMENT,
                state = ExpenseState.CONFIRMED,
                createdByKey = executionKey,
            )
            val payment = PaymentRecord(ids.newId(), inv.id, amount, paidDate, expense.id, method)
            store.insertExpense(expense)
            store.insertPayment(payment)
            store.insertLink(ExpenseSourceLink(expense.id, LinkKind.BILL_PAYMENT, payment.id))
            store.insertCommand(CommandExecution(executionKey, "RecordPayment", now(), expense.id, describe(expense)))
            store.insertRevision(ExpenseRevision(expense.id, 1, now(), "Created from bill payment"))
            store.upsertInvoice(inv.copy(status = statusFor(inv, store.payments(inv.id))))
            payment
        }
    }

    private suspend fun paymentForExpense(invoiceId: String, expenseId: String?): PaymentRecord =
        store.payments(invoiceId).first { it.expenseId == expenseId }

    /** Attaches a payment receipt to an existing bill payment as evidence. It is not counted again. */
    suspend fun attachPaymentReceipt(receipt: Receipt, paymentId: String): Receipt = store.transaction {
        val payment = store.payment(paymentId) ?: throw NoSuchElementException("Payment $paymentId")
        val prior = store.linkForTarget(LinkKind.RECEIPT, receipt.id)
        if (prior != null && prior.expenseId != payment.expenseId) {
            val priorExpense = store.expense(prior.expenseId)!!
            check(priorExpense.source == ExpenseSource.RECEIPT) { "Receipt supports another expense" }
            store.deleteLink(prior)
            voidExpense(prior.expenseId, "Receipt moved to bill payment")
        }
        val saved = receipt.copy(status = ReceiptStatus.CONFIRMED, isPaymentReceipt = true)
        store.upsertReceipt(saved)
        if (store.linkForTarget(LinkKind.RECEIPT, receipt.id) == null) {
            store.insertLink(ExpenseSourceLink(payment.expenseId, LinkKind.RECEIPT, receipt.id))
        }
        saved
    }

    /** Voids a payment's expense; the invoice balance and status follow. */
    suspend fun voidPayment(paymentId: String) = store.transaction {
        val payment = store.payment(paymentId) ?: throw NoSuchElementException("Payment $paymentId")
        voidExpense(payment.expenseId, "Payment voided")
        val inv = store.invoice(payment.invoiceId)!!
        store.upsertInvoice(inv.copy(status = statusFor(inv, store.payments(inv.id))))
    }

    // ---------------- Queries ----------------

    suspend fun query(filter: ExpenseFilter): ExpenseQueryResult = summarize(filter, store.expenses(filter))

    suspend fun summarize(filter: ExpenseFilter, entries: List<ExpenseEntry>): ExpenseQueryResult {
        val allocations = store.allocations(entries.map { it.id }).groupBy { it.expenseId }
        return ExpenseQueryResult.of(filter, entries, allocations)
    }

    private fun describe(e: ExpenseEntry): String =
        "${MoneyFormat.formatShort(e.amount)} · ${Categories.name(e.categoryId)} · ${e.effectiveDate}"

    private fun diff(a: ExpenseEntry, b: ExpenseEntry): String = buildList {
        if (a.amount != b.amount) add("amount ${MoneyFormat.format(a.amount)} → ${MoneyFormat.format(b.amount)}")
        if (a.categoryId != b.categoryId) add("category ${a.categoryId} → ${b.categoryId}")
        if (a.effectiveDate != b.effectiveDate) add("date ${a.effectiveDate} → ${b.effectiveDate}")
        if (a.merchant != b.merchant) add("merchant")
        if (a.note != b.note) add("note")
    }.joinToString(", ").ifEmpty { "no change" }
}

/** Totals per currency, never summed across currencies. */
data class CurrencyTotal(val total: Money, val count: Int)

data class CategoryTotal(val categoryId: String, val total: Money)

/** Deterministic answer to an expense query: the records themselves plus totals calculated from them. */
data class ExpenseQueryResult(
    val filter: ExpenseFilter,
    val entries: List<ExpenseEntry>,
    val totals: List<CurrencyTotal>,
    /** Category totals per currency code, using receipt item allocations where present. */
    val byCategory: Map<String, List<CategoryTotal>>,
    val bySource: Map<ExpenseSource, Int>,
) {
    val count: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    companion object {
        fun of(filter: ExpenseFilter, entries: List<ExpenseEntry>, allocations: Map<String, List<ExpenseAllocation>>): ExpenseQueryResult {
            val totals = entries.groupBy { it.amount.currency.code }.map { (_, list) ->
                CurrencyTotal(list.map { it.signedAmount }.totalsByCurrency().values.single(), list.size)
            }.sortedByDescending { it.count }
            val byCategory = entries.groupBy { it.amount.currency.code }.mapValues { (_, list) ->
                val parts = list.flatMap { e ->
                    val allocs = allocations[e.id].orEmpty()
                    if (allocs.isEmpty()) listOf(e.categoryId to e.signedAmount)
                    else allocs.map { it.categoryId to (if (e.kind.sign < 0) -it.amount else it.amount) }
                }
                val filtered = if (filter.categoryIds == null) parts else parts.filter { it.first in filter.categoryIds }
                filtered.groupBy({ it.first }, { it.second }).map { (c, ms) -> CategoryTotal(c, ms.reduce { a, b -> a + b }) }
                    .sortedByDescending { it.total.minor }
            }
            return ExpenseQueryResult(filter, entries, totals, byCategory, entries.groupingBy { it.source }.eachCount())
        }
    }
}
