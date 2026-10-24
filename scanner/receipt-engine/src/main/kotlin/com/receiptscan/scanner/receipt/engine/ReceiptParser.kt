package com.receiptscan.scanner.receipt.engine

import com.receiptscan.money.AmountParse
import com.receiptscan.money.AmountParser
import com.receiptscan.money.Currencies
import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.DecimalStyle
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import com.receiptscan.ocr.model.Layout
import com.receiptscan.ocr.model.LayoutRow
import com.receiptscan.ocr.model.OcrDocument
import com.receiptscan.ocr.model.TextNormalizer
import com.receiptscan.scanner.contracts.EvidenceRegion
import com.receiptscan.scanner.contracts.Issue
import com.receiptscan.scanner.contracts.Value
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.time.DateOrder
import com.receiptscan.time.PrintedDateParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class ReceiptParserConfig(
    val today: LocalDate,
    val defaultCurrency: CurrencySpec = Currencies.PKR,
    val dateOrder: DateOrder = DateOrder.DMY,
    val decimalStyle: DecimalStyle = DecimalStyle.POINT,
    /** Learned merchant names: normalized key → display name the person chose. */
    val merchantAliases: Map<String, String> = emptyMap(),
    val categorizer: Categorizer = Categorizer(),
    /** Engine line confidence below this marks a line "Hard to read". ML Kit v2 reports per-line confidence. */
    val lowConfidence: Float = 0.5f,
)

/**
 * Geometry-aware receipt parser. Pipeline (brief section 6):
 * rows by geometry → normalisation → row roles → merchant/date/reference → items with quantity/unit
 * price/line total and wrapped descriptions → totals, taxes, discounts, tender and change →
 * reconciliation → review issues. A partial result is returned rather than invented values.
 */
class ReceiptParser(private val config: ReceiptParserConfig) {

    private data class Row(
        val index: Int,
        val pageIndex: Int,
        val layout: LayoutRow,
        val text: String,
        val key: String,
        val amounts: List<AmountToken>,
        val lowConfidence: Boolean,
    ) {
        val trailing: AmountToken? get() = amounts.lastOrNull()?.takeIf { it.isTrailing }
        fun region(): EvidenceRegion = layout.page.region(layout.box)
    }

    private data class AmountToken(val raw: String, val value: BigDecimal, val start: Int, val end: Int, val isTrailing: Boolean, val hasDecimals: Boolean)

    private val numberToken = Regex("""(?<![\p{L}\d.,:/#-])\(?-?(?:Rs\.?\s?|PKR\s?|₨\s?|\$\s?)?\d[\d,]*(?:[.,]\d{1,3})?\)?-?(?![\p{L}\d%:]|/(?!-)|-\d|\s?%|\s?(?:kg|g|gm|ml|l|ltr|pcs|pc|x)\b)""", RegexOption.IGNORE_CASE)

