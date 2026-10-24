package com.receiptscan.capture

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Simple image measurements for capture guidance. These are heuristics and are never shown as scores. */
data class FrameQuality(
    /** Variance of a Laplacian over a small greyscale copy; low means blurry. */
    val sharpness: Double,
    /** Mean luminance 0–255. */
    val brightness: Double,
    /** Bounds of the bright paper-like region as fractions of the frame, or null when none stands out. */
    val paper: FloatRect?,
) {
    val isBlurry: Boolean get() = sharpness < BLUR_THRESHOLD
    val isDark: Boolean get() = brightness < 50
    val paperFillsFrame: Boolean get() = paper != null && paper.width * paper.height > 0.18f
    val paperTooSmall: Boolean get() = paper != null && paper.width * paper.height < 0.08f

    companion object { const val BLUR_THRESHOLD = 60.0 }
}

data class FloatRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

object QualityMeter {
    /** Measures a greyscale luminance plane of [w]×[h]. Works on CameraX Y planes and bitmaps alike. */
    fun measure(lum: ByteArray, w: Int, h: Int, rowStride: Int = w): FrameQuality {
        val step = max(1, max(w, h) / 160)
        val sw = w / step; val sh = h / step
        if (sw < 3 || sh < 3) return FrameQuality(0.0, 0.0, null)
        val g = IntArray(sw * sh)
        var sum = 0L
        for (y in 0 until sh) for (x in 0 until sw) {
            val v = lum[(y * step) * rowStride + x * step].toInt() and 0xFF
            g[y * sw + x] = v; sum += v
        }
        val mean = sum.toDouble() / g.size
        var lapSum = 0.0; var lapSq = 0.0; var n = 0
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val c = g[y * sw + x]
            val l = 4 * c - g[y * sw + x - 1] - g[y * sw + x + 1] - g[(y - 1) * sw + x] - g[(y + 1) * sw + x]
            lapSum += l; lapSq += l.toDouble() * l; n++
        }
        val lapMean = lapSum / n
        val variance = lapSq / n - lapMean * lapMean
        // Paper: pixels clearly brighter than the frame mean; take the bounds of the bright mass.
        val threshold = min(250.0, mean + 25)
        var minX = sw; var minY = sh; var maxX = -1; var maxY = -1; var count = 0
        for (y in 0 until sh) for (x in 0 until sw) if (g[y * sw + x] > threshold) {
            count++; if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val paper = if (count > g.size * 0.04 && maxX > minX && maxY > minY) {
            val box = FloatRect(minX / sw.toFloat(), minY / sh.toFloat(), (maxX + 1) / sw.toFloat(), (maxY + 1) / sh.toFloat())
            // Require the bright mass to fill its bounds reasonably (a sheet, not scattered highlights).
            val fill = count.toFloat() / ((maxX - minX + 1) * (maxY - minY + 1))
            if (fill > 0.45f) box else null
        } else null
        return FrameQuality(variance, mean, paper)
    }

    fun measure(bitmap: Bitmap): FrameQuality {
        val small = Images.scaleToMax(bitmap, 320)
        val px = IntArray(small.width * small.height)
        small.getPixels(px, 0, small.width, 0, 0, small.width, small.height)
        val lum = ByteArray(px.size) { i ->
            val c = px[i]
            (((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000).toByte()
        }
        return measure(lum, small.width, small.height)
    }

    /** True when two consecutive paper boxes are within [tolerance] of each other (camera held steady). */
    fun steady(a: FloatRect?, b: FloatRect?, tolerance: Float = 0.03f): Boolean =
        a != null && b != null && abs(a.left - b.left) < tolerance && abs(a.top - b.top) < tolerance &&
            abs(a.right - b.right) < tolerance && abs(a.bottom - b.bottom) < tolerance
}
