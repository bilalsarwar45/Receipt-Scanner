package com.receiptscan.app

import com.receiptscan.expenses.Invoice
import com.receiptscan.expenses.InvoiceStatus
import com.receiptscan.expenses.Receipt
import com.receiptscan.expenses.ReceiptItem
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.money.Currencies
import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.Money
import com.receiptscan.scanner.invoice.BillPayload
import com.receiptscan.scanner.receipt.ReceiptDraftPayload
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

fun currencyOf(code: String): CurrencySpec = Currencies.find(code) ?: CurrencySpec(code.uppercase(), 2)

/** Converts the receipt module's payload into the ledger's receipt. Values are kept exactly as reviewed. */
fun ReceiptDraftPayload.toReceipt(id: String, scannedAt: Instant): Receipt {
    val cur = currencyOf(currency)
    fun m(v: Long?) = v?.let { Money(it, cur) }
    return Receipt(
        id = id, status = ReceiptStatus.NEEDS_REVIEW, merchant = merchant, purchaseDate = date?.let(LocalDate::parse), purchaseTime = time,
        currency = cur, subtotal = m(subtotalMinor), tax = m(taxMinor), discount = m(discountMinor), total = m(totalMinor),
        categoryId = categoryId, scannedAt = scannedAt, rawText = rawText, reference = reference, paymentMethod = paymentMethod,
        sourceImageIds = sourceImageIds.filterNot { it.startsWith("session-") }, ocrEngine = ocrEngine, reviewReasons = reviewReasons,
        items = items.mapIndexed { i, it ->
            ReceiptItem(UUID.randomUUID().toString(), id, i, it.description.ifBlank { "Item ${i + 1}" }, it.quantity?.toBigDecimalOrNull(), it.unit,
                it.unitPriceMinor?.let { p -> Money(p, cur) }, Money(it.lineTotalMinor, cur), it.categoryId, if (it.edited) "edited" else "photo", it.rawText, it.uncertain)
        },
    )
}

fun BillPayload.toInvoice(id: String, scannedAt: Instant, categoryId: String = "bills"): Invoice = Invoice(
    id = id, supplier = supplier, invoiceNumber = invoiceNumber, issueDate = issueDate?.let(LocalDate::parse), dueDate = dueDate?.let(LocalDate::parse),
    amountDue = Money(amountDueMinor, currencyOf(currency)), status = InvoiceStatus.UNPAID, needsReview = needsReview, categoryId = categoryId,
    scannedAt = scannedAt, rawText = rawText, sourceImageIds = sourceIds.filterNot { it.startsWith("session-") },
)
