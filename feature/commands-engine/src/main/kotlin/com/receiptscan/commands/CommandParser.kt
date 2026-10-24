package com.receiptscan.commands

import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.money.AmountParser
import com.receiptscan.money.Currencies
import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import com.receiptscan.time.DatePeriod
import com.receiptscan.time.DateResolver
import com.receiptscan.time.DisplayDates
import com.receiptscan.time.PrintedDateParser
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Deterministic, local intent and slot parser shared by voice and typed input.
 *
 * Layers (brief 8.3): normalise text and numerals → detect intent from patterns and aliases →
 * extract slots → resolve dates/categories/currency in domain code → ask one short question when a
 * required field is missing or ambiguous → produce a typed [Command]. No language model is needed
 * for the supported commands, and nothing here writes data.
 */
class CommandParser(
    private val clock: Clock,
    private val zone: () -> ZoneId,
    private val defaultCurrency: () -> CurrencySpec = { Currencies.PKR },
) {
    private val resolver get() = DateResolver(clock, zone())
    private fun today() = resolver.today()

    private val currencyWords = setOf("rs", "pkr", "rupee", "rupees", "rupay", "rupaye", "rupees", "روپے", "روپیہ", "روپیے", "usd", "dollar", "dollars", "eur", "euro", "euros", "gbp", "pound", "pounds", "aed", "dirham", "dirhams", "sar", "riyal", "riyals", "inr")

    fun normalize(text: String): String {
        var s = java.text.Normalizer.normalize(AmountParser.normalizeDigits(text), java.text.Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        s = s.replace(Regex("[\\u064B-\\u065F\\u0670\\u06D4]"), " ") // Urdu diacritics and full stop
        s = s.replace(Regex("(?<=\\d),(?=\\d)"), ",") // keep digit grouping
        s = s.replace(Regex("rs\\.(?=\\s|\\d|$)"), "rs ")
        s = s.replace(Regex("[\"“”‘’'!?؟،;:()\\[\\]]"), " ")
        s = s.replace(Regex("(?<!\\d)[.,]|[.,](?!\\d)"), " ")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private val tokenRx = Regex("""\d{1,3}(?:,\d{2,3})+(?:\.\d+)?k?|\d+(?:\.\d+)?k?|[\p{L}\p{M}]+(?:-[\p{L}\p{M}]+)*""")

    fun parse(text: String, mode: InputMode, context: CommandContext = CommandContext()): ParseResult {
        val normalized = normalize(text)
        if (normalized.isBlank()) return ParseResult.Unsupported(normalized, "Say or type a command, for example “Add 300 rupees fuel expense for today”.")

        // Explicit dates ("12 October", "12/10/2026") are found first and blanked out so their numbers are not amounts.
        var working = normalized
        val explicitDates = PrintedDateParser.findAll(working, today()).filter { !it.monthOnly }
        explicitDates.forEach { d -> working = working.replaceFirst(d.raw.lowercase(Locale.ROOT), " ") }
        val tokens = tokenRx.findAll(working).map { it.value }.toList()
        val consumed = BooleanArray(tokens.size)
        val tokenSet = tokens.toSet()

        // ---- period ----
        var period: DatePeriod? = explicitDates.firstOrNull()?.let { DatePeriod.OnDate(it.date) }
        var periodLabel: String? = explicitDates.firstOrNull()?.let { DisplayDates.format(it.date) }
        var kalAmbiguity: Pair<LocalDate, LocalDate>? = null
        val dateAmbiguity: List<LocalDate> = explicitDates.firstOrNull()?.alternatives.orEmpty()
        val numbersAll = NumberWords.findAll(tokens)

        fun consume(range: IntRange) = range.forEach { if (it in tokens.indices) consumed[it] = true }

        for (i in tokens.indices) {
            val t = tokens[i]
            when {
                t in Lexicon.last || t in Lexicon.thisWords -> {
                    val isLast = t in Lexicon.last
                    val num = numbersAll.firstOrNull { it.startToken == i + 1 }
                    val unitIdx = num?.endTokenExclusive ?: (i + 1)
                    val unit = tokens.getOrNull(unitIdx)
                    when {
                        unit in Lexicon.dayWords && num != null && isLast -> {
                            val n = num.value.toInt()
                            if (n in 1..3660) { period = DatePeriod.LastNDays(n); periodLabel = "Last $n days"; consume(i..unitIdx) }
                        }
                        unit in Lexicon.weekWords && num != null && isLast -> {
                            val n = num.value.toInt()
                            if (n in 1..520) { period = DatePeriod.LastNDays(7 * n); periodLabel = "Last $n weeks"; consume(i..unitIdx) }
                        }
                        unit in Lexicon.weekWords && num == null -> {
                            period = if (isLast) DatePeriod.LastWeek else DatePeriod.ThisWeek
                            periodLabel = if (isLast) "Last week" else "This week"; consume(i..unitIdx)
                        }
                        unit in Lexicon.monthWords && num == null -> {
                            period = if (isLast) DatePeriod.LastMonth else DatePeriod.ThisMonth
                            periodLabel = if (isLast) "Last month" else "This month"; consume(i..unitIdx)
                        }
                        unit in Lexicon.yearWords && num == null && !isLast -> {
                            period = DatePeriod.ThisYear; periodLabel = "This year"; consume(i..unitIdx)
                        }
                    }
                }
                t in Lexicon.today -> { period = DatePeriod.Today; periodLabel = "Today"; consume(i..i) }
                t in Lexicon.yesterday -> { period = DatePeriod.Yesterday; periodLabel = "Yesterday"; consume(i..i) }
                t in Lexicon.kal || t in Lexicon.parson -> {
                    val back = if (t in Lexicon.kal) 1L else 2L
                    val past = today().minusDays(back)
                    val future = today().plusDays(back)
                    consume(i..i)
                    val pastTense = tokens.any { it in Lexicon.pastMarkers }
                    // "کل" also means "total" in Urdu: treat it as a date only when it is not followed by an amount noun.
                    if (pastTense) { period = DatePeriod.OnDate(past); periodLabel = DisplayDates.relative(past, today()) } else kalAmbiguity = past to future
                }
            }
        }
        // "30 days ke kharchay" / "tees din" without "last"
        if (period == null) {
            numbersAll.forEach { n ->
                if (tokens.getOrNull(n.endTokenExclusive) in Lexicon.dayWords && !consumed[n.startToken]) {
                    val days = n.value.toInt()
                    if (days in 1..3660) { period = DatePeriod.LastNDays(days); periodLabel = "Last $days days"; consume(n.startToken..n.endTokenExclusive) }
                }
            }
        }
        // Month name alone: "in September" → the most recent September.
        if (period == null) {
            tokens.forEachIndexed { i, t ->
                val m = monthFromWord(t) ?: return@forEachIndexed
                val today = today()
                val year = if (m.value > today.monthValue) today.year - 1 else today.year
                period = DatePeriod.Month(year, m.value)
                periodLabel = "${m.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $year"
                consume(i..i)
            }
        }

        // ---- numbers / currency / category ----
        val amounts = numbersAll.filter { n -> (n.startToken until n.endTokenExclusive).none { consumed[it] } }
        amounts.forEach { consume(it.startToken until it.endTokenExclusive) }
        var currency: CurrencySpec? = null
        tokens.forEachIndexed { i, t ->
            if (t in currencyWords) {
                currency = currency ?: Currencies.fromAlias(t, defaultCurrency())
                consume(i..i)
            }
        }
        val categories = linkedSetOf<String>()
        Lexicon.categoryAliases.entries.filter { ' ' in it.key }.forEach { (phrase, id) ->
            val parts = phrase.split(' ')
            for (i in 0..tokens.size - parts.size) {
                if (tokens.subList(i, i + parts.size) == parts) { categories += id; consume(i until i + parts.size) }
            }
        }
        tokens.forEachIndexed { i, t ->
            if (!consumed[i]) Lexicon.categoryAliases[t]?.let { categories += it; consume(i..i) }
        }

        val has = { set: Set<String> -> tokenSet.any { it in set } }
        val summarize = has(Lexicon.summaryWords)
        val intent = when {
            has(Lexicon.undoWords) -> Intent.UNDO
            has(Lexicon.remindWords) -> Intent.REMIND
            has(Lexicon.editWords) && (has(Lexicon.pronounsIt) || tokens.contains("to") || context.draft != null) && !has(Lexicon.queryVerbs) -> Intent.EDIT
            has(Lexicon.findVerbs) || (has(Lexicon.receiptNouns) && !has(Lexicon.expenseNouns) && !has(Lexicon.addVerbs)) -> Intent.FIND_RECEIPTS
            has(Lexicon.addVerbs) && !has(Lexicon.queryVerbs) && !(summarize && amounts.isEmpty()) -> Intent.ADD_EXPENSE
            has(Lexicon.queryVerbs) || summarize || (has(Lexicon.expenseNouns) && amounts.isEmpty()) -> Intent.QUERY_EXPENSES
            amounts.size == 1 && categories.isNotEmpty() -> Intent.ADD_EXPENSE
            else -> Intent.UNKNOWN
        }
        val intentWords = Lexicon.addVerbs + Lexicon.queryVerbs + Lexicon.summaryWords + Lexicon.expenseNouns + Lexicon.findVerbs +
            Lexicon.receiptNouns + Lexicon.undoWords + Lexicon.editWords + Lexicon.remindWords + Lexicon.pronounsIt
        val unknown = tokens.filterIndexed { i, t -> !consumed[i] && t !in intentWords && t !in Lexicon.fillers && t != "expense" }

        val cur = currency ?: defaultCurrency()
        val moneyCandidates = amounts.mapNotNull { m -> toMoney(m.value, cur) }.distinct()
        val draft = ParsedDraft(
            intent = intent,
            amount = moneyCandidates.singleOrNull(),
            amountCandidates = moneyCandidates,
            currency = cur,
            currencyAssumed = currency == null,
            categoryId = categories.singleOrNull(),
            period = period,
            summarize = summarize,
            unknownWords = unknown,
            amountFromWords = amounts.any { it.fromWords },
        )

        return when (intent) {
            Intent.ADD_EXPENSE -> resolveAdd(normalized, draft, categories, kalAmbiguity, dateAmbiguity, mode, amounts.size)
            Intent.QUERY_EXPENSES -> {
                val p = period ?: DatePeriod.ThisMonth
                val range = resolver.resolve(p)
                if (kalAmbiguity != null && period == null) {
                    // A query about "kal" is about the past.
                    val d = kalAmbiguity.first
                    val r = resolver.resolve(DatePeriod.OnDate(d))
                    return ParseResult.Ready(normalized, Command.QueryExpenses(r, DisplayDates.relative(d, today()), categories.ifEmpty { null }, null, sourcesFrom(tokens), summarize), draft)
                }
                ParseResult.Ready(
                    normalized,
                    Command.QueryExpenses(range, periodLabel ?: "This month", categories.ifEmpty { null }, null, sourcesFrom(tokens), summarize),
                    draft.copy(period = p),
                )
            }
            Intent.FIND_RECEIPTS -> {
                val itemWords = tokens.filterIndexed { i, t ->
                    (!consumed[i] || Lexicon.categoryAliases.containsKey(t)) && t !in intentWords && t !in Lexicon.fillers &&
                        t !in currencyWords && Lexicon.categoryAliases[t] == null || (Lexicon.categoryAliases[t] != null && t !in setOf("bill", "bills"))
                }.filter { it !in Lexicon.findVerbs && it !in Lexicon.receiptNouns && it !in Lexicon.fillers && it !in Lexicon.last && it !in Lexicon.thisWords && it !in Lexicon.weekWords && it !in Lexicon.monthWords && it !in Lexicon.dayWords && it !in Lexicon.today }
                val text = itemWords.joinToString(" ").trim()
                val range = period?.let { resolver.resolve(it) }
                if (text.isEmpty() && range == null) {
                    ParseResult.Unsupported(normalized, "Say what to look for, for example “find the charger receipt from last week”.")
                } else {
                    ParseResult.Ready(normalized, Command.FindReceipts(text, range, periodLabel), draft.copy(searchText = text))
                }
            }
            Intent.EDIT -> resolveEdit(normalized, draft, categories, context)
            Intent.UNDO -> {
                val recent = context.recentAdd
                    ?: return ParseResult.Unsupported(normalized, "There's no recent expense here to undo.")
                ParseResult.Ready(normalized, Command.UndoRecent(recent.executionKey, recent.summary), draft)
            }
            Intent.REMIND -> {
                val rec = context.selectedRecord
                    ?: return ParseResult.Unsupported(normalized, "Open a bill, warranty or expiry record first, then ask for a reminder there.")
                val date = (period as? DatePeriod.OnDate)?.date ?: rec.suggestedDate
                ParseResult.Ready(normalized, Command.CreateReminder(rec.id, rec.label, date), draft)
            }
            Intent.UNKNOWN -> ParseResult.Unsupported(
                normalized,
                "I can add an expense, show expenses for a period, find a receipt, or undo a recent add. I didn't change anything.",
            )
        }
    }

    private fun sourcesFrom(tokens: List<String>): Set<ExpenseSource>? {
        val s = buildSet {
            if ("voice" in tokens) add(ExpenseSource.VOICE)
            if ("manual" in tokens || "typed" in tokens) add(ExpenseSource.MANUAL)
            if ("scanned" in tokens) add(ExpenseSource.RECEIPT)
        }
        return s.ifEmpty { null }
    }

    private fun toMoney(value: BigDecimal, currency: CurrencySpec): Money? =
        runCatching { Money.fromDecimal(value, currency) }.getOrNull()?.takeIf { it.minor > 0 && it.toDecimal() <= Money.MAX_MAJOR }

    private fun resolveAdd(
        normalized: String,
        draft: ParsedDraft,
        categories: Set<String>,
        kal: Pair<LocalDate, LocalDate>?,
        dateAlternatives: List<LocalDate>,
        mode: InputMode,
        rawAmountCount: Int,
    ): ParseResult {
        val source = if (mode == InputMode.VOICE) ExpenseSource.VOICE else ExpenseSource.MANUAL
        // Amount
        if (draft.amountCandidates.isEmpty()) {
            return if (rawAmountCount > 0) {
                ParseResult.Unsupported(normalized, "That amount isn't valid for ${draft.currency?.code}. Use up to ${draft.currency?.fractionDigits} decimal places.")
            } else {
                ParseResult.NeedsClarification(normalized, "How much was it?", "amount", emptyList(), draft)
            }
        }
        if (draft.amountCandidates.size > 1) {
            return ParseResult.NeedsClarification(
                normalized, "Which amount?", "amount",
                draft.amountCandidates.map { ClarifyOption(MoneyFormat.format(it), draft.copy(amount = it, amountCandidates = listOf(it))) }, draft,
            )
        }
        // Category
        if (categories.size > 1) {
            return ParseResult.NeedsClarification(
                normalized, "Which category?", "category",
                categories.map { ClarifyOption(Categories.name(it), draft.copy(categoryId = it)) }, draft,
            )
        }
        if (categories.isEmpty()) {
            val common = listOf("fuel", "groceries", "dining", "transport", "health", "shopping", Categories.UNCATEGORIZED)
            return ParseResult.NeedsClarification(
                normalized, "Which category?", "category",
                common.map { ClarifyOption(Categories.name(it), draft.copy(categoryId = it)) }, draft,
            )
        }
        // Date
        val today = today()
        val date: LocalDate
        var assumed = false
        when {
            kal != null && draft.period == null -> return ParseResult.NeedsClarification(
                normalized, "Which day did you mean?", "date",
                listOf(
                    ClarifyOption("Yesterday, ${DisplayDates.format(kal.first)}", draft.copy(period = DatePeriod.OnDate(kal.first), date = kal.first)),
                    ClarifyOption("Tomorrow, ${DisplayDates.format(kal.second)}", draft.copy(period = DatePeriod.OnDate(kal.second), date = kal.second)),
                ),
                draft,
            )
            dateAlternatives.isNotEmpty() -> {
                val first = (draft.period as DatePeriod.OnDate).date
                return ParseResult.NeedsClarification(
                    normalized, "Which date?", "date",
                    (listOf(first) + dateAlternatives).map { ClarifyOption(DisplayDates.format(it), draft.copy(period = DatePeriod.OnDate(it), date = it)) },
                    draft,
                )
            }
            draft.period == null -> { date = today; assumed = true }
            draft.period is DatePeriod.Today || draft.period is DatePeriod.Yesterday || draft.period is DatePeriod.OnDate ->
                date = resolver.resolve(draft.period).startInclusive
            else -> return ParseResult.NeedsClarification(
                normalized, "Which day?", "date",
                listOf(
                    ClarifyOption("Today, ${DisplayDates.format(today)}", draft.copy(period = DatePeriod.Today, date = today)),
                    ClarifyOption("Yesterday, ${DisplayDates.format(today.minusDays(1))}", draft.copy(period = DatePeriod.Yesterday, date = today.minusDays(1))),
                ),
                draft,
            )
        }
        if (date.isAfter(today.plusDays(1))) {
            return ParseResult.Unsupported(normalized, "That date is in the future. Expenses can be added up to tomorrow.")
        }
        val cmd = Command.AddExpense(draft.amount!!, categories.single(), date, null, null, source, draft.currencyAssumed, assumed)
        return ParseResult.Ready(normalized, cmd, draft.copy(date = date, dateAssumed = assumed))
    }

    /** Applies a clarification answer and re-validates. */
    fun resolveClarification(original: ParseResult.NeedsClarification, choice: ParsedDraft, mode: InputMode): ParseResult {
        val d = choice
        if (d.intent != Intent.ADD_EXPENSE) return ParseResult.Unsupported(original.normalized, "Please say the command again.")
        return resolveAdd(
            original.normalized,
            d,
            setOfNotNull(d.categoryId),
            null,
            emptyList(),
            mode,
            d.amountCandidates.size,
        )
    }

    private fun resolveEdit(normalized: String, draft: ParsedDraft, categories: Set<String>, context: CommandContext): ParseResult {
        val scope = when {
            context.draft != null -> EditScope.DRAFT
            context.recentAdd != null -> EditScope.RECENT_ADD
            else -> return ParseResult.Unsupported(normalized, "There's no expense on screen to change. Open it first, or say the whole expense again.")
        }
        val baseCurrency = context.draft?.amount?.currency ?: context.recentAdd!!.amount.currency
        val amount = draft.amountCandidates.singleOrNull()?.let { if (draft.currencyAssumed) Money.fromDecimal(it.toDecimal(), baseCurrency, java.math.RoundingMode.UNNECESSARY) else it }
        if (draft.amountCandidates.size > 1) {
            return ParseResult.NeedsClarification(normalized, "Which amount?", "amount", emptyList(), draft)
        }
        val date = draft.period?.let { p ->
            if (p is DatePeriod.Today || p is DatePeriod.Yesterday || p is DatePeriod.OnDate) resolver.resolve(p).startInclusive else null
        }
        val category = categories.singleOrNull()
        if (amount == null && category == null && date == null) {
            return ParseResult.NeedsClarification(normalized, "What should it change to?", "edit", emptyList(), draft)
        }
        if (amount != null && amount.currency != baseCurrency) {
            return ParseResult.Unsupported(normalized, "Currency can't change on an existing expense.")
        }
        return ParseResult.Ready(normalized, Command.EditTarget(amount, category, date, scope), draft)
    }

    private fun monthFromWord(t: String): Month? = when (t) {
        "january", "jan" -> Month.JANUARY; "february", "feb" -> Month.FEBRUARY; "march" -> Month.MARCH; "april", "apr" -> Month.APRIL
        "june" -> Month.JUNE; "july" -> Month.JULY; "august", "aug" -> Month.AUGUST; "september", "sept" -> Month.SEPTEMBER
        "october", "oct" -> Month.OCTOBER; "november", "nov" -> Month.NOVEMBER; "december", "dec" -> Month.DECEMBER
        else -> null
    }
}

/**
 * Quick add (optional setting) saves without the review card only when every rule holds.
 * These rules are deterministic and documented in docs/VOICE_AND_COMMANDS.md.
 */
object QuickAddPolicy {
    data class Decision(val eligible: Boolean, val reasons: List<String>)

    fun evaluate(result: ParseResult, originalText: String, fromSuggestionChip: Boolean): Decision {
        val reasons = mutableListOf<String>()
        if (fromSuggestionChip) reasons += "Example suggestions always open the review card"
        val ready = result as? ParseResult.Ready
        val cmd = ready?.command as? Command.AddExpense
        if (cmd == null) return Decision(false, reasons + "Not a complete add command")
        val d = ready.draft
        val tokens = originalText.lowercase().split(Regex("\\s+"))
        if (tokens.none { it in Lexicon.addVerbs }) reasons += "No explicit add word"
        if (d.amountCandidates.size != 1) reasons += "Amount not unique"
        if (cmd.currencyAssumed) reasons += "Currency not said"
        if (cmd.dateAssumed) reasons += "Date not said"
        if (d.unknownWords.isNotEmpty()) reasons += "Some words were not understood: ${d.unknownWords.joinToString(", ")}"
        if (cmd.categoryId == Categories.UNCATEGORIZED) reasons += "No category"
        return Decision(reasons.isEmpty(), reasons)
    }
}
