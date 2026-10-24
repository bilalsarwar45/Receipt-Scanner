package com.receiptscan.ocr.model

/** A visual row: OCR lines whose vertical extents overlap, ordered left to right. */
data class LayoutRow(
    val page: OcrPage,
    val cells: List<OcrLine>,
) {
    val box: Box = cells.map { it.box }.reduce { a, b -> a.union(b) }
    val text: String get() = cells.joinToString("  ") { it.text }

    /** Text of every cell except the right-most one (usually the description part of an item row). */
    val leadingText: String get() = if (cells.size <= 1) cells.firstOrNull()?.text.orEmpty() else cells.dropLast(1).joinToString(" ") { it.text }
    val trailingCell: OcrLine get() = cells.last()
}

object Layout {
    /**
     * Groups lines into rows. ML Kit often returns the item name and its price as separate
     * lines/blocks; receipts need them re-joined by geometry rather than by reading order.
     * Elements (words) are used when a line spans a wide gap, so column cells separate properly.
     */
    fun rows(page: OcrPage, minOverlap: Float = 0.5f): List<LayoutRow> {
        val lines = page.lines.sortedWith(compareBy<OcrLine> { it.box.centerY }.thenBy { it.box.left })
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines) {
            val target = rows.lastOrNull()?.takeIf { row ->
                val rowBox = row.map { it.box }.reduce { a, b -> a.union(b) }
                val ref = row.maxBy { it.box.height }.box
                line.box.verticalOverlap(ref) >= minOverlap && line.box.verticalOverlap(rowBox) >= minOverlap
            }
            if (target != null) target.add(line) else rows.add(mutableListOf(line))
        }
        return rows.map { r -> LayoutRow(page, r.sortedBy { it.box.left }) }
    }

    fun rows(doc: OcrDocument): List<LayoutRow> = doc.pages.flatMap { rows(it) }
}

/**
 * Normalises recognised text for matching while the raw text is kept separately:
 * Unicode NFKC, unified dashes/spaces, collapsed whitespace.
 */
object TextNormalizer {
    fun normalize(text: String): String {
        val nfkc = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (c in nfkc) {
            sb.append(
                when (c) {
                    '−', '–', '—', '‒' -> '-'
                    ' ', ' ', ' ', '\t' -> ' '
                    '‘', '’' -> '\''
                    '“', '”' -> '"'
                    else -> c
                },
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    /** Lower-case, accents removed, punctuation stripped: used for keyword and alias matching. */
    fun key(text: String): String {
        val n = java.text.Normalizer.normalize(normalize(text).lowercase(), java.text.Normalizer.Form.NFD)
        return n.replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
