package com.receiptscan.scanner.counter.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CounterTest {
    private fun d(l: Float, t: Float, s: Float = 0.9f, label: String = "bottle") = Detection(NRect(l, t, l + 0.1f, t + 0.1f), label, s)

    @Test fun `nms removes overlaps and region and class filters apply`() {
        val dets = listOf(d(0.1f, 0.1f), d(0.11f, 0.11f, 0.8f), d(0.5f, 0.5f), d(0.8f, 0.8f, label = "cup"), d(0.3f, 0.3f, 0.2f))
        val kept = DetectionFilter.filter(dets, setOf("bottle"), NRect(0f, 0f, 0.7f, 0.7f))
        assertThat(kept).hasSize(2)
        assertThat(DetectionFilter.filter(emptyList(), null, null)).isEmpty()
    }

    @Test fun `markers stay aligned after rotation and crop`() {
        val t = ImageTransform(90, NRect(0f, 0f, 0.5f, 1f))
        // A box at the top-left of the sensor image appears at the top-right after 90° rotation, then the crop keeps the left half.
        assertThat(t.map(NRect(0f, 0f, 0.1f, 0.1f))).isNull()
        val mapped = ImageTransform(90).map(NRect(0f, 0f, 0.1f, 0.1f))!!
        assertThat(mapped.left).isWithin(1e-6f).of(0.9f)
        assertThat(mapped.top).isWithin(1e-6f).of(0f)
        val back = ImageTransform(180).map(NRect(0.2f, 0.3f, 0.4f, 0.5f))!!
        assertThat(back.left).isWithin(1e-6f).of(0.6f)
        assertThat(back.top).isWithin(1e-6f).of(0.5f)
    }

    @Test fun `manual corrections and undo`() {
        val s = CountSession(listOf(d(0.1f, 0.1f), d(0.5f, 0.5f)))
        assertThat(s.count).isEqualTo(2)
        s.add(0.9f, 0.9f)
        assertThat(s.removeNear(0.15f, 0.15f)).isTrue()
        assertThat(s.count).isEqualTo(2)
        assertThat(s.added).isEqualTo(1)
        assertThat(s.removed).isEqualTo(1)
        s.undo(); s.undo()
        assertThat(s.count).isEqualTo(2)
        assertThat(s.added).isEqualTo(0)
    }

    @Test fun `cap at 500`() {
        val many = List(600) { d((it % 30) / 30f, (it / 30) / 30f) }
        val s = CountSession(many)
        assertThat(s.tooMany).isTrue()
        assertThat(s.count).isEqualTo(500)
        assertThat(s.add(0.5f, 0.5f)).isFalse()
    }
}
