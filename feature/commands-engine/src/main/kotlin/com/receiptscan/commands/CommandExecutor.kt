package com.receiptscan.commands

import com.receiptscan.expenses.AddResult
import com.receiptscan.expenses.ExpenseEdit
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.ExpenseQueryResult
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.LedgerService
import com.receiptscan.expenses.LedgerStore
import com.receiptscan.expenses.NewExpense
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.expenses.UndoResult
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import com.receiptscan.time.DisplayDates
import com.receiptscan.time.LocalDateRange
import java.time.LocalDate

data class ReceiptHit(
    val receiptId: String,
    val merchant: String?,
    val date: LocalDate?,
    val total: Money?,
    val status: ReceiptStatus,
    /** The item line or text snippet that matched, for highlighting. */
    val snippet: String,
)

/** Receipt search used by "find the charger receipt". The app backs this with Room FTS. */
fun interface ReceiptSearch {
    suspend fun search(text: String, range: LocalDateRange?): List<ReceiptHit>
}

/** Simple search over the ledger store; adequate for tests and the sample host. */
class StoreReceiptSearch(private val store: LedgerStore) : ReceiptSearch {
    override suspend fun search(text: String, range: LocalDateRange?): List<ReceiptHit> {
        val words = text.lowercase().split(' ').filter { it.isNotBlank() }
        return store.receipts(ReceiptFilter()).filter { r -> range == null || (r.purchaseDate?.let { it in range } == true) }
            .mapNotNull { r ->
                val lines = listOfNotNull(r.merchant) + r.items.map { it.description } + r.rawText.lines()
                val hit = lines.firstOrNull { l -> words.all { l.lowercase().contains(it) } } ?: if (words.isEmpty()) r.merchant.orEmpty() else null
                hit?.let { ReceiptHit(r.id, r.merchant, r.purchaseDate, r.total, r.status, it) }
            }
    }
}

sealed interface CommandOutcome {
    data class Added(val expense: ExpenseEntry, val executionKey: String, val alreadyExisted: Boolean) : CommandOutcome {
        /** "Added PKR 300 · Fuel · Today" */
        fun confirmation(today: LocalDate): String =
            "Added ${MoneyFormat.formatShort(expense.amount)} · ${com.receiptscan.expenses.Categories.name(expense.categoryId)} · ${DisplayDates.relative(expense.effectiveDate, today)}"
    }
    data class QueryAnswer(val result: ExpenseQueryResult, val range: LocalDateRange, val label: String, val summarize: Boolean) : CommandOutcome {
        /** Scope reminder shown with every answer. */
        val scopeNote: String get() = "Recorded expenses only: confirmed receipts, voice and manual entries. Not your full bank spending."
    }
    data class ReceiptsFound(val hits: List<ReceiptHit>, val text: String, val label: String?) : CommandOutcome
    data class DraftUpdated(val draft: Command.AddExpense) : CommandOutcome
    data class Edited(val expense: ExpenseEntry) : CommandOutcome
    data class Undone(val expense: ExpenseEntry) : CommandOutcome
    data class Refused(val message: String) : CommandOutcome
    data class ReminderRequested(val recordId: String, val label: String, val date: LocalDate?) : CommandOutcome
}

/**
 * Runs validated commands through application use cases. Queries are read-only. Adds and edits run
 * in ledger transactions with the execution key, so a repeated execution writes nothing new.
 */
class CommandExecutor(
    private val ledger: LedgerService,
    private val receipts: ReceiptSearch,
) {
    suspend fun execute(command: Command, executionKey: String, context: CommandContext = CommandContext()): CommandOutcome = when (command) {
        is Command.AddExpense -> {
            val r = ledger.addExpense(
                NewExpense(command.amount, command.categoryId, command.date, command.source, command.merchant, command.note),
                executionKey,
                commandType = if (command.source == ExpenseSource.VOICE) "VoiceAddExpense" else "AddExpense",
            )
            CommandOutcome.Added(r.expense, executionKey, r is AddResult.AlreadyExecuted)
        }
        is Command.QueryExpenses -> {
            val filter = ExpenseFilter(
                range = command.range,
                categoryIds = command.categoryIds,
                sources = command.sources ?: ExpenseSource.entries.toSet(),
            )
            CommandOutcome.QueryAnswer(ledger.query(filter), command.range, command.periodLabel, command.summarize)
        }
        is Command.FindReceipts -> CommandOutcome.ReceiptsFound(receipts.search(command.text, command.range), command.text, command.periodLabel)
        is Command.EditTarget -> when (command.target) {
            EditScope.DRAFT -> {
                val d = context.draft ?: return CommandOutcome.Refused("The draft is no longer open.")
                CommandOutcome.DraftUpdated(
                    d.copy(
                        amount = command.amount ?: d.amount,
                        categoryId = command.categoryId ?: d.categoryId,
                        date = command.date ?: d.date,
                        currencyAssumed = if (command.amount != null) false else d.currencyAssumed,
                    ),
                )
            }
            EditScope.RECENT_ADD -> {
                val recent = context.recentAdd ?: return CommandOutcome.Refused("That expense is no longer on screen.")
                CommandOutcome.Edited(
                    ledger.editExpense(recent.expenseId, ExpenseEdit(amount = command.amount, categoryId = command.categoryId, effectiveDate = command.date), reason = "Edited by command"),
                )
            }
        }
        is Command.UndoRecent -> when (val r = ledger.undo(command.executionKey)) {
            is UndoResult.Undone -> CommandOutcome.Undone(r.expense)
            UndoResult.AlreadyUndone -> CommandOutcome.Refused("That was already undone.")
            UndoResult.NotFound -> CommandOutcome.Refused("Couldn't find that expense.")
            is UndoResult.NotUndoable -> CommandOutcome.Refused(r.reason)
        }
        is Command.CreateReminder -> CommandOutcome.ReminderRequested(command.recordId, command.recordLabel, command.date)
    }
}
