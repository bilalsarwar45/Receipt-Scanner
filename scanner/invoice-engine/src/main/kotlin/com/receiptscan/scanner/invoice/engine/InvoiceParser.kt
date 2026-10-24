package com.receiptscan.scanner.invoice.engine

import com.receiptscan.money.AmountParse
import com.receiptscan.money.AmountParser
import com.receiptscan.money.Currencies
import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.DecimalStyle
import com.receiptscan.money.Money
import com.receiptscan.ocr.model.Layout
import com.receiptscan.ocr.model.LayoutRow
import com.receiptscan.ocr.model.OcrDocument
import com.receiptscan.ocr.model.TextNormalizer
import com.receiptscan.scanner.contracts.Issue
import com.receiptscan.scanner.contracts.Value
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.time.DateOrder
import com.receiptscan.time.PrintedDateParser
import java.math.RoundingMode
import java.time.LocalDate

/** Bill/invoice fields read from a photo. A captured bill is never a paid expense by itself. */
data class InvoiceExtraction(
    val supplier: Value<String>?,
    val invoiceNumber: Value<String>?,
    val accountNumber: Value<String>?,
    val issueDate: Value<LocalDate>?,
    val dueDate: Value<LocalDate>?,
    val billingPeriod: Value<String>?,
    /** Amount payable by the due date. */
    val amountDue: Value<Money>?,
    /** Amount payable after the due date (common on Pakistani utility bills), if printed. */
    val amountAfterDue: Value<Money>?,
    val taxes: List<Pair<String, Value<Money>>>,
    /** Only true when the bill itself says it is paid (e.g. "PAID" stamp text). Never assumed. */
    val paidEvidence: Value<String>?,
    val currency: Value<CurrencySpec>,
    val issues: List<Issue>,
    val rawText: String,
)

