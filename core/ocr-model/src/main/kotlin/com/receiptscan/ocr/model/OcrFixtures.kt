package com.receiptscan.ocr.model

/**
 * Builds synthetic OCR pages from a simple text layout, for tests and previews.
 * Each input line becomes a row; "|" separates left and right-aligned cells.
 * This mimics ML Kit output geometry; it is not a substitute for real-photo evaluation.
 */
object SyntheticOcr {
    fun page(layout: String, sourceId: String = "test", pageIndex: Int = 0, lineHeight: Int = 30, width: Int = 600): OcrPage {
        val blocks = mutableListOf<OcrBlock>()
        layout.trimIndent().lines().forEachIndexed { i, raw ->
            if (raw.isBlank()) return@forEachIndexed
            val top = 20 + i * (lineHeight + 8)
            val bottom = top + lineHeight
            val cells = raw.split("|").map { it.trim() }
            cells.forEachIndexed { c, text ->
                if (text.isEmpty()) return@forEachIndexed
                val charW = 11
                val box = when {
                    cells.size == 1 -> Box(20, top, 20 + text.length * charW, bottom)
                    c == cells.lastIndex -> Box(width - 20 - text.length * charW, top, width - 20, bottom)
                    else -> {
                        val left = 20 + c * (width / cells.size)
                        Box(left, top, left + text.length * charW, bottom)
                    }
                }
                val line = OcrLine(text, box, elements = text.split(" ").map { OcrElement(it, box) })
                blocks += OcrBlock(text, box, listOf(line))
            }
        }
        return OcrPage(sourceId, pageIndex, width, 20 + layout.lines().size * (lineHeight + 8), blocks)
    }

    fun document(vararg layouts: String) = OcrDocument(
        pages = layouts.mapIndexed { i, l -> page(l, sourceId = "test-$i", pageIndex = i) },
        engine = "synthetic",
        engineVersion = "1",
    )
}
