package com.receiptscan.actions

import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.Receipt
import com.receiptscan.money.MoneyFormat

/** CSV export of ledger entries (RFC 4180 quoting; formulas neutralised against spreadsheet injection). */
object CsvExport {
    private fun cell(v: String?): String {
        var s = v ?: ""
        if (s.isNotEmpty() && s[0] in "=+-@\t\r") s = "'$s"
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun expenses(entries: List<ExpenseEntry>): String = buildString {
        append("date,amount,currency,kind,category,merchant,note,source,state,id\r\n")
        entries.forEach { e ->
            append(listOf(e.effectiveDate.toString(), MoneyFormat.formatNumber(e.signedAmount.toDecimal(), e.amount.currency.fractionDigits).replace(",", ""), e.amount.currency.code, e.kind.name.lowercase(), Categories.name(e.categoryId), e.merchant, e.note, e.source.label, e.state.name.lowercase(), e.id).joinToString(",") { cell(it) })
            append("\r\n")
        }
    }

    fun receiptItems(r: Receipt): String = buildString {
        append("item,quantity,unit_price,line_total,currency\r\n")
        r.items.forEach { i ->
            append(listOf(i.description, i.quantity?.toPlainString(), i.unitPrice?.toDecimal()?.toPlainString(), i.lineTotal.toDecimal().toPlainString(), i.lineTotal.currency.code).joinToString(",") { cell(it) })
            append("\r\n")
        }
    }
}

/** Shopping list built from receipt items the person picks; duplicates merge by normalised name. */
object ShoppingListBuilder {
    data class Entry(val name: String, val lastPrice: String?, val sourceReceiptId: String?)

    fun fromReceipt(r: Receipt, pickedItemIds: Set<String>, existing: List<Entry>): List<Entry> {
        val seen = existing.map { it.name.lowercase() }.toMutableSet()
        val added = r.items.filter { it.id in pickedItemIds }.mapNotNull { i ->
            val name = i.description.trim()
            if (!seen.add(name.lowercase())) null else Entry(name, MoneyFormat.format(i.unitPrice ?: i.lineTotal), r.id)
        }
        return existing + added
    }
}
