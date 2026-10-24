package com.receiptscan.ocr.model

import com.receiptscan.scanner.contracts.EvidenceRegion

/** Axis-aligned box in pixels of the processed page image. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerY: Float get() = (top + bottom) / 2f
    val centerX: Float get() = (left + right) / 2f

    fun union(o: Box) = Box(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    /** Fraction of the shorter box's height that overlaps vertically with [o]. */
    fun verticalOverlap(o: Box): Float {
        val overlap = minOf(bottom, o.bottom) - maxOf(top, o.top)
        val minH = minOf(height, o.height).coerceAtLeast(1)
        return overlap.coerceAtLeast(0).toFloat() / minH
    }
}

data class OcrElement(val text: String, val box: Box, val confidence: Float? = null)

data class OcrLine(
    val text: String,
    val box: Box,
    val elements: List<OcrElement> = emptyList(),
    /** Engine confidence when the engine reports one. Not a calibrated probability. */
    val confidence: Float? = null,
    /** BCP-47 tag the engine detected, if any. */
    val language: String? = null,
)

data class OcrBlock(val text: String, val box: Box, val lines: List<OcrLine>)

/**
 * Recognised text of one processed image. A long receipt has several pages/segments,
 * each kept separately with its own [sourceId] so evidence can always be traced back.
 */
data class OcrPage(
    val sourceId: String,
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val blocks: List<OcrBlock>,
) {
    val lines: List<OcrLine> get() = blocks.flatMap { it.lines }

    fun region(box: Box) = EvidenceRegion(sourceId, pageIndex, box.left, box.top, box.right, box.bottom)
}

/** Output of an OCR engine for one scan session. [engine] and [engineVersion] are kept for search-index metadata. */
data class OcrDocument(
    val pages: List<OcrPage>,
    val engine: String,
    val engineVersion: String,
    val script: String = "latin",
) {
    val fullText: String get() = pages.joinToString("\n\n") { p -> p.blocks.joinToString("\n") { it.text } }
    val lineCount: Int get() = pages.sumOf { it.lines.size }
    val isEmpty: Boolean get() = pages.all { it.lines.isEmpty() }
}