    fun parse(doc: OcrDocument): ReceiptExtraction {
        val currency = detectCurrency(doc.fullText)
        val cur = currency.value
        val allRows = mutableListOf<Row>()
        var dropped = 0
        doc.pages.sortedBy { it.pageIndex }.forEach { page ->
            val rows = Layout.rows(page)
            val start = if (allRows.isEmpty()) 0 else overlapLength(allRows.takeLast(8).map { it.key }, rows.map { TextNormalizer.key(it.text) })
            dropped += start
            rows.drop(start).forEach { lr -> allRows += buildRow(allRows.size, page.pageIndex, lr, cur) }
        }

        val roles = allRows.map { classify(it) }.toMutableList()
        // Rows with an amount before the first totals row are items; after it, unknown amount rows are payment info.
        val firstTotalIdx = roles.indexOfFirst { it == RowRole.SUBTOTAL || it == RowRole.TOTAL }.let { if (it < 0) roles.size else it }
        for (i in roles.indices) {
            if (roles[i] == RowRole.ITEM && i > firstTotalIdx) roles[i] = RowRole.PAYMENT_INFO
        }
        var firstItemIdx = roles.indexOfFirst { it == RowRole.ITEM }.let { if (it < 0) firstTotalIdx else it }
        // The text row right before the first item can be the first half of a wrapped item name,
        // as long as it comes after the header's date line.
        val dateRowIdx = allRows.indexOfFirst { PrintedDateParser.findAll(it.text, config.today).isNotEmpty() }
        if (firstItemIdx in 2 until roles.size && dateRowIdx in 0 until firstItemIdx - 1 && roles[firstItemIdx - 1] == RowRole.TEXT &&
            allRows[firstItemIdx - 1].text.count { it.isLetter() } >= 3
        ) {
            firstItemIdx -= 1
        }
        for (i in 0 until firstItemIdx) if (roles[i] == RowRole.TEXT) roles[i] = RowRole.HEADER

        val issues = mutableListOf<Issue>()
        val merchant = findMerchant(allRows, roles, firstItemIdx)
        val (date, alternatives) = findDate(allRows)
        val time = allRows.firstNotNullOfOrNull { r -> PrintedDateParser.findTime(r.text)?.let { Value.fromPhoto(it, r.region(), r.text) } }
        val reference = allRows.firstNotNullOfOrNull { r -> Keywords.reference.find(r.text)?.let { Value.fromPhoto(it.groupValues[1], r.region(), r.text) } }
        val payment = allRows.withIndex().firstNotNullOfOrNull { (i, r) ->
            if (roles[i] == RowRole.TENDERED || roles[i] == RowRole.PAYMENT_INFO) Keywords.paymentMethod.find(r.text)?.let { Value.fromPhoto(it.value.replaceFirstChar { c -> c.uppercase() }, r.region(), r.text) } else null
        }

        val items = buildItems(allRows, roles, firstItemIdx, firstTotalIdx, cur)

        var subtotal: Value<Money>? = null
        val adjustments = mutableListOf<Adjustment>()
        val totals = mutableListOf<Pair<Int, Value<Money>>>()
        var tendered: Value<Money>? = null
        var change: Value<Money>? = null
        allRows.forEachIndexed { i, r ->
            val amt = r.trailing ?: return@forEachIndexed
            val money = Money.fromDecimal(amt.value.abs(), cur, RoundingMode.HALF_EVEN)
            val v = Value.fromPhoto(money, r.region(), r.text)
            when (roles[i]) {
                RowRole.SUBTOTAL -> if (subtotal == null) subtotal = v
                RowRole.TAX -> adjustments += Adjustment(label(r), RowRole.TAX, v, included = Keywords.taxIncluded.containsMatchIn(r.key), percent = percentIn(r.text))
                RowRole.DISCOUNT -> adjustments += Adjustment(label(r), RowRole.DISCOUNT, v, percent = percentIn(r.text))
                RowRole.SERVICE -> adjustments += Adjustment(label(r), RowRole.SERVICE, v, percent = percentIn(r.text))
                RowRole.ROUNDING -> adjustments += Adjustment(label(r), RowRole.ROUNDING, Value.fromPhoto(Money.fromDecimal(amt.value, cur, RoundingMode.HALF_EVEN), r.region(), r.text))
                RowRole.TOTAL -> totals += totalPriority(r.key) to v
                RowRole.TENDERED -> if (tendered == null) tendered = v
                RowRole.CHANGE -> if (change == null) change = v
                else -> Unit
            }
        }
        // Repeated total lines are one total. Prefer "grand/net/payable" wording, then the last plain total.
        val total = totals.maxWithOrNull(compareBy<Pair<Int, Value<Money>>> { it.first }.thenBy { totals.indexOf(it) })?.second
        val dedupAdjustments = adjustments.distinctBy { it.role to it.amount.value to it.label }

        val reconciliation = reconcile(items, subtotal?.value, dedupAdjustments, total?.value, cur)
        if (reconciliation.taxesIncluded.minor > 0 && dedupAdjustments.none { it.role == RowRole.TAX && it.included }) {
            issues += Issue("tax-included-inferred", "Tax appears to be included in item prices", "tax")
        }

        if (merchant == null) issues += Issue("merchant-missing", "Add a merchant name", "merchant")
        if (date == null) issues += Issue("date-missing", "Add the purchase date", "date")
        if (alternatives.isNotEmpty()) issues += Issue("date-ambiguous", "Date can be read two ways", "date")
        if (total == null) {
            issues += Issue("total-missing", "We couldn't read the total", "total")
        } else if (reconciliation.matches == false) {
            val computed = reconciliation.computedTotal!!
            issues += Issue("totals-mismatch", "Items add up to ${MoneyFormat.format(computed)}, total says ${MoneyFormat.format(total.value)}", "total")
        }
        if (reconciliation.subtotalPrinted != null && reconciliation.itemsSum != null && reconciliation.itemsSum != reconciliation.subtotalPrinted) {
            issues += Issue("items-subtotal-mismatch", "Items add up to ${MoneyFormat.format(reconciliation.itemsSum)}, subtotal says ${MoneyFormat.format(reconciliation.subtotalPrinted)}", "items")
        }
        items.forEachIndexed { i, it -> if (it.uncertain) issues += Issue("item-uncertain", it.uncertainReasons.first(), "items[$i]") }
        if (allRows.any { r -> r.amounts.any { a -> AmountParser.parse(a.raw, config.decimalStyle, cur.fractionDigits).let { p -> p is AmountParse.Ok && p.ambiguousSeparators && a.raw.contains('.') } } }) {
            issues += Issue("separator-ambiguous", "Some amounts could be read two ways; check decimal points", null)
        }
        if (items.isEmpty() && total != null) issues += Issue("no-items", "No item lines found; the total can still be saved", "items")

        val suggestedCategory = config.categorizer.categorizeReceipt(merchant?.value, items)

        return ReceiptExtraction(
            merchant = merchant,
            date = date,
            dateAlternatives = alternatives,
            time = time,
            reference = reference,
            paymentMethod = payment,
            currency = currency,
            items = items.map { it.copy(suggestedCategory = it.suggestedCategory ?: config.categorizer.categorizeItem(it.description.value)) },
            subtotal = subtotal,
            adjustments = dedupAdjustments,
            total = total,
            tendered = tendered,
            change = change,
            reconciliation = reconciliation,
            issues = issues,
            suggestedCategory = suggestedCategory?.let { Value.suggested(it) },
            rawText = allRows.joinToString("\n") { it.text },
            lineCount = doc.lineCount,
            overlapRowsDropped = dropped,
            rows = allRows.mapIndexed { i, r -> ClassifiedRow(i, r.pageIndex, r.text, roles[i], r.trailing?.let { Money.fromDecimal(it.value.abs(), cur, RoundingMode.HALF_EVEN) }) },
        )
    }

