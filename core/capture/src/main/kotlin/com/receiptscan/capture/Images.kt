package com.receiptscan.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Thrown when an imported file cannot be decoded. Maps to FailReason.UNREADABLE_FILE. */
class UnreadableImageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Image decoding and clean-up helpers. Everything runs on [Dispatchers.Default]/IO and never on the main thread. */
object Images {
    /** Long edge used for OCR. ML Kit recommends at least 16 px per character; 2400 px keeps receipts legible. */
    const val OCR_MAX_EDGE = 2400

    /** Decodes [uri] (content:// or file://), applies EXIF orientation and downsamples to [maxEdge]. */
    suspend fun load(context: Context, uri: Uri, maxEdge: Int = OCR_MAX_EDGE): Bitmap = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: throw UnreadableImageException("Cannot open $uri")
        } catch (e: SecurityException) {
            throw UnreadableImageException("No permission to read the file", e)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw UnreadableImageException("Not an image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw UnreadableImageException("Could not decode image")
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        scaleToMax(applyOrientation(decoded, orientation), maxEdge)
    }

    suspend fun load(file: File, maxEdge: Int = OCR_MAX_EDGE): Bitmap = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) throw UnreadableImageException("Not an image")
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw UnreadableImageException("Could not decode image")
        val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        scaleToMax(applyOrientation(bmp, orientation), maxEdge)
    }

    fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun scaleToMax(src: Bitmap, maxEdge: Int): Bitmap {
        val edge = max(src.width, src.height)
        if (edge <= maxEdge) return src
        val f = maxEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(src, (src.width * f).roundToInt().coerceAtLeast(1), (src.height * f).roundToInt().coerceAtLeast(1), true)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Perspective correction: maps the quadrilateral [corners] (TL, TR, BR, BL in bitmap pixels)
     * to an upright rectangle using [Matrix.setPolyToPoly].
     */
    fun warp(src: Bitmap, corners: List<PointF>): Bitmap {
        require(corners.size == 4)
        val (tl, tr, br, bl) = corners
        val w = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y)).roundToInt().coerceAtLeast(16)
        val h = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y)).roundToInt().coerceAtLeast(16)
        val srcPts = floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y)
        val dst = floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(srcPts, 0, dst, 0, 4)) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** "Enhance": greyscale with raised contrast, which helps OCR on thermal receipts. */
    fun enhance(src: Bitmap, contrast: Float = 1.35f): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(0f) }
        val t = (-0.5f * contrast + 0.5f) * 255f
        cm.postConcat(ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        return out
    }

    /** Stacks long-receipt parts vertically for the preview/export image. OCR still runs per part. */
    fun stitchVertical(parts: List<Bitmap>): Bitmap {
        require(parts.isNotEmpty())
        val width = parts.maxOf { it.width }
        val height = parts.sumOf { (it.height * width.toFloat() / it.width).roundToInt() }
        val out = Bitmap.createBitmap(width, height.coerceAtMost(16_000), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        var y = 0f
        parts.forEach { p ->
            val scale = width.toFloat() / p.width
            val m = Matrix().apply { postScale(scale, scale); postTranslate(0f, y) }
            c.drawBitmap(p, m, Paint(Paint.FILTER_BITMAP_FLAG))
            y += p.height * scale
        }
        return out
    }

    fun crop(src: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        val l = left.coerceIn(0, src.width - 1); val t = top.coerceIn(0, src.height - 1)
        val r = right.coerceIn(l + 1, src.width); val b = bottom.coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    suspend fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = 90): File = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        file
    }

    /** Renders PDF pages to bitmaps (PdfRenderer). Large PDFs are capped at [maxPages]. */
    suspend fun renderPdf(context: Context, uri: Uri, maxPages: Int = 20, targetWidth: Int = 1800): List<Bitmap> = withContext(Dispatchers.IO) {
        val pfd: ParcelFileDescriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r") ?: throw UnreadableImageException("Cannot open PDF")
        } catch (e: SecurityException) {
            throw UnreadableImageException("No permission to read the file", e)
        }
        pfd.use { fd ->
            val renderer = try { PdfRenderer(fd) } catch (e: Exception) { throw UnreadableImageException("Not a readable PDF (it may be password protected)", e) }
            renderer.use { r ->
                (0 until minOf(r.pageCount, maxPages)).map { i ->
                    r.openPage(i).use { page ->
                        val scale = targetWidth.toFloat() / page.width
                        val bmp = Bitmap.createBitmap(targetWidth, (page.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }
    }
}
