package com.receiptscan.commands

import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.Money
import com.receiptscan.time.DatePeriod
import com.receiptscan.time.LocalDateRange
import java.time.LocalDate

/** Where the text came from. Voice and typed commands share one parser. */
enum class InputMode { VOICE, TYPED }

/**
 * A validated command. These are domain types, never raw JSON routed to storage.
 * Amounts are exact [Money]; dates are resolved local dates.
 */
sealed interface Command {
    data class AddExpense(
        val amount: Money,
        val categoryId: String,
        val date: LocalDate,
        val note: String?,
        val merchant: String?,
        val source: ExpenseSource,
        /** True when the currency was not said and the user's default was used. Shown in the preview. */
        val currencyAssumed: Boolean,
        /** True when no date was said and today was used. */
        val dateAssumed: Boolean,
    ) : Command

    data class QueryExpenses(
        val range: LocalDateRange,
        val periodLabel: String,
        val categoryIds: Set<String>?,
        val merchant: String?,
        val sources: Set<ExpenseSource>?,
        /** "How much…" questions lead with the total; "show…" leads with the list. Both show records. */
        val summarize: Boolean,
    ) : Command

    data class FindReceipts(val text: String, val range: LocalDateRange?, val periodLabel: String?) : Command

    /** Edits the draft being reviewed, or a recent add the person can see. Never inferred broadly. */
    data class EditTarget(
        val amount: Money?,
        val categoryId: String?,
        val date: LocalDate?,
        val target: EditScope,
    ) : Command

    data class UndoRecent(val executionKey: String, val summary: String) : Command

    data class CreateReminder(val recordId: String, val recordLabel: String, val date: LocalDate?) : Command
}

enum class EditScope { DRAFT, RECENT_ADD }

/** One tappable answer to a clarification question. */
data class ClarifyOption(val label: String, val resolved: ParsedDraft)

/** Fields understood so far, before validation completes. */
data class ParsedDraft(
    val intent: Intent,
    val amount: Money? = null,
    val amountCandidates: List<Money> = emptyList(),
    val currency: CurrencySpec? = null,
    val currencyAssumed: Boolean = false,
    val categoryId: String? = null,
    val period: DatePeriod? = null,
    val date: LocalDate? = null,
    val dateAssumed: Boolean = false,
    val searchText: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    val summarize: Boolean = false,
    val unknownWords: List<String> = emptyList(),
    val amountFromWords: Boolean = false,
)

enum class Intent { ADD_EXPENSE, QUERY_EXPENSES, FIND_RECEIPTS, EDIT, UNDO, REMIND, UNKNOWN }

sealed interface ParseResult {
    val normalized: String

    data class Ready(override val normalized: String, val command: Command, val draft: ParsedDraft) : ParseResult

    /** One short question with concrete choices. Nothing is saved until it is answered. */
    data class NeedsClarification(
        override val normalized: String,
        val question: String,
        val field: String,
        val options: List<ClarifyOption>,
        val draft: ParsedDraft,
    ) : ParseResult

    data class Unsupported(override val normalized: String, val message: String) : ParseResult
}

/** What the person can currently see; follow-ups like "change it" bind only to this. */
data class CommandContext(
    val draft: Command.AddExpense? = null,
    val recentAdd: RecentAdd? = null,
    val selectedRecord: SelectedRecord? = null,
)

data class RecentAdd(val executionKey: String, val expenseId: String, val summary: String, val amount: Money)

data class SelectedRecord(val id: String, val label: String, val suggestedDate: LocalDate?)