    // ---------------- rows ----------------

    private fun buildRow(index: Int, pageIndex: Int, lr: LayoutRow, cur: CurrencySpec): Row {
        val text = AmountParser.normalizeDigits(TextNormalizer.normalize(lr.text))
        val tokens = numberToken.findAll(text).mapNotNull { m ->
            val raw = m.value.trim()
            val parsed = AmountParser.parse(raw, config.decimalStyle, cur.fractionDigits)
            if (parsed !is AmountParse.Ok) return@mapNotNull null
            val after = text.substring(m.range.last + 1)
            val trailing = after.isBlank() || after.trim().matches(Regex("""(?i)[*#a-z]{0,2}|/-"""))
            AmountToken(raw, parsed.value, m.range.first, m.range.last + 1, trailing, Regex("""[.,]\d{1,3}\)?-?$""").containsMatchIn(raw))
        }.toList()
        val low = lr.cells.any { (it.confidence ?: 1f) < config.lowConfidence }
        return Row(index, pageIndex, lr, text, TextNormalizer.key(text), tokens, low)
    }

    private fun overlapLength(previousTail: List<String>, next: List<String>): Int {
        // Largest k such that the first k rows of the next part equal the last k rows of the previous part.
        for (k in minOf(previousTail.size, next.size) downTo 1) {
            if (previousTail.takeLast(k) == next.take(k) && next.take(k).any { it.length >= 3 }) return k
        }
        return 0
    }