class InvoiceParser(
    private val today: LocalDate,
    private val defaultCurrency: CurrencySpec = Currencies.PKR,
    private val dateOrder: DateOrder = DateOrder.DMY,
) {
    private val dueKeys = Regex("(?i)(due date|pay by|payment due|last date|due on|payable by|due)")
    private val issueKeys = Regex("(?i)(issue date|invoice date|bill date|date of issue|reading date|dated|date)")
    private val amountDueKeys = Regex("(?i)(payable within due date|amount payable|amount due|total due|total payable|balance due|net payable|total amount due|grand total|invoice total|total)")
    private val afterDueKeys = Regex("(?i)(after due date|payable after|late payment|after due)")
    private val invoiceNoKeys = Regex("(?i)(?:invoice|bill|inv)\\s*(?:no|number|#)\\.?\\s*[:#]?\\s*([A-Z0-9][A-Z0-9/-]{2,})")
    private val accountKeys = Regex("(?i)(?:consumer|account|customer|reference|ref)\\s*(?:no|number|id|#)\\.?\\s*[:#]?\\s*([A-Z0-9][A-Z0-9 /-]{3,})")
    private val periodKeys = Regex("(?i)(?:bill(?:ing)? (?:month|period)|for the month of|period)\\s*[:]?\\s*([A-Za-z]{3,9}[ -]?\\d{2,4}(?:\\s*(?:to|-)\\s*[A-Za-z]{3,9}[ -]?\\d{2,4})?)")
    private val taxKeys = Regex("(?i)(gst|sales tax|vat|tax|fed|withholding|income tax|electricity duty|tv fee)")
    private val paidKeys = Regex("(?i)\\b(paid in full|payment received|paid|status:\\s*paid)\\b")
    private val amountRx = Regex("""(?<![\p{L}\d.,:/#-])(?:Rs\.?\s?|PKR\s?)?\d[\d,]*(?:\.\d{1,3})?(?:/-)?(?![\p{L}\d%:/])""", RegexOption.IGNORE_CASE)

    fun parse(doc: OcrDocument): InvoiceExtraction {
        val rows = Layout.rows(doc)
        val texts = rows.map { TextNormalizer.normalize(AmountParser.normalizeDigits(it.text)) }
        val currency = if (Regex("(?i)\\b(rs\\.?|pkr|rupees)\\b").containsMatchIn(doc.fullText)) Value(Currencies.PKR, ValueSource.FROM_PHOTO)
        else if (doc.fullText.contains("$") || Regex("(?i)\\busd\\b").containsMatchIn(doc.fullText)) Value(Currencies.USD, ValueSource.FROM_PHOTO)
        else Value(defaultCurrency, ValueSource.SUGGESTED)
        val cur = currency.value
        val issues = mutableListOf<Issue>()

        fun region(r: LayoutRow) = r.page.region(r.box)
        fun dateNear(i: Int, keys: Regex, exclude: Regex? = null): Value<LocalDate>? {
            for (j in rows.indices) {
                val t = texts[j]
                if (!keys.containsMatchIn(t) || (exclude != null && exclude.containsMatchIn(t))) continue
                val after = t.substring(keys.find(t)!!.range.last + 1)
                val sameRow = PrintedDateParser.findAll(after, today, dateOrder).firstOrNull()
                    ?: texts.getOrNull(j + 1)?.let { PrintedDateParser.findAll(it, today, dateOrder).firstOrNull() }
                if (sameRow != null) {
                    if (sameRow.isAmbiguous) issues += Issue("date-ambiguous", "A date can be read two ways", keys.pattern)
                    return Value.fromPhoto(sameRow.date, region(rows[j]), sameRow.raw)
                }
            }
            return null
        }
        fun amountNear(keys: Regex, exclude: Regex?): Value<Money>? {
            var best: Value<Money>? = null
            for (j in rows.indices) {
                val t = texts[j]
                if (!keys.containsMatchIn(t) || (exclude != null && exclude.containsMatchIn(t))) continue
                val after = t.substring(keys.find(t)!!.range.last + 1)
                val token = amountRx.findAll(after).lastOrNull()?.value
                    ?: texts.getOrNull(j + 1)?.let { n -> amountRx.findAll(n).lastOrNull()?.value?.takeIf { PrintedDateParser.findAll(n, today).isEmpty() } }
                    ?: continue
                val p = AmountParser.parse(token, DecimalStyle.POINT, cur.fractionDigits) as? AmountParse.Ok ?: continue
                if (p.value.signum() <= 0) continue
                best = Value.fromPhoto(Money.fromDecimal(p.value, cur, RoundingMode.HALF_EVEN), region(rows[j]), t)
                if (!Regex("(?i)^total$").matches(keys.find(t)!!.value)) return best
            }
            return best
        }

        val due = dateNear(0, dueKeys, afterDueKeys)
        val issue = dateNear(0, issueKeys, dueKeys)
        val amountDue = amountNear(amountDueKeys, afterDueKeys)
        val afterDue = amountNear(afterDueKeys, null)
        val invoiceNo = rows.indices.firstNotNullOfOrNull { j -> invoiceNoKeys.find(texts[j])?.let { Value.fromPhoto(it.groupValues[1], region(rows[j]), texts[j]) } }
        val account = rows.indices.firstNotNullOfOrNull { j -> accountKeys.find(texts[j])?.let { Value.fromPhoto(it.groupValues[1].trim(), region(rows[j]), texts[j]) } }
        val period = rows.indices.firstNotNullOfOrNull { j -> periodKeys.find(texts[j])?.let { Value.fromPhoto(it.groupValues[1].trim(), region(rows[j]), texts[j]) } }
        val taxes = rows.indices.mapNotNull { j ->
            val t = texts[j]
            if (!taxKeys.containsMatchIn(t) || amountDueKeys.containsMatchIn(t) || Regex("(?i)(ntn|strn|reg)").containsMatchIn(t)) return@mapNotNull null
            val token = amountRx.findAll(t).lastOrNull()?.value ?: return@mapNotNull null
            val p = AmountParser.parse(token, DecimalStyle.POINT, cur.fractionDigits) as? AmountParse.Ok ?: return@mapNotNull null
            taxKeys.find(t)!!.value to Value.fromPhoto(Money.fromDecimal(p.value.abs(), cur, RoundingMode.HALF_EVEN), region(rows[j]), t)
        }
        val paid = rows.indices.firstNotNullOfOrNull { j ->
            val t = texts[j]
            if (paidKeys.containsMatchIn(t) && !Regex("(?i)(un ?paid|to be paid|amount paid|payable)").containsMatchIn(t)) Value.fromPhoto(paidKeys.find(t)!!.value, region(rows[j]), t) else null
        }
        val supplier = rows.take(5).withIndex().firstOrNull { (j, r) ->
            val t = texts[j]
            t.count { it.isLetter() } >= 3 && amountRx.find(t) == null && PrintedDateParser.findAll(t, today).isEmpty() &&
                !Regex("(?i)^(tax invoice|invoice|bill|electricity bill|gas bill|statement)$").matches(t.trim())
        }?.let { (j, r) -> Value.fromPhoto(texts[j].trim(), region(r), texts[j]) }

        if (supplier == null) issues += Issue("supplier-missing", "Add who the bill is from", "supplier")
        if (amountDue == null) issues += Issue("amount-missing", "We couldn't read the amount due", "amountDue")
        if (due == null) issues += Issue("due-missing", "Add the due date to get a reminder", "dueDate")
        if (due != null && issue != null && due.value.isBefore(issue.value)) issues += Issue("due-before-issue", "Due date is before the issue date", "dueDate")

        return InvoiceExtraction(supplier, invoiceNo, account, issue, due, period, amountDue, afterDue, taxes, paid, currency, issues, texts.joinToString("\n"))
    }
}
