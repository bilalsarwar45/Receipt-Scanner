package com.receiptscan.scanner.counter.engine

import kotlin.math.max
import kotlin.math.min

/** Normalised rectangle (0..1) in the upright, cropped image the person sees. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = max(0f, width) * max(0f, height)
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun iou(o: NRect): Float {
        val inter = NRect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom)).let { if (it.width <= 0 || it.height <= 0) 0f else it.area }
        val union = area + o.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}

data class Detection(val box: NRect, val label: String, val score: Float)

/** Image orientation/crop applied between the model input and the screen. */
data class ImageTransform(val rotationDegrees: Int, val crop: NRect = NRect(0f, 0f, 1f, 1f)) {
    init { require(rotationDegrees in setOf(0, 90, 180, 270)) }

    /** Maps a box from the raw sensor image (normalised) into the upright cropped view. Returns null when fully cropped out. */
    fun map(b: NRect): NRect? {
        val r = when (rotationDegrees) {
            90 -> NRect(1 - b.bottom, b.left, 1 - b.top, b.right)
            180 -> NRect(1 - b.right, 1 - b.bottom, 1 - b.left, 1 - b.top)
            270 -> NRect(b.top, 1 - b.right, b.bottom, 1 - b.left)
            else -> b
        }
        val l = (r.left - crop.left) / crop.width
        val t = (r.top - crop.top) / crop.height
        val rr = (r.right - crop.left) / crop.width
        val bb = (r.bottom - crop.top) / crop.height
        val clipped = NRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), rr.coerceIn(0f, 1f), bb.coerceIn(0f, 1f))
        return if (clipped.width <= 0f || clipped.height <= 0f) null else clipped
    }
}

object DetectionFilter {
    /**
     * Keeps detections of the chosen classes whose centre lies in [region], above [minScore],
     * then applies class-wise non-maximum suppression.
     */
    fun filter(detections: List<Detection>, classes: Set<String>?, region: NRect?, minScore: Float = 0.4f, iouThreshold: Float = 0.5f): List<Detection> {
        val candidates = detections.filter { d ->
            d.score >= minScore && (classes == null || d.label in classes) && (region == null || region.contains(d.box.cx, d.box.cy))
        }.sortedByDescending { it.score }
        val kept = mutableListOf<Detection>()
        for (d in candidates) {
            if (kept.none { it.label == d.label && it.box.iou(d.box) > iouThreshold }) kept += d
        }
        return kept
    }
}

data class Marker(val id: Int, val x: Float, val y: Float, val fromDetector: Boolean)

/**
 * Editable count. Detector markers are a starting point; the person adds or removes markers and the
 * confirmed count is what gets saved, alongside the detected count and the corrections.
 */
class CountSession(detections: List<Detection>, val cap: Int = 500) {
    private var nextId = 0
    private val markers = detections.take(cap).map { Marker(nextId++, it.box.cx, it.box.cy, true) }.toMutableList()
    private val history = ArrayDeque<List<Marker>>()
    val detectedCount: Int = detections.size.coerceAtMost(cap)
    val tooMany: Boolean = detections.size > cap

    val current: List<Marker> get() = markers.toList()
    val count: Int get() = markers.size
    val added: Int get() = markers.count { !it.fromDetector }
    val removed: Int get() = detectedCount - markers.count { it.fromDetector }

    fun add(x: Float, y: Float): Boolean {
        if (markers.size >= cap) return false
        snapshot(); markers += Marker(nextId++, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), false); return true
    }

    /** Removes the marker nearest to (x, y) within [radius]. */
    fun removeNear(x: Float, y: Float, radius: Float = 0.04f): Boolean {
        val m = markers.minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) } ?: return false
        if ((m.x - x) * (m.x - x) + (m.y - y) * (m.y - y) > radius * radius) return false
        snapshot(); markers.remove(m); return true
    }

    fun undo(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        markers.clear(); markers += prev; return true
    }

    val canUndo get() = history.isNotEmpty()

    private fun snapshot() { history.addLast(markers.toList()); if (history.size > 100) history.removeFirst() }
}
