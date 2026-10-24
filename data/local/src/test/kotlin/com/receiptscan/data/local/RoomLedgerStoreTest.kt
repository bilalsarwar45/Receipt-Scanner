package com.receiptscan.data.local

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.receiptscan.expenses.AddResult
import com.receiptscan.expenses.AmountChoice
import com.receiptscan.expenses.ConfirmResult
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.ExpenseSourceLink
import com.receiptscan.expenses.IdGenerator
import com.receiptscan.expenses.Invoice
import com.receiptscan.expenses.InvoiceStatus
import com.receiptscan.expenses.LedgerService
import com.receiptscan.expenses.LinkKind
import com.receiptscan.expenses.NewExpense
import com.receiptscan.expenses.Receipt
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptItem
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.expenses.UniqueConstraintException
import com.receiptscan.money.Currencies
import com.receiptscan.money.Money
import com.receiptscan.time.DatePeriod
import com.receiptscan.time.DateResolver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

/** Runs the ledger's double-counting rules against the real Room schema and constraints. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomLedgerStoreTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val clock = Clock.fixed(ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, zone).toInstant(), zone)
    private val counter = AtomicInteger()
    private val db = AppDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true)
    private val store = RoomLedgerStore(db)
    private val ledger = LedgerService(store, clock, { zone }, IdGenerator { "id-${counter.incrementAndGet()}" })
    private val resolver = DateResolver(clock, zone)
    private val today = LocalDate.of(2026, 10, 24)

    private fun pkr(major: String) = Money.fromDecimal(BigDecimal(major), Currencies.PKR)

    private fun receipt(id: String, merchant: String = "FreshMart", total: String = "3800", date: LocalDate = LocalDate.of(2026, 10, 23)) = Receipt(
        id = id, status = ReceiptStatus.NEEDS_REVIEW, merchant = merchant, purchaseDate = date, purchaseTime = "18:42",
        currency = Currencies.PKR, subtotal = pkr("3840"), tax = pkr("160"), discount = pkr("200"), total = pkr(total), categoryId = "groceries",
        scannedAt = clock.instant(), rawText = "FRESHMART\nMilk 1L 2 x 320 640\nRice 5kg 1200",
        items = listOf(
            ReceiptItem("$id-1", id, 0, "Milk 1L", BigDecimal("2"), null, pkr("320"), pkr("640"), "groceries"),
            ReceiptItem("$id-2", id, 1, "Rice 5kg", BigDecimal.ONE, null, pkr("1200"), pkr("1200"), "groceries", uncertain = true),
        ),
    )

    private suspend fun last30() = ledger.query(ExpenseFilter(range = resolver.resolve(DatePeriod.LastNDays(30))))

    @After fun close() = db.close()

    @Test fun `receipt round trips with items exactly`(): Unit = runBlocking {
        ledger.saveReceiptDraft(receipt("fm"))
        val loaded = store.receipt("fm")!!
        assertThat(loaded.items.map { it.lineTotal }).containsExactly(pkr("640"), pkr("1200")).inOrder()
        assertThat(loaded.items[1].uncertain).isTrue()
        assertThat(loaded.total).isEqualTo(pkr("3800"))
        assertThat(loaded.purchaseDate).isEqualTo(LocalDate.of(2026, 10, 23))
    }

    @Test fun `voice add is idempotent per execution key`(): Unit = runBlocking {
        val new = NewExpense(pkr("300"), "fuel", today, ExpenseSource.VOICE)
        val first = ledger.addExpense(new, "key-1")
        val again = ledger.addExpense(new, "key-1")
        assertThat(first).isInstanceOf(AddResult.Added::class.java)
        assertThat(again).isInstanceOf(AddResult.AlreadyExecuted::class.java)
        assertThat(last30().count).isEqualTo(1)
    }

    @Test fun `drafts unpaid bills and voided entries are excluded from totals`(): Unit = runBlocking {
        ledger.saveReceiptDraft(receipt("techhub", "TechHub", "6500"))
        ledger.saveInvoice(Invoice("bill", "City Power", "INV-1", today, today.plusDays(6), pkr("8240"), InvoiceStatus.UNPAID, false, "utilities", clock.instant()))
        val added = ledger.addExpense(NewExpense(pkr("500"), "dining", today, ExpenseSource.MANUAL), "k").expense
        ledger.voidExpense(added.id, "test")
        ledger.confirmReceipt(receipt("fm"))
        val result = last30()
        assertThat(result.count).isEqualTo(1)
        assertThat(result.totals.single().total).isEqualTo(pkr("3800"))
    }

    @Test fun `receipt attached to a voice expense does not double count`(): Unit = runBlocking {
        val voice = ledger.addExpense(NewExpense(pkr("1500"), "groceries", LocalDate.of(2026, 10, 23), ExpenseSource.VOICE), "v1").expense
        val r = receipt("r1", total = "1520")
        assertThat(ledger.confirmReceipt(r, voice.id)).isInstanceOf(ConfirmResult.NeedsAmountChoice::class.java)
        assertThat(store.receipt("r1")).isNull()
        val res = ledger.confirmReceipt(r, voice.id, AmountChoice.USE_RECEIPT_TOTAL) as ConfirmResult.Confirmed
        assertThat(res.expense.id).isEqualTo(voice.id)
        assertThat(last30().count).isEqualTo(1)
        assertThat(last30().totals.single().total).isEqualTo(pkr("1520"))
    }

    @Test fun `a receipt can support only one expense`(): Unit = runBlocking {
        val a = ledger.addExpense(NewExpense(pkr("10"), "fuel", today, ExpenseSource.MANUAL), "a").expense
        val b = ledger.addExpense(NewExpense(pkr("10"), "fuel", today, ExpenseSource.MANUAL), "b").expense
        store.insertLink(ExpenseSourceLink(a.id, LinkKind.RECEIPT, "r"))
        assertThrows(UniqueConstraintException::class.java) { runBlocking { store.insertLink(ExpenseSourceLink(b.id, LinkKind.RECEIPT, "r")) } }
    }

    @Test fun `failed transaction rolls back every write`(): Unit = runBlocking {
        runCatching {
            store.transaction {
                ledger.addExpense(NewExpense(pkr("10"), "fuel", today, ExpenseSource.MANUAL), "rollback")
                error("boom")
            }
        }
        assertThat(store.command("rollback")).isNull()
        assertThat(last30().isEmpty).isTrue()
    }

    @Test fun `delete undo and purge`(): Unit = runBlocking {
        ledger.confirmReceipt(receipt("fm"))
        val token = ledger.deleteReceipt("fm")
        assertThat(last30().isEmpty).isTrue()
        ledger.undoDelete(token)
        assertThat(last30().totals.single().total).isEqualTo(pkr("3800"))
        val token2 = ledger.deleteReceipt("fm")
        ledger.purgeReceipt(token2.receiptId)
        assertThat(store.receipt("fm")).isNull()
    }

    @Test fun `full text search finds receipts by item name and merchant`(): Unit = runBlocking {
        ledger.confirmReceipt(receipt("fm"))
        ledger.confirmReceipt(receipt("cafe", "Corner Café", "1680"))
        assertThat(store.receipts(ReceiptFilter(text = "rice")).map { it.id }).containsExactly("fm", "cafe")
        assertThat(store.receipts(ReceiptFilter(text = "corner")).map { it.id }).containsExactly("cafe")
        assertThat(store.receipts(ReceiptFilter(text = "zzz"))).isEmpty()
    }
}