    private fun classify(r: Row): RowRole {
        val k = r.key
        val hasAmount = r.trailing != null
        if (!hasAmount) return RowRole.TEXT
        val description = k.replace(Regex("\\d+"), "").trim()
        // Date/time lines carry numbers but are header metadata, not purchases.
        if (PrintedDateParser.findAll(r.text, config.today).isNotEmpty() && !Keywords.total.containsMatchIn(k)) return RowRole.TEXT
        if (Keywords.ignored.containsMatchIn(k)) return RowRole.IGNORED
        val isTotalWord = Keywords.grandTotal.containsMatchIn(k) || Regex("(?<![\\p{L}])(total|kul|کل|ٹوٹل)(?![\\p{L}])").containsMatchIn(k)
        return when {
            Keywords.change.containsMatchIn(k) -> RowRole.CHANGE
            Keywords.tendered.containsMatchIn(k) && !Keywords.grandTotal.containsMatchIn(k) -> RowRole.TENDERED
            Keywords.rounding.containsMatchIn(k) -> RowRole.ROUNDING
            Keywords.subtotal.containsMatchIn(k) -> RowRole.SUBTOTAL
            isTotalWord && Keywords.tax.containsMatchIn(k) && Keywords.taxIncluded.containsMatchIn(k) -> RowRole.TOTAL
            Keywords.tax.containsMatchIn(k) -> RowRole.TAX
            Keywords.discount.containsMatchIn(k) -> RowRole.DISCOUNT
            Keywords.service.containsMatchIn(k) && description.split(' ').size <= 4 -> RowRole.SERVICE
            Keywords.grandTotal.containsMatchIn(k) -> RowRole.TOTAL
            isTotalWord -> RowRole.TOTAL
            else -> RowRole.ITEM
        }
    }

    private fun totalPriority(key: String) = if (Keywords.grandTotal.containsMatchIn(key)) 2 else 1

    private fun label(r: Row): String {
        val t = r.trailing ?: return r.text
        return r.text.substring(0, t.start).trim().trimEnd(':', '.', '-').trim().ifEmpty { r.text }
    }

    private fun percentIn(text: String): BigDecimal? =
        Regex("""(\d{1,2}(?:\.\d{1,2})?)\s?%""").find(text)?.groupValues?.get(1)?.toBigDecimal()

    // ---------------- header ----------------

    private fun findMerchant(rows: List<Row>, roles: List<RowRole>, firstItemIdx: Int): Value<String>? {
        val header = rows.take(maxOf(firstItemIdx, 0).coerceAtMost(8)).ifEmpty { rows.take(3) }
        val candidates = header.filter { r ->
            val letters = r.text.count { it.isLetter() }
            letters >= 3 && r.amounts.isEmpty() &&
                !Keywords.headerNoise.matches(r.key) && !r.key.startsWith("tel") && !r.key.startsWith("ntn") &&
                PrintedDateParser.findAll(r.text, config.today).isEmpty() &&
                !Regex("""\d{3,}""").containsMatchIn(r.text.replace(" ", "")) &&
                !Regex("(?i)^(receipt|invoice|tax invoice|sales receipt|cash memo|welcome( to)?)$").matches(r.text.trim())
        }.filterNot { r -> Regex("(?i)\\b(road|street|st\\.|block|plot|sector|phase|floor|near|opp)\\b").containsMatchIn(r.text) }
        if (candidates.isEmpty()) return null
        // Store names are usually the tallest text near the top.
        val best = candidates.take(4).maxBy { it.layout.box.height * 10 - candidates.indexOf(it) }
        val raw = best.text.trim().trim('*', '-', '=', ' ')
        val alias = config.merchantAliases[TextNormalizer.key(raw)]
        return if (alias != null) Value(alias, ValueSource.SUGGESTED, best.region(), rawText = raw) else Value.fromPhoto(prettyName(raw), best.region(), raw)
    }

    private fun prettyName(raw: String): String =
        if (raw == raw.uppercase() && raw.any { it.isLetter() }) raw.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else raw

    private fun findDate(rows: List<Row>): Pair<Value<LocalDate>?, List<LocalDate>> {
        for (r in rows) {
            val found = PrintedDateParser.findAll(r.text, config.today, config.dateOrder, maxFutureDays = 1).firstOrNull { !it.monthOnly } ?: continue
            return Value.fromPhoto(found.date, r.region(), found.raw) to found.alternatives.filter { !it.isAfter(config.today.plusDays(1)) }
        }
        return null to emptyList()
    }

    // ---------------- items ----------------

    private val qtyTimesPrice = Regex("""(?i)(?<![\d.])(\d{1,4}(?:[.,]\d{1,3})?)\s*(kg|g|gm|l|ltr|ml|pcs?|pc|nos?)?\s*(?:x|×|\*|@)\s*(?:rs\.?\s?)?(\d[\d,]*(?:\.\d{1,3})?)""")

