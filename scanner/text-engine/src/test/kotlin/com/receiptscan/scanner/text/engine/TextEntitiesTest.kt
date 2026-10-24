package com.receiptscan.scanner.text.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class TextEntitiesTest {
    @Test fun `finds actionable entities`() {
        val t = "Call 0300-1234567 or +92 42 3576 1234. Mail info@shop.pk, visit www.shop.pk. Meeting on 12 Nov 2026."
        val e = TextEntities.find(t, LocalDate.of(2026, 10, 24))
        assertThat(e.map { it.type }).containsExactly(EntityType.PHONE, EntityType.PHONE, EntityType.EMAIL, EntityType.URL, EntityType.DATE).inOrder()
        assertThat(e.first { it.type == EntityType.URL }.normalized).isEqualTo("https://www.shop.pk")
        assertThat(e.first().normalized).isEqualTo("03001234567")
    }

    @Test fun `script detection`() {
        assertThat(TextEntities.dominantScript("Hello world")).isEqualTo(Script.LATIN)
        assertThat(TextEntities.dominantScript("یہ اردو متن ہے")).isEqualTo(Script.ARABIC)
        assertThat(TextEntities.dominantScript("1234")).isEqualTo(Script.NONE)
    }

    @Test fun `occurrences`() {
        assertThat(TextEntities.occurrences("Milk milk MILK", "milk")).hasSize(3)
    }
}
