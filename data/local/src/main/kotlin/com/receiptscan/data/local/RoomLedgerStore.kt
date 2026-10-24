package com.receiptscan.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.receiptscan.expenses.CommandExecution
import com.receiptscan.expenses.ExpenseAllocation
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.ExpenseKind
import com.receiptscan.expenses.ExpenseRevision
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.ExpenseSourceLink
import com.receiptscan.expenses.ExpenseState
import com.receiptscan.expenses.Invoice
import com.receiptscan.expenses.InvoiceStatus
import com.receiptscan.expenses.LedgerStore
import com.receiptscan.expenses.LinkKind
import com.receiptscan.expenses.PaymentRecord
import com.receiptscan.expenses.Receipt
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptItem
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.expenses.UniqueConstraintException
import com.receiptscan.money.Currencies
import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

internal fun currency(code: String): CurrencySpec = Currencies.find(code) ?: CurrencySpec(code.uppercase(), 2)
internal fun money(minor: Long, code: String) = Money(minor, currency(code))
internal fun Long.day(): LocalDate = LocalDate.ofEpochDay(this)
internal fun Long.instant(): Instant = Instant.ofEpochMilli(this)

private val json = Json { ignoreUnknownKeys = true }
private val stringList = ListSerializer(String.serializer())
internal fun List<String>.toJson(): String = json.encodeToString(stringList, this)
internal fun String.toStringList(): List<String> = if (isBlank()) emptyList() else runCatching { json.decodeFromString(stringList, this) }.getOrDefault(emptyList())

/** Builds an FTS4 MATCH expression with prefix matching on every word. */
fun ftsQuery(text: String): String? {
    val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return null
    return words.joinToString(" ") { "$it*" }
}

/**
 * Room implementation of the ledger port. Writes inside [transaction] share one SQLite transaction;
 * unique constraints (execution keys, evidence links) are enforced by primary keys and surface as
 * [UniqueConstraintException], exactly like the in-memory store used by the domain tests.
 */
class RoomLedgerStore(private val db: AppDatabase) : LedgerStore {
    private val dao = db.ledgerDao()

    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    private inline fun <T> unique(what: String, block: () -> T): T = try {
        block()
    } catch (e: SQLiteConstraintException) {
        throw UniqueConstraintException("$what: ${e.message}")
    }

    // ---- expenses

    override suspend fun insertExpense(e: ExpenseEntry) = unique("expense ${e.id}") { dao.insertExpense(e.toRow()) }

    override suspend fun updateExpense(e: ExpenseEntry) {
        checkNotNull(dao.expense(e.id)) { "Expense ${e.id} missing" }
        dao.upsertExpense(e.toRow())
    }

    override suspend fun expense(id: String) = dao.expense(id)?.toDomain()

    override suspend fun expenses(filter: ExpenseFilter): List<ExpenseEntry> {
        val (from, to) = bounds(filter)
        return dao.expensesBetween(from, to).map { it.toDomain() }.filter(filter::matches)
    }

    override fun observeExpenses(filter: ExpenseFilter): Flow<List<ExpenseEntry>> {
        val (from, to) = bounds(filter)
        return dao.observeExpensesBetween(from, to).map { rows -> rows.map { it.toDomain() }.filter(filter::matches) }
    }

    private fun bounds(filter: ExpenseFilter): Pair<Long, Long> =
        (filter.range?.startInclusive?.toEpochDay() ?: Long.MIN_VALUE) to (filter.range?.endExclusive?.toEpochDay() ?: Long.MAX_VALUE)

    override suspend fun replaceAllocations(expenseId: String, allocations: List<ExpenseAllocation>) {
        dao.deleteAllocations(expenseId)
        if (allocations.isNotEmpty()) dao.insertAllocations(allocations.map { AllocationRow(0, it.expenseId, it.categoryId, it.amount.minor, it.amount.currency.code) })
    }

    override suspend fun allocations(expenseIds: Collection<String>): List<ExpenseAllocation> =
        expenseIds.chunked(500).flatMap { chunk -> dao.allocations(chunk) }.map { ExpenseAllocation(it.expenseId, it.categoryId, money(it.amountMinor, it.currency)) }

    // ---- links and revisions

    override suspend fun insertLink(link: ExpenseSourceLink) {
        checkNotNull(dao.expense(link.expenseId)) { "Link to missing expense" }
        unique("${link.kind} ${link.targetId} already linked") { dao.insertLink(LinkRow(link.expenseId, link.kind.name, link.targetId)) }
    }