    private fun buildItems(rows: List<Row>, roles: List<RowRole>, firstItemIdx: Int, firstTotalIdx: Int, cur: CurrencySpec): List<ExtractedItem> {
        val items = mutableListOf<ExtractedItem>()
        var pendingText: Row? = null
        for (i in firstItemIdx.coerceAtLeast(0) until firstTotalIdx.coerceAtMost(rows.size)) {
            val r = rows[i]
            when (roles[i]) {
                RowRole.TEXT -> {
                    // A text-only row inside the item area: either the first half of a wrapped description,
                    // or a continuation of the previous item (indented or starting lower-case).
                    val prev = items.lastOrNull()
                    val continuation = prev != null && pendingText == null && (
                        r.layout.box.left > rows[prev.rowIndex].layout.box.left + 12 || r.text.firstOrNull()?.isLowerCase() == true
                        )
                    if (continuation) {
                        items[items.lastIndex] = prev!!.copy(description = prev.description.copy(value = prev.description.value + " " + r.text.trim(), region = prev.description.region?.union(r.region())))
                    } else {
                        pendingText = r
                    }
                }
                RowRole.ITEM -> {
                    val item = parseItem(r, cur, pendingText)
                    if (item != null) items += item
                    pendingText = null
                }
                else -> pendingText = null
            }
        }
        return items
    }

    private fun parseItem(r: Row, cur: CurrencySpec, pending: Row?): ExtractedItem? {
        val totalTok = r.trailing ?: return null
        if (totalTok.value.signum() < 0) return null // negative lines inside items are discounts printed inline
        val lineTotal = Money.fromDecimal(totalTok.value, cur, RoundingMode.HALF_EVEN)
        var quantity: BigDecimal? = null
        var unit: String? = null
        var unitPrice: Money? = null
        var descEnd = totalTok.start
        val reasons = mutableListOf<String>()

        val m = qtyTimesPrice.find(r.text.substring(0, totalTok.start))
        if (m != null) {
            quantity = m.groupValues[1].replace(',', '.').toBigDecimalOrNull()
            unit = m.groupValues[2].ifEmpty { null }?.lowercase()
            unitPrice = (AmountParser.parse(m.groupValues[3], config.decimalStyle, cur.fractionDigits) as? AmountParse.Ok)?.let { Money.fromDecimal(it.value, cur, RoundingMode.HALF_EVEN) }
            descEnd = m.range.first
        } else {
            // Column layout: "Milk 1L   2   320.00   640.00" → qty, unit price, line total.
            val before = r.amounts.filter { it.end <= totalTok.start }
            if (before.size >= 2) {
                val q = before[before.size - 2]
                val p = before[before.size - 1]
                if (q.value.signum() > 0 && q.value.scale() <= 3 && !q.hasDecimals && q.value <= BigDecimal(9999)) {
                    if (q.value.multiply(p.value).compareTo(totalTok.value) == 0) {
                        quantity = q.value
                        unitPrice = Money.fromDecimal(p.value, cur, RoundingMode.HALF_EVEN)
                        descEnd = q.start
                    }
                }
            } else if (before.size == 1) {
                val p = before[0]
                // "Bread  240.00  240.00" (unit price + total, qty 1) or "Eggs 12 550" (12 is part of the name).
                if (p.hasDecimals && p.value.signum() > 0) {
                    val ratio = totalTok.value.divide(p.value, 6, RoundingMode.HALF_EVEN)
                    if (ratio.stripTrailingZeros().scale() <= 0 && ratio <= BigDecimal(999)) {
                        quantity = ratio.setScale(0)
                        unitPrice = Money.fromDecimal(p.value, cur, RoundingMode.HALF_EVEN)
                        descEnd = p.start
                    }
                }
            }
        }

        var description = r.text.substring(0, descEnd).trim().trimEnd('-', ':', '.', '@', 'x', 'X').trim()
        if (pending != null && (description.count { it.isLetter() } < 3 || pending.layout.box.left <= r.layout.box.left + 4)) {
            description = (pending.text.trim() + " " + description).trim()
        }
        if (description.count { it.isLetter() } == 0) {
            if (description.isEmpty() && pending == null) return null
            reasons += "Item name unreadable"
        }
        if (quantity != null && unitPrice != null) {
            val expected = unitPrice.times(quantity)
            if (expected != lineTotal) reasons += "Hard to read · ${quantity.stripTrailingZeros().toPlainString()} × ${MoneyFormat.format(unitPrice, false)} ≠ ${MoneyFormat.format(lineTotal, false)}"
        }
        if (r.lowConfidence || pending?.lowConfidence == true) reasons += "Hard to read · check price"

        val region = pending?.region()?.union(r.region()) ?: r.region()
        return ExtractedItem(
            description = Value.fromPhoto(description, region, r.text),
            quantity = quantity?.let { Value.fromPhoto(it, region) },
            unit = unit,
            unitPrice = unitPrice?.let { Value.fromPhoto(it, region) },
            lineTotal = Value.fromPhoto(lineTotal, r.region(), totalTok.raw),
            uncertainReasons = reasons,
            rowIndex = r.index,
        )
    }

