package com.receiptscan.scanner.plant.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlantTest {
    private val labels = PlantPostprocess.parseLabelMap("0,background\n1,Rosa chinensis,China rose\n2,Ocimum basilicum,Basil\n3,Mentha spicata,Spearmint")

    @Test fun `top k excludes background and orders`() {
        val o = PlantPostprocess.topK(floatArrayOf(0.9f, 0.7f, 0.2f, 0.1f), labels) as PlantOutcome.Matches
        assertThat(o.candidates.map { it.label.commonName }).containsExactly("China rose", "Basil").inOrder()
        assertThat(o.suggestAnotherPhoto).isFalse()
    }

    @Test fun `low confidence is no match and close scores ask for another photo`() {
        assertThat(PlantPostprocess.topK(floatArrayOf(0.9f, 0.05f, 0.04f, 0.01f), labels)).isEqualTo(PlantOutcome.NoMatch)
        val o = PlantPostprocess.topK(floatArrayOf(0f, 0.4f, 0.38f, 0.1f), labels) as PlantOutcome.Matches
        assertThat(o.suggestAnotherPhoto).isTrue()
    }
}
