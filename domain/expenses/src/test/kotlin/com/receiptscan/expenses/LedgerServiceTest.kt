package com.receiptscan.expenses

import com.google.common.truth.Truth.assertThat
import com.receiptscan.money.Currencies
import com.receiptscan.money.Money
import com.receiptscan.time.DatePeriod
import com.receiptscan.time.DateResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

class LedgerServiceTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, zone).toInstant(), zone)
    private val counter = AtomicInteger()
    private val store = InMemoryLedgerStore()
    private val ledger = LedgerService(store, clock, { zone }, IdGenerator { "id-${counter.incrementAndGet()}" })
    private val resolver = DateResolver(clock, zone)
    private val pkr = Currencies.PKR
    private val today = LocalDate.of(2026, 10, 24)

    private fun pkr(major: String) = Money.fromDecimal(BigDecimal(major), pkr)

    private fun receipt(id: String, merchant: String = "FreshMart", total: String = "3800", date: LocalDate = LocalDate.of(2026, 10, 23), items: List<ReceiptItem> = emptyList()) = Receipt(
        id = id, status = ReceiptStatus.NEEDS_REVIEW, merchant = merchant, purchaseDate = date, purchaseTime = null,
        currency = pkr, subtotal = null, tax = null, discount = null, total = pkr(total), categoryId = "groceries",
        scannedAt = clock.instant(), rawText = "", items = items,
    )

    private suspend fun last30() = ledger.query(ExpenseFilter(range = resolver.resolve(DatePeriod.LastNDays(30))))

    @Test fun `voice add is idempotent per execution key`() = runTest {
        val new = NewExpense(pkr("300"), "fuel", today, ExpenseSource.VOICE)
        val first = ledger.addExpense(new, "key-1")
        val again = ledger.addExpense(new, "key-1")
        assertThat(first).isInstanceOf(AddResult.Added::class.java)
        assertThat(again).isInstanceOf(AddResult.AlreadyExecuted::class.java)
        assertThat(again.expense.id).isEqualTo(first.expense.id)
        assertThat(last30().count).isEqualTo(1)
    }

    @Test fun `concurrent duplicate final callbacks create one expense`() = runTest {
        val new = NewExpense(pkr("300"), "fuel", today, ExpenseSource.VOICE)
        val results = List(10) { async { ledger.addExpense(new, "same-key") } }.awaitAll()
        assertThat(results.map { it.expense.id }.toSet()).hasSize(1)
        assertThat(results.count { it is AddResult.Added }).isEqualTo(1)
        assertThat(last30().totals.single().total).isEqualTo(pkr("300"))
    }

    @Test fun `drafts unpaid bills and voided entries are excluded from totals`() = runTest {
        ledger.saveReceiptDraft(receipt("techhub", "TechHub", "6500"))
        ledger.saveInvoice(Invoice("bill", "City Power", "INV-1", today, today.plusDays(6), pkr("8240"), InvoiceStatus.UNPAID, false, "utilities", clock.instant()))
        val added = ledger.addExpense(NewExpense(pkr("500"), "dining", today, ExpenseSource.MANUAL), "k").expense
        ledger.voidExpense(added.id, "test")
        ledger.confirmReceipt(receipt("fm"))
        val result = last30()
        assertThat(result.count).isEqualTo(1)
        assertThat(result.totals.single().total).isEqualTo(pkr("3800"))
    }

    @Test fun `handover sample October total is 5480 from two receipts`() = runTest {
        ledger.confirmReceipt(receipt("fm", "FreshMart", "3800"))
        ledger.confirmReceipt(receipt("cafe", "Corner Café", "1680").copy(categoryId = "dining"))
        ledger.saveReceiptDraft(receipt("th", "TechHub", "6500", LocalDate.of(2026, 10, 22)))
        ledger.confirmReceipt(receipt("pharm", "City Pharmacy", "920", LocalDate.of(2026, 9, 28)))
        val oct = ledger.query(ExpenseFilter(range = resolver.resolve(DatePeriod.ThisMonth)))
        assertThat(oct.totals.single().total).isEqualTo(pkr("5480"))
        assertThat(oct.count).isEqualTo(2)
    }

    @Test fun `later receipt attached to a voice expense does not double count`() = runTest {
        val voice = ledger.addExpense(NewExpense(pkr("1500"), "groceries", LocalDate.of(2026, 10, 23), ExpenseSource.VOICE), "v1").expense
        val r = receipt("r1", total = "1500")
        assertThat(ledger.possibleDuplicates(r).map { it.id }).containsExactly(voice.id)
        val result = ledger.confirmReceipt(r, attachToExpenseId = voice.id) as ConfirmResult.Confirmed
        assertThat(result.createdExpense).isFalse()
        assertThat(result.expense.id).isEqualTo(voice.id)
        assertThat(result.expense.source).isEqualTo(ExpenseSource.VOICE)
        assertThat(result.expense.merchant).isEqualTo("FreshMart")
        assertThat(last30().totals.single().total).isEqualTo(pkr("1500"))
        assertThat(last30().count).isEqualTo(1)
    }

    @Test fun `attaching a receipt with a different total asks which amount to keep`() = runTest {
        val voice = ledger.addExpense(NewExpense(pkr("1500"), "groceries", today, ExpenseSource.VOICE), "v1").expense
        val r = receipt("r1", total = "1520")
        assertThat(ledger.confirmReceipt(r, voice.id)).isInstanceOf(ConfirmResult.NeedsAmountChoice::class.java)
        assertThat(store.receipt("r1")).isNull()
        val res = ledger.confirmReceipt(r, voice.id, AmountChoice.USE_RECEIPT_TOTAL) as ConfirmResult.Confirmed
        assertThat(res.expense.amount).isEqualTo(pkr("1520"))
        assertThat(last30().totals.single().total).isEqualTo(pkr("1520"))
    }

    @Test fun `merging a receipt that already created its own expense voids the extra one`() = runTest {
        val voice = ledger.addExpense(NewExpense(pkr("3800"), "groceries", today, ExpenseSource.VOICE), "v1").expense
        ledger.confirmReceipt(receipt("fm"))
        assertThat(last30().count).isEqualTo(2) // two real purchases until the person says otherwise
        ledger.confirmReceipt(store.receipt("fm")!!, attachToExpenseId = voice.id)
        assertThat(last30().count).isEqualTo(1)
        assertThat(last30().totals.single().total).isEqualTo(pkr("3800"))
    }

    @Test fun `editing a confirmed receipt updates its expense exactly once`() = runTest {
        val first = ledger.confirmReceipt(receipt("fm")) as ConfirmResult.Confirmed
        val edited = ledger.confirmReceipt(store.receipt("fm")!!.copy(total = pkr("3900"))) as ConfirmResult.Confirmed
        assertThat(edited.expense.id).isEqualTo(first.expense.id)
        assertThat(edited.createdExpense).isFalse()
        assertThat(last30().totals.single().total).isEqualTo(pkr("3900"))
        assertThat(store.revisions(first.expense.id).map { it.change }).hasSize(2)
    }

    @Test fun `delete and undo delete restore totals`() = runTest {
        ledger.confirmReceipt(receipt("fm"))
        val token = ledger.deleteReceipt("fm")
        assertThat(last30().isEmpty).isTrue()
        ledger.undoDelete(token)
        assertThat(last30().totals.single().total).isEqualTo(pkr("3800"))
        val token2 = ledger.deleteReceipt("fm")
        ledger.purgeReceipt(token2.receiptId)
        assertThat(store.receipt("fm")).isNull()
        assertThat(last30().isEmpty).isTrue()
    }

    @Test fun `deleting a receipt attached to a voice expense keeps the expense`() = runTest {
        val voice = ledger.addExpense(NewExpense(pkr("3800"), "groceries", today, ExpenseSource.VOICE), "v1").expense
        ledger.confirmReceipt(receipt("fm"), voice.id)
        ledger.deleteReceipt("fm")
        assertThat(last30().entries.map { it.id }).containsExactly(voice.id)
        assertThat(store.links(voice.id)).isEmpty()
    }

    @Test fun `undo excludes the expense once and only once`() = runTest {
        ledger.addExpense(NewExpense(pkr("1000"), "dining", today, ExpenseSource.MANUAL), "a")
        ledger.addExpense(NewExpense(pkr("300"), "fuel", today, ExpenseSource.VOICE), "b")
        assertThat(ledger.undo("b")).isInstanceOf(UndoResult.Undone::class.java)
        assertThat(ledger.undo("b")).isEqualTo(UndoResult.AlreadyUndone)
        assertThat(ledger.undo("missing")).isEqualTo(UndoResult.NotFound)
        assertThat(last30().totals.single().total).isEqualTo(pkr("1000"))
        // Re-delivering the original add after undo must not resurrect it.
        assertThat(ledger.addExpense(NewExpense(pkr("300"), "fuel", today, ExpenseSource.VOICE), "b")).isInstanceOf(AddResult.AlreadyExecuted::class.java)
        assertThat(last30().totals.single().total).isEqualTo(pkr("1000"))
    }

    @Test fun `partial bill payments keep a balance and payment receipts are not counted again`() = runTest {
        ledger.saveInvoice(Invoice("bill", "City Power", "INV-9", today.minusDays(5), today.plusDays(6), pkr("8240"), InvoiceStatus.UNPAID, false, "utilities", clock.instant()))
        assertThat(last30().isEmpty).isTrue()
        val p1 = ledger.recordPayment("bill", pkr("5000"), today, "pay-1")
        assertThat(ledger.recordPayment("bill", pkr("5000"), today, "pay-1").id).isEqualTo(p1.id)
        var bal = ledger.balance("bill")
        assertThat(bal.remaining).isEqualTo(pkr("3240"))
        assertThat(bal.invoice.status).isEqualTo(InvoiceStatus.PARTIALLY_PAID)
        assertThrows(LedgerValidationException::class.java) { kotlinx.coroutines.runBlocking { ledger.recordPayment("bill", pkr("4000"), today, "pay-x") } }
        ledger.recordPayment("bill", pkr("3240"), today, "pay-2")
        bal = ledger.balance("bill")
        assertThat(bal.remaining.isZero).isTrue()
        assertThat(store.invoice("bill")!!.status).isEqualTo(InvoiceStatus.PAID)
        ledger.attachPaymentReceipt(receipt("payslip", "City Power", "5000"), p1.id)
        val q = last30()
        assertThat(q.count).isEqualTo(2)
        assertThat(q.totals.single().total).isEqualTo(pkr("8240"))
        ledger.voidPayment(p1.id)
        assertThat(ledger.balance("bill").remaining).isEqualTo(pkr("5000"))
        assertThat(store.invoice("bill")!!.status).isEqualTo(InvoiceStatus.PARTIALLY_PAID)
    }

    @Test fun `refunds count negative on their own date and cannot exceed the purchase`() = runTest {
        val buy = (ledger.confirmReceipt(receipt("th", "TechHub", "6500", LocalDate.of(2026, 9, 20))) as ConfirmResult.Confirmed).expense
        ledger.recordRefund(pkr("6500"), today, "refund-1", buy.id)
        val oct = ledger.query(ExpenseFilter(range = resolver.resolve(DatePeriod.ThisMonth)))
        assertThat(oct.totals.single().total).isEqualTo(pkr("-6500"))
        val sep = ledger.query(ExpenseFilter(range = resolver.resolve(DatePeriod.LastMonth)))
        assertThat(sep.totals.single().total).isEqualTo(pkr("6500"))
        assertThrows(LedgerValidationException::class.java) { kotlinx.coroutines.runBlocking { ledger.recordRefund(pkr("1"), today, "refund-2", buy.id) } }
    }

    @Test fun `mixed currencies stay separate`() = runTest {
        ledger.addExpense(NewExpense(pkr("300"), "fuel", today, ExpenseSource.VOICE), "a")
        ledger.addExpense(NewExpense(Money(2500, Currencies.USD), "travel", today, ExpenseSource.MANUAL), "b")
        val totals = last30().totals.associate { it.total.currency.code to it.total.minor }
        assertThat(totals).containsExactly("PKR", 30_000L, "USD", 2500L)
    }

    @Test fun `receipt item categories allocate the total exactly including tax and discount`() = runTest {
        val items = listOf(
            ReceiptItem("i1", "fm", 0, "Milk", null, null, null, pkr("640"), "groceries"),
            ReceiptItem("i2", "fm", 1, "Shampoo", null, null, null, pkr("333"), "personal-care"),
        )
        ledger.confirmReceipt(receipt("fm", total = "1000", items = items))
        val cats = last30().byCategory.getValue("PKR").associate { it.categoryId to it.total.minor }
        assertThat(cats.values.sum()).isEqualTo(100_000L)
        assertThat(cats["groceries"]).isEqualTo(65_776L) // 1000 × 640/973 rounded by largest remainder
    }

    @Test fun `failed transactions leave no partial writes`() = runTest {
        val voice = ledger.addExpense(NewExpense(pkr("100"), "fuel", today, ExpenseSource.VOICE), "v").expense
        ledger.confirmReceipt(receipt("r1", total = "100"), voice.id)
        // Attaching a second receipt to the same expense must fail without saving the receipt.
        assertThrows(LedgerValidationException::class.java) {
            kotlinx.coroutines.runBlocking { ledger.confirmReceipt(receipt("r2", total = "100"), voice.id) }
        }
        assertThat(store.receipt("r2")).isNull()
    }

    @Test fun `confirm requires merchant date and total`() = runTest {
        val e = assertThrows(LedgerValidationException::class.java) {
            kotlinx.coroutines.runBlocking { ledger.confirmReceipt(receipt("x").copy(merchant = " ", total = null, purchaseDate = null)) }
        }
        assertThat(e.errors.map { it.field }).containsExactly("merchant", "date", "total")
        assertThat(ledger.validate(NewExpense(pkr("10"), "fuel", today.plusDays(3), ExpenseSource.MANUAL)).map { it.message }).contains("This date is in the future")
    }
}