    override suspend fun deleteLink(link: ExpenseSourceLink) = dao.deleteLink(link.expenseId, link.kind.name, link.targetId)
    override suspend fun links(expenseId: String) = dao.links(expenseId).map { ExpenseSourceLink(it.expenseId, LinkKind.valueOf(it.kind), it.targetId) }
    override suspend fun linkForTarget(kind: LinkKind, targetId: String) =
        dao.linkForTarget(kind.name, targetId)?.let { ExpenseSourceLink(it.expenseId, LinkKind.valueOf(it.kind), it.targetId) }

    override suspend fun insertRevision(r: ExpenseRevision) = dao.insertRevision(RevisionRow(0, r.expenseId, r.version, r.changedAt.toEpochMilli(), r.change))
    override suspend fun revisions(expenseId: String) = dao.revisions(expenseId).map { ExpenseRevision(it.expenseId, it.version, it.changedAt.instant(), it.change) }

    // ---- receipts

    override suspend fun upsertReceipt(r: Receipt) = db.withTransaction {
        dao.upsertReceipt(r.toRow())
        dao.deleteItems(r.id)
        if (r.items.isNotEmpty()) dao.insertItems(r.items.map { it.toRow() })
        dao.deleteFts(r.id)
        if (r.status != ReceiptStatus.DELETED) {
            dao.insertFts(ReceiptFts(r.id, r.merchant.orEmpty(), r.items.joinToString("\n") { it.description }, r.rawText))
        }
    }

    override suspend fun receipt(id: String): Receipt? {
        val row = dao.receipt(id) ?: return null
        return row.toDomain(dao.items(listOf(id)))
    }

    override suspend fun receipts(filter: ReceiptFilter): List<Receipt> {
        val rows = dao.receipts(filter.statuses.map { it.name })
        val q = filter.text?.let(::ftsQuery)
        val matching = if (q == null) rows else dao.searchReceiptIds(q).toSet().let { ids -> rows.filter { it.id in ids } }
        return attachItems(matching)
    }

    override fun observeReceipts(filter: ReceiptFilter): Flow<List<Receipt>> =
        combine(dao.observeReceipts(filter.statuses.map { it.name }), dao.observeAllItems()) { rows, items ->
            val q = filter.text?.trim()?.lowercase()
            val byReceipt = items.groupBy { it.receiptId }
            rows.map { it.toDomain(byReceipt[it.id].orEmpty()) }.filter { r ->
                q.isNullOrEmpty() || (r.merchant ?: "").lowercase().contains(q) || r.rawText.lowercase().contains(q) || r.items.any { it.description.lowercase().contains(q) }
            }
        }

    private suspend fun attachItems(rows: List<ReceiptRow>): List<Receipt> {
        if (rows.isEmpty()) return emptyList()
        val items = rows.map { it.id }.chunked(500).flatMap { dao.items(it) }.groupBy { it.receiptId }
        return rows.map { it.toDomain(items[it.id].orEmpty()) }
    }

    override suspend fun hardDeleteReceipt(id: String) = db.withTransaction {
        check(dao.linkForTarget(LinkKind.RECEIPT.name, id) == null) { "Receipt still linked" }
        dao.deleteItems(id)
        dao.deleteFts(id)
        dao.deleteReceipt(id)
    }

    // ---- invoices and payments

    override suspend fun upsertInvoice(i: Invoice) = dao.upsertInvoice(i.toRow())
    override suspend fun invoice(id: String) = dao.invoice(id)?.toDomain()
    override suspend fun invoices() = dao.invoices().map { it.toDomain() }
    override fun observeInvoices(): Flow<List<Invoice>> = dao.observeInvoices().map { l -> l.map { it.toDomain() } }
    override suspend fun insertPayment(p: PaymentRecord) = unique("payment ${p.id}") {
        dao.insertPayment(PaymentRow(p.id, p.invoiceId, p.amount.minor, p.amount.currency.code, p.paidDate.toEpochDay(), p.expenseId, p.method))
    }
    override suspend fun payment(id: String) = dao.payment(id)?.toDomain()
    override suspend fun payments(invoiceId: String) = dao.payments(invoiceId).map { it.toDomain() }

    // ---- commands

    override suspend fun insertCommand(c: CommandExecution) = unique("execution key ${c.executionKey} already used") { dao.insertCommand(c.toRow()) }
    override suspend fun updateCommand(c: CommandExecution) = dao.upsertCommand(c.toRow())
    override suspend fun command(executionKey: String) = dao.command(executionKey)?.toDomain()
    override suspend fun recentCommands(limit: Int) = dao.recentCommands(limit).map { it.toDomain() }
}

