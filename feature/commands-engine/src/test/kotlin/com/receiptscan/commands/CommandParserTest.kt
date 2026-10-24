package com.receiptscan.commands

import com.google.common.truth.Truth.assertThat
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.IdGenerator
import com.receiptscan.expenses.InMemoryLedgerStore
import com.receiptscan.expenses.LedgerService
import com.receiptscan.money.Currencies
import com.receiptscan.money.Money
import com.receiptscan.time.LocalDateRange
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CommandParserTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone).toInstant(), zone)
    private val parser = CommandParser(clock, { zone })
    private val today = LocalDate.of(2026, 10, 24)
    private fun pkr(v: String) = Money.fromDecimal(BigDecimal(v), Currencies.PKR)

    private fun add(text: String, mode: InputMode = InputMode.VOICE): Command.AddExpense {
        val r = parser.parse(text, mode)
        check(r is ParseResult.Ready) { "Expected Ready for '$text' but was $r" }
        return r.command as Command.AddExpense
    }

    private fun query(text: String): Command.QueryExpenses {
        val r = parser.parse(text, InputMode.VOICE)
        check(r is ParseResult.Ready) { "Expected Ready for '$text' but was $r" }
        return r.command as Command.QueryExpenses
    }

    @Test fun `required 30 day query resolves 25 September to 24 October`() {
        val q = query("Bring me my last 30 days expenses.")
        assertThat(q.range).isEqualTo(LocalDateRange(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 25)))
        assertThat(q.categoryIds).isNull()
        assertThat(q.periodLabel).isEqualTo("Last 30 days")
    }

    @Test fun `required add command`() {
        val a = add("Add 300 rupees fuel expense for today")
        assertThat(a.amount).isEqualTo(pkr("300"))
        assertThat(a.amount.minor).isEqualTo(30_000)
        assertThat(a.categoryId).isEqualTo("fuel")
        assertThat(a.date).isEqualTo(today)
        assertThat(a.source).isEqualTo(ExpenseSource.VOICE)
        assertThat(a.currencyAssumed).isFalse()
        assertThat(a.dateAssumed).isFalse()
    }

    @Test fun `grouped amount and yesterday`() {
        val a = add("Add 1,500 rupees groceries for yesterday", InputMode.TYPED)
        assertThat(a.amount).isEqualTo(pkr("1500"))
        assertThat(a.date).isEqualTo(LocalDate.of(2026, 10, 23))
        assertThat(a.source).isEqualTo(ExpenseSource.MANUAL)
    }

    @Test fun `roman urdu and urdu script map to the same command`() {
        val english = add("Add 300 rupees fuel expense for today")
        val roman = add("Aaj fuel ka teen sau rupay expense add kar do")
        val urdu = add("آج ایندھن کا تین سو روپے کا خرچ شامل کر دو")
        listOf(roman, urdu).forEach {
            assertThat(it.amount).isEqualTo(english.amount)
            assertThat(it.categoryId).isEqualTo(english.categoryId)
            assertThat(it.date).isEqualTo(english.date)
        }
    }

    @Test fun `roman urdu 30 day query`() {
        val q = query("Pichlay tees din ke kharchay dikhao")
        assertThat(q.range).isEqualTo(LocalDateRange(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 25)))
    }

    @Test fun `category month queries and summaries`() {
        val q = query("Show my fuel expenses this month")
        assertThat(q.categoryIds).containsExactly("fuel")
        assertThat(q.range.startInclusive).isEqualTo(LocalDate.of(2026, 10, 1))
        val s = query("How much did I spend on groceries last month?")
        assertThat(s.summarize).isTrue()
        assertThat(s.categoryIds).containsExactly("groceries")
        assertThat(s.range).isEqualTo(LocalDateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
    }

    @Test fun `last month and last 30 days differ`() {
        assertThat(query("expenses last month").range).isNotEqualTo(query("expenses last 30 days").range)
    }

    @Test fun `find receipts from last week`() {
        val r = parser.parse("Find the charger receipt from last week", InputMode.VOICE) as ParseResult.Ready
        val f = r.command as Command.FindReceipts
        assertThat(f.text).isEqualTo("charger")
        assertThat(f.range).isEqualTo(LocalDateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 19)))
    }

    @Test fun `ambiguous amount asks a question instead of saving`() {
        val r = parser.parse("add 300 and 3000 fuel today", InputMode.VOICE)
        assertThat(r).isInstanceOf(ParseResult.NeedsClarification::class.java)
        r as ParseResult.NeedsClarification
        assertThat(r.field).isEqualTo("amount")
        assertThat(r.options.map { it.label }).containsExactly("PKR 300.00", "PKR 3,000.00")
    }

    @Test fun `kal is ambiguous unless past tense says yesterday`() {
        val r = parser.parse("kal fuel 500 rupay add karo", InputMode.VOICE)
        assertThat(r).isInstanceOf(ParseResult.NeedsClarification::class.java)
        r as ParseResult.NeedsClarification
        assertThat(r.options.map { it.label }).containsExactly("Yesterday, 23 Oct 2026", "Tomorrow, 25 Oct 2026")
        val resolved = parser.resolveClarification(r, r.options.first().resolved, InputMode.VOICE) as ParseResult.Ready
        assertThat((resolved.command as Command.AddExpense).date).isEqualTo(LocalDate.of(2026, 10, 23))
        val past = add("kal fuel par 500 rupay kharch kiye")
        assertThat(past.date).isEqualTo(LocalDate.of(2026, 10, 23))
    }

    @Test fun `missing category or amount asks`() {
        assertThat((parser.parse("add 300 rupees today", InputMode.VOICE) as ParseResult.NeedsClarification).field).isEqualTo("category")
        assertThat((parser.parse("add fuel expense today", InputMode.VOICE) as ParseResult.NeedsClarification).field).isEqualTo("amount")
    }

    @Test fun `number word forms`() {
        assertThat(add("add one thousand five hundred rupees groceries today").amount).isEqualTo(pkr("1500"))
        assertThat(add("dedh hazaar rupay ka rashan aaj add karo").amount).isEqualTo(pkr("1500"))
        assertThat(add("add 2 lakh rupees travel today").amount).isEqualTo(pkr("200000"))
        assertThat(add("add 1.5k fuel today").amount).isEqualTo(pkr("1500"))
        assertThat(add("add three hundred fifty rupees fuel today").amount).isEqualTo(pkr("350"))
        assertThat(add("saadhe teen sau ka petrol aaj add karo").amount).isEqualTo(pkr("350"))
        // "kar do" must not be read as the number two.
        assertThat(add("aaj 300 rupay fuel add kar do").amount).isEqualTo(pkr("300"))
    }

    @Test fun `default currency is used and flagged when not said`() {
        val a = add("add 300 fuel today")
        assertThat(a.currencyAssumed).isTrue()
        assertThat(a.amount.currency).isEqualTo(Currencies.PKR)
    }

    @Test fun `change it binds only to the visible draft or recent add`() {
        val none = parser.parse("Change it to 350", InputMode.VOICE)
        assertThat(none).isInstanceOf(ParseResult.Unsupported::class.java)
        val draft = add("Add 300 rupees fuel expense for today")
        val r = parser.parse("Change it to 350", InputMode.VOICE, CommandContext(draft = draft)) as ParseResult.Ready
        val e = r.command as Command.EditTarget
        assertThat(e.target).isEqualTo(EditScope.DRAFT)
        assertThat(e.amount).isEqualTo(pkr("350"))
    }

    @Test fun `undo needs a recent command context`() {
        assertThat(parser.parse("Undo that expense", InputMode.VOICE)).isInstanceOf(ParseResult.Unsupported::class.java)
        val ctx = CommandContext(recentAdd = RecentAdd("k1", "e1", "PKR 300 · Fuel", pkr("300")))
        val r = parser.parse("Undo that expense", InputMode.VOICE, ctx) as ParseResult.Ready
        assertThat((r.command as Command.UndoRecent).executionKey).isEqualTo("k1")
    }

    @Test fun `unsupported commands are not guessed`() {
        assertThat(parser.parse("what's the weather tomorrow", InputMode.VOICE)).isInstanceOf(ParseResult.Unsupported::class.java)
        assertThat(parser.parse("delete everything", InputMode.VOICE)).isInstanceOf(ParseResult.Unsupported::class.java)
    }

    @Test fun `quick add rules`() {
        val text = "Add 300 rupees fuel expense for today"
        assertThat(QuickAddPolicy.evaluate(parser.parse(text, InputMode.VOICE), text, false).eligible).isTrue()
        assertThat(QuickAddPolicy.evaluate(parser.parse(text, InputMode.VOICE), text, true).eligible).isFalse()
        val noCurrency = "add 300 fuel today"
        assertThat(QuickAddPolicy.evaluate(parser.parse(noCurrency, InputMode.VOICE), noCurrency, false).eligible).isFalse()
        val noDate = "add 300 rupees fuel"
        assertThat(QuickAddPolicy.evaluate(parser.parse(noDate, InputMode.VOICE), noDate, false).eligible).isFalse()
    }

    @Test fun `explicit dates`() {
        assertThat(add("add 500 rupees dining on 12 october").date).isEqualTo(LocalDate.of(2026, 10, 12))
        val r = parser.parse("add 500 rupees dining on 04/05/2026", InputMode.TYPED)
        assertThat(r).isInstanceOf(ParseResult.NeedsClarification::class.java)
    }

    @Test fun `in september query`() {
        assertThat(query("how much did i spend in september").range).isEqualTo(LocalDateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1)))
    }
}

class CommandExecutorTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone).toInstant(), zone)
    private val store = InMemoryLedgerStore()
    private var n = 0
    private val ledger = LedgerService(store, clock, { zone }, IdGenerator { "e${++n}" })
    private val parser = CommandParser(clock, { zone })
    private val executor = CommandExecutor(ledger, StoreReceiptSearch(store))

    @Test fun `voice add then query then undo refreshes totals`() = runTest {
        val add = (parser.parse("Add 300 rupees fuel expense for today", InputMode.VOICE) as ParseResult.Ready).command
        val added = executor.execute(add, "exec-1") as CommandOutcome.Added
        assertThat(added.confirmation(LocalDate.of(2026, 10, 24))).isEqualTo("Added PKR 300 · Fuel · Today")
        // Duplicate final callback with the same key.
        assertThat((executor.execute(add, "exec-1") as CommandOutcome.Added).alreadyExisted).isTrue()

        val q = (parser.parse("Bring me my last 30 days expenses", InputMode.VOICE) as ParseResult.Ready).command
        val before = store.expenses(ExpenseFilter(states = com.receiptscan.expenses.ExpenseState.entries.toSet()))
        val answer = executor.execute(q, "exec-q") as CommandOutcome.QueryAnswer
        assertThat(answer.result.count).isEqualTo(1)
        assertThat(answer.result.totals.single().total.minor).isEqualTo(30_000)
        // Read-only: a query writes nothing.
        assertThat(store.expenses(ExpenseFilter(states = com.receiptscan.expenses.ExpenseState.entries.toSet()))).isEqualTo(before)
        assertThat(store.recentCommands(10).map { it.executionKey }).containsExactly("exec-1")

        val ctx = CommandContext(recentAdd = RecentAdd("exec-1", added.expense.id, "x", added.expense.amount))
        val change = (parser.parse("change it to 350", InputMode.VOICE, ctx) as ParseResult.Ready).command
        val edited = executor.execute(change, "exec-2", ctx) as CommandOutcome.Edited
        assertThat(edited.expense.amount.minor).isEqualTo(35_000)

        val undo = (parser.parse("undo that", InputMode.VOICE, ctx) as ParseResult.Ready).command
        assertThat(executor.execute(undo, "exec-3", ctx)).isInstanceOf(CommandOutcome.Undone::class.java)
        val after = executor.execute(q, "exec-q2") as CommandOutcome.QueryAnswer
        assertThat(after.result.isEmpty).isTrue()
    }
}
