package com.receiptscan.feature.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.receiptscan.ocr.model.OcrPage
import java.io.File
import java.io.FileOutputStream

/** Page modes from S-PDF. */
enum class DocMode(val label: String) { DOCUMENT("Document"), WHITEBOARD("Whiteboard"), BOOK("Book") }

/**
 * Writes PDFs with Android's [PdfDocument]. For searchable output, each OCR line is drawn as text
 * first and the page image is drawn on top, so the text is present in the PDF content (selectable and
 * searchable) but hidden behind the image. Image-only PDFs are never labelled searchable.
 */
class PdfWriter(private val out: File) {
    private val doc = PdfDocument()
    private var pageNo = 0
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.SANS_SERIF }

    /** Adds one page sized to the image's aspect ratio at [widthPt] points wide (595 ≈ A4). */
    fun addPage(bitmap: Bitmap, ocr: OcrPage?, widthPt: Int = 595) {
        val heightPt = (widthPt * bitmap.height.toFloat() / bitmap.width).toInt().coerceIn(200, 2400)
        val info = PdfDocument.PageInfo.Builder(widthPt, heightPt, ++pageNo).create()
        val page = doc.startPage(info)
        val c = page.canvas
        if (ocr != null) {
            val sx = widthPt.toFloat() / ocr.width.coerceAtLeast(1)
            val sy = heightPt.toFloat() / ocr.height.coerceAtLeast(1)
            ocr.lines.forEach { line ->
                val b = line.box
                val h = (b.height * sy).coerceAtLeast(2f)
                textPaint.textSize = h * 0.85f
                textPaint.textScaleX = 1f
                val measured = textPaint.measureText(line.text).coerceAtLeast(1f)
                textPaint.textScaleX = ((b.width * sx) / measured).coerceIn(0.2f, 4f)
                c.drawText(line.text, b.left * sx, b.bottom * sy - h * 0.15f, textPaint)
            }
        }
        c.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), RectF(0f, 0f, widthPt.toFloat(), heightPt.toFloat()), imagePaint)
        doc.finishPage(page)
    }

    fun finish(): File {
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    fun abort() { doc.close(); out.delete() }
}

/** Mode-specific clean-up applied before writing a page. */
object PageProcessor {
    /** Book mode splits a two-page spread down the middle when the photo is landscape. */
    fun split(mode: DocMode, b: Bitmap): List<Bitmap> =
        if (mode == DocMode.BOOK && b.width > b.height * 1.15f) listOf(Bitmap.createBitmap(b, 0, 0, b.width / 2, b.height), Bitmap.createBitmap(b, b.width / 2, 0, b.width - b.width / 2, b.height))
        else listOf(b)

    fun clean(mode: DocMode, b: Bitmap, enhance: Boolean): Bitmap = when {
        !enhance -> b
        mode == DocMode.WHITEBOARD -> com.receiptscan.capture.Images.enhance(b, contrast = 1.8f)
        else -> com.receiptscan.capture.Images.enhance(b)
    }
}
