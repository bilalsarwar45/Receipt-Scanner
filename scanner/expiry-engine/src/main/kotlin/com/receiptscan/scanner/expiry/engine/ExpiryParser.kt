package com.receiptscan.scanner.expiry.engine

import com.receiptscan.ocr.model.OcrDocument
import com.receiptscan.scanner.contracts.EvidenceRegion
import com.receiptscan.time.DateOrder
import com.receiptscan.time.PrintedDateParser
import java.time.LocalDate

/** Manufacturing, expiry and best-before dates mean different things and are never interchanged. */
enum class DateKind(val label: String) {
    EXPIRY("Expires"),
    BEST_BEFORE("Best before"),
    USE_BY("Use by"),
    MANUFACTURED("Manufactured"),
    PACKED("Packed"),
    UNKNOWN("Date"),
}

data class DateCandidate(
    val kind: DateKind,
    val date: LocalDate,
    val raw: String,
    /** Other readings of the same text (e.g. 04/05 as 4 May or 5 April). */
    val alternatives: List<LocalDate>,
    val monthOnly: Boolean,
    val yearInferred: Boolean,
    val region: EvidenceRegion?,
)

data class ExpiryResult(
    val candidates: List<DateCandidate>,
    /** The date that should drive a reminder, only when it is an expiry-type date and unambiguous. */
    val primary: DateCandidate?,
    val needsChoice: Boolean,
    val message: String?,
)

object ExpiryParser {
    private val kinds = listOf(
        DateKind.USE_BY to Regex("(?i)\\buse\\s*by\\b"),
        DateKind.BEST_BEFORE to Regex("(?i)\\b(best\\s*before|bb|bbe|best\\s*by)\\b"),
        DateKind.EXPIRY to Regex("(?i)\\b(exp(?:iry|ires|\\.)?|expiration|exp\\s*date|ed)\\b"),
        DateKind.MANUFACTURED to Regex("(?i)\\b(mfg|mfd|manufactured|manufacturing|mfg\\.?\\s*date|prod(?:uction)?(?:\\s*date)?|made\\s*on)\\b"),
        DateKind.PACKED to Regex("(?i)\\b(pkd|packed|packing\\s*date|pack\\s*date)\\b"),
    )

    fun parse(doc: OcrDocument, today: LocalDate, order: DateOrder = DateOrder.DMY): ExpiryResult {
        val candidates = mutableListOf<DateCandidate>()
        doc.pages.forEach { page ->
            val lines = page.lines
            lines.forEachIndexed { i, line ->
                val dates = PrintedDateParser.findAll(line.text, today, order)
                if (dates.isEmpty()) return@forEachIndexed
                dates.forEach { d ->
                    val before = line.text.substring(0, line.text.indexOf(d.raw).coerceAtLeast(0))
                    val kind = kinds.firstOrNull { it.second.containsMatchIn(before) }?.first
                        ?: kinds.firstOrNull { it.second.containsMatchIn(line.text) }?.first
                        ?: lines.getOrNull(i - 1)?.let { prev -> kinds.firstOrNull { it.second.containsMatchIn(prev.text) && PrintedDateParser.findAll(prev.text, today).isEmpty() }?.first }
                        ?: DateKind.UNKNOWN
                    candidates += DateCandidate(kind, d.date, d.raw, d.alternatives, d.monthOnly, d.yearInferred, page.region(line.box))
                }
            }
        }
        val expiryLike = candidates.filter { it.kind in setOf(DateKind.EXPIRY, DateKind.BEST_BEFORE, DateKind.USE_BY) }
        val primary = expiryLike.singleOrNull() ?: expiryLike.maxByOrNull { it.date }.takeIf { expiryLike.map { it.date }.distinct().size == 1 }
        val message = when {
            candidates.isEmpty() -> "No date found. Get closer to the printed date."
            expiryLike.isEmpty() && candidates.any { it.kind == DateKind.MANUFACTURED || it.kind == DateKind.PACKED } ->
                "Only a manufacturing or packing date was found. Expiry can't be worked out without the shelf life."
            expiryLike.isEmpty() -> "We found a date but not what it means. Pick the kind of date."
            primary?.alternatives?.isNotEmpty() == true -> "This date can be read two ways."
            primary == null -> "Several expiry dates were found. Pick the right one."
            primary.yearInferred -> "The year wasn't printed fully. Check it."
            else -> null
        }
        val needsChoice = primary == null || primary.alternatives.isNotEmpty() || primary.yearInferred
        return ExpiryResult(candidates, primary, needsChoice, message)
    }

    /** Expiry from a manufacturing date, only with a shelf life the person confirmed. */
    fun expiryFromShelfLife(manufactured: LocalDate, confirmedShelfLife: java.time.Period): LocalDate = manufactured.plus(confirmedShelfLife)
}
