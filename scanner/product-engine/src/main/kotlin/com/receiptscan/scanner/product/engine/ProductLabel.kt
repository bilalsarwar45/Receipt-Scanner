package com.receiptscan.scanner.product.engine

import com.receiptscan.ocr.model.OcrDocument
import com.receiptscan.scanner.contracts.Value
import java.time.LocalDate
import java.time.Period

/** Identifiers read from a product sticker. The person confirms identity; nothing is looked up online by default. */
data class ProductLabelResult(
    val brandOrName: Value<String>?,
    val model: Value<String>?,
    val serial: Value<String>?,
    val barcode: Value<String>?,
    val warrantyText: Value<String>?,
    val warrantyPeriod: Period?,
    val returnPeriod: Period?,
    val rawText: String,
)

object ProductLabelParser {
    private val model = Regex("""(?i)\b(?:model|mdl|model no|m/n|type)\s*[:#.]?\s*([A-Z0-9][A-Z0-9\-/.]{2,24})""")
    private val serial = Regex("""(?i)\b(?:serial|s/n|sn|serial no|imei)\s*[:#.]?\s*([A-Z0-9][A-Z0-9\-]{3,30})""")
    private val ean = Regex("""(?<!\d)(\d{8}|\d{12,14})(?!\d)""")
    private val warranty = Regex("""(?i)(\d{1,2})\s*(year|yr|month|mo|day)s?\s*(?:limited\s*)?(warranty|guarantee)""")
    private val returns = Regex("""(?i)(\d{1,3})\s*[- ]?(day|week)s?\s*(?:return|exchange|refund)""")

    fun parse(doc: OcrDocument): ProductLabelResult {
        val lines = doc.pages.flatMap { p -> p.lines.map { p to it } }
        fun find(rx: Regex): Value<String>? = lines.firstNotNullOfOrNull { (p, l) -> rx.find(l.text)?.let { Value.fromPhoto(it.groupValues[1], p.region(l.box), l.text) } }
        val barcode = lines.firstNotNullOfOrNull { (p, l) ->
            if (serial.containsMatchIn(l.text)) null else ean.find(l.text)?.let { Value.fromPhoto(it.value, p.region(l.box), l.text) }
        }
        val w = lines.firstNotNullOfOrNull { (p, l) -> warranty.find(l.text)?.let { it to Value.fromPhoto(it.value, p.region(l.box), l.text) } }
        val r = lines.firstNotNullOfOrNull { (_, l) -> returns.find(l.text) }
        val name = lines.take(3).firstOrNull { (_, l) -> l.text.count { it.isLetter() } >= 3 && !model.containsMatchIn(l.text) && !serial.containsMatchIn(l.text) }
            ?.let { (p, l) -> Value.fromPhoto(l.text.trim(), p.region(l.box), l.text) }
        return ProductLabelResult(
            brandOrName = name,
            model = find(model),
            serial = find(serial),
            barcode = barcode,
            warrantyText = w?.second,
            warrantyPeriod = w?.first?.let { periodOf(it.groupValues[1].toInt(), it.groupValues[2]) },
            returnPeriod = r?.let { periodOf(it.groupValues[1].toInt(), it.groupValues[2]) },
            rawText = doc.fullText,
        )
    }

    fun periodOf(n: Int, unit: String): Period = when (unit.lowercase().take(2)) {
        "ye", "yr" -> Period.ofYears(n)
        "mo" -> Period.ofMonths(n)
        "we" -> Period.ofWeeks(n)
        else -> Period.ofDays(n)
    }
}

/**
 * Warranty and return dates from a purchase date and terms that came from evidence or the person.
 * Terms are never invented: without a period there is no date.
 */
data class WarrantyTerms(val purchaseDate: LocalDate, val warranty: Period?, val returnWindow: Period?) {
    /** Last day of the warranty: purchase + period − 1 day is not used; the handover shows 22 Oct 2026 + 12 months = 22 Oct 2027. */
    val warrantyUntil: LocalDate? get() = warranty?.let { purchaseDate.plus(it) }
    val returnBy: LocalDate? get() = returnWindow?.let { purchaseDate.plus(it) }

    fun daysLeft(date: LocalDate?, today: LocalDate): Long? = date?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
}