    // ---------------- reconciliation ----------------

    private fun reconcile(items: List<ExtractedItem>, subtotal: Money?, adjustments: List<Adjustment>, total: Money?, cur: CurrencySpec): Reconciliation {
        val zero = Money.zero(cur)
        val itemsSum = if (items.isEmpty()) null else items.fold(zero) { a, i -> a + i.lineTotal.value }
        val base = subtotal ?: itemsSum
        val taxes = adjustments.filter { it.role == RowRole.TAX }
        val explicitIncluded = taxes.filter { it.included }.fold(zero) { a, t -> a + t.amount.value }
        val maybeAdded = taxes.filter { !it.included }.fold(zero) { a, t -> a + t.amount.value }
        val discounts = adjustments.filter { it.role == RowRole.DISCOUNT }.fold(zero) { a, t -> a + t.amount.value }
        val service = adjustments.filter { it.role == RowRole.SERVICE }.fold(zero) { a, t -> a + t.amount.value }
        val rounding = adjustments.filter { it.role == RowRole.ROUNDING }.fold(zero) { a, t -> a + t.amount.value }

        var taxesAdded = maybeAdded
        var taxesIncluded = explicitIncluded
        var computed = base?.let { it + taxesAdded + service - discounts + rounding }
        if (base != null && total != null && computed != total && maybeAdded.minor > 0) {
            // Second hypothesis: the printed taxes are already inside item prices.
            val alt = base + service - discounts + rounding
            if (alt == total) {
                taxesIncluded += maybeAdded
                taxesAdded = zero
                computed = alt
            }
        }
        val tolerance = cur.minorPerMajor / 100 // 0.01 in major units, per the validation rules
        val difference = if (computed != null && total != null) total - computed else null
        val matches = difference?.let { kotlin.math.abs(it.minor) <= tolerance }

        val parts = buildList {
            base?.let { add((if (subtotal != null) "Subtotal " else "Items ") + MoneyFormat.format(it, false)) }
            if (taxesAdded.minor > 0) add("+ Tax ${MoneyFormat.format(taxesAdded, false)}")
            if (service.minor > 0) add("+ Service ${MoneyFormat.format(service, false)}")
            if (discounts.minor > 0) add("− Discount ${MoneyFormat.format(discounts, false)}")
            if (rounding.minor != 0L) add((if (rounding.minor > 0) "+ " else "− ") + "Rounding ${MoneyFormat.format(rounding.abs(), false)}")
        }
        val formula = if (computed != null) parts.joinToString(" ") + " = ${MoneyFormat.format(computed)}" else "Not enough values to check"
        return Reconciliation(itemsSum, subtotal, base, taxesAdded, taxesIncluded, discounts, service, rounding, computed, total, difference, matches, formula)
    }

    // ---------------- currency ----------------

    private fun detectCurrency(text: String): Value<CurrencySpec> {
        val counts = mutableMapOf<String, Int>()
        val lower = text.lowercase()
        fun count(code: String, vararg pats: String) {
            val n = pats.sumOf { p -> Regex(p).findAll(lower).count() }
            if (n > 0) counts[code] = (counts[code] ?: 0) + n
        }
        count("PKR", "\\bpkr\\b", "\\brs\\.?(?=\\s?\\d)", "₨", "\\brupees?\\b", "روپے")
        count("INR", "\\binr\\b", "₹")
        count("USD", "\\busd\\b", "\\$\\s?\\d")
        count("EUR", "\\beur\\b", "€")
        count("GBP", "\\bgbp\\b", "£")
        count("AED", "\\baed\\b")
        count("SAR", "\\bsar\\b")
        count("KWD", "\\bkwd\\b")
        val best = counts.maxByOrNull { it.value }?.key
        return if (best != null) Value(Currencies.of(best), ValueSource.FROM_PHOTO) else Value(config.defaultCurrency, ValueSource.SUGGESTED)
    }
}
