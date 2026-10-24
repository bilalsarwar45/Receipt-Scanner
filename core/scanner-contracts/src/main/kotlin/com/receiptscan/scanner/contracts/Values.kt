package com.receiptscan.scanner.contracts

/**
 * Where a displayed value came from. Every field in a scanner result carries one,
 * so the UI can show the matching badge (handover section 4.4).
 */
enum class ValueSource {
    /** Read by OCR or a detector and not changed. */
    FROM_PHOTO,
    /** Typed or changed by the person. */
    EDITED,
    /** Derived from other values; [Value.formula] explains how. */
    CALCULATED,
    /** A model or rule guess that the person must accept before it counts. */
    SUGGESTED,
    /** An approximation such as food calories; always shown with "≈". */
    ESTIMATE,
}

/**
 * A rectangle in the source image, in pixels of the processed image of [pageIndex].
 * [sourceId] identifies the image/page the host stored; modules never pass bitmaps around.
 */
data class EvidenceRegion(
    val sourceId: String,
    val pageIndex: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(right >= left && bottom >= top) { "Invalid region" }
    }

    fun union(other: EvidenceRegion): EvidenceRegion {
        require(other.sourceId == sourceId && other.pageIndex == pageIndex) { "Regions from different pages" }
        return copy(
            left = minOf(left, other.left),
            top = minOf(top, other.top),
            right = maxOf(right, other.right),
            bottom = maxOf(bottom, other.bottom),
        )
    }
}

/** A value with its provenance. Editing always produces [ValueSource.EDITED] and keeps the evidence region. */
data class Value<out T>(
    val value: T,
    val source: ValueSource,
    val region: EvidenceRegion? = null,
    /** Human-readable derivation for CALCULATED values, e.g. "3,840.00 + 160.00 − 200.00". */
    val formula: String? = null,
    /** The original text this value was read from, when it was read from a photo. */
    val rawText: String? = null,
) {
    fun <R> edited(newValue: R): Value<R> = Value(newValue, ValueSource.EDITED, region, null, rawText)

    companion object {
        fun <T> fromPhoto(value: T, region: EvidenceRegion?, rawText: String? = null) = Value(value, ValueSource.FROM_PHOTO, region, rawText = rawText)
        fun <T> entered(value: T) = Value(value, ValueSource.EDITED)
        fun <T> calculated(value: T, formula: String) = Value(value, ValueSource.CALCULATED, formula = formula)
        fun <T> suggested(value: T, region: EvidenceRegion? = null) = Value(value, ValueSource.SUGGESTED, region)
        fun <T> estimate(value: T, formula: String? = null) = Value(value, ValueSource.ESTIMATE, formula = formula)
    }
}