// ---------------------------------------------------------------- mappers

internal fun ExpenseEntry.toRow() = ExpenseRow(
    id, amount.minor, amount.currency.code, kind.name, effectiveDate.toEpochDay(), recordedAt.toEpochMilli(), zoneId, categoryId,
    merchant, note, source.name, state.name, version, originalExpenseId, createdByKey, voidReason,
)

internal fun ExpenseRow.toDomain() = ExpenseEntry(
    id = id, amount = money(amountMinor, currency), kind = ExpenseKind.valueOf(kind), effectiveDate = effectiveDate.day(),
    recordedAt = recordedAt.instant(), zoneId = zoneId, categoryId = categoryId, merchant = merchant, note = note,
    source = ExpenseSource.valueOf(source), state = ExpenseState.valueOf(state), version = version,
    originalExpenseId = originalExpenseId, createdByKey = createdByKey, voidReason = voidReason,
)

internal fun Receipt.toRow() = ReceiptRow(
    id = id, status = status.name, merchant = merchant, purchaseDate = purchaseDate?.toEpochDay(), purchaseTime = purchaseTime,
    currency = currency.code, subtotalMinor = subtotal?.minor, taxMinor = tax?.minor, discountMinor = discount?.minor, totalMinor = total?.minor,
    categoryId = categoryId, scannedAt = scannedAt.toEpochMilli(), rawText = rawText, reference = reference, paymentMethod = paymentMethod,
    note = note, sourceImageIds = sourceImageIds.toJson(), ocrEngine = ocrEngine, reviewReasons = reviewReasons.toJson(), isPaymentReceipt = isPaymentReceipt,
)

internal fun ReceiptRow.toDomain(items: List<ReceiptItemRow>): Receipt {
    val c = currency(currency)
    return Receipt(
        id = id, status = ReceiptStatus.valueOf(status), merchant = merchant, purchaseDate = purchaseDate?.day(), purchaseTime = purchaseTime,
        currency = c, subtotal = subtotalMinor?.let { Money(it, c) }, tax = taxMinor?.let { Money(it, c) }, discount = discountMinor?.let { Money(it, c) },
        total = totalMinor?.let { Money(it, c) }, categoryId = categoryId, scannedAt = scannedAt.instant(), rawText = rawText, reference = reference,
        paymentMethod = paymentMethod, note = note, sourceImageIds = sourceImageIds.toStringList(), ocrEngine = ocrEngine,
        reviewReasons = reviewReasons.toStringList(), items = items.sortedBy { it.position }.map { it.toDomain() }, isPaymentReceipt = isPaymentReceipt,
    )
}

internal fun ReceiptItem.toRow() = ReceiptItemRow(
    id, receiptId, position, description, quantity?.toPlainString(), unit, unitPrice?.minor, lineTotal.minor, lineTotal.currency.code,
    categoryId, provenance, rawText, uncertain,
)

internal fun ReceiptItemRow.toDomain() = ReceiptItem(
    id = id, receiptId = receiptId, position = position, description = description, quantity = quantity?.let { BigDecimal(it) }, unit = unit,
    unitPrice = unitPriceMinor?.let { money(it, currency) }, lineTotal = money(lineTotalMinor, currency), categoryId = categoryId,
    provenance = provenance, rawText = rawText, uncertain = uncertain,
)

internal fun Invoice.toRow() = InvoiceRow(
    id, supplier, invoiceNumber, issueDate?.toEpochDay(), dueDate?.toEpochDay(), amountDue.minor, amountDue.currency.code, status.name,
    needsReview, categoryId, scannedAt.toEpochMilli(), rawText, sourceImageIds.toJson(),
)

internal fun InvoiceRow.toDomain() = Invoice(
    id = id, supplier = supplier, invoiceNumber = invoiceNumber, issueDate = issueDate?.day(), dueDate = dueDate?.day(),
    amountDue = money(amountDueMinor, currency), status = InvoiceStatus.valueOf(status), needsReview = needsReview, categoryId = categoryId,
    scannedAt = scannedAt.instant(), rawText = rawText, sourceImageIds = sourceImageIds.toStringList(),
)

internal fun PaymentRow.toDomain() = PaymentRecord(id, invoiceId, money(amountMinor, currency), paidDate.day(), expenseId, method)

internal fun CommandExecution.toRow() = CommandRow(executionKey, commandType, executedAt.toEpochMilli(), affectedExpenseId, summary, undone)
internal fun CommandRow.toDomain() = CommandExecution(executionKey, commandType, executedAt.instant(), affectedExpenseId, summary, undone)
