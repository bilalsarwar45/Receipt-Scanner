package com.receiptscan.scanner.nutrition.engine

import com.google.common.truth.Truth.assertThat
import com.receiptscan.ocr.model.SyntheticOcr
import org.junit.Test
import java.math.BigDecimal

class NutritionTest {
    private val label = """
        Nutrition Information
        Serving size 30 g   Servings per pack 10
        | Per 100 g | Per serving
        Energy 1650 kJ / 395 kcal | 495 kJ / 119 kcal
        Fat | 12.5 g | 3.8 g
        of which saturates | 5 g | 1.5 g
        Carbohydrate | 60 g | 18 g
        of which sugars | 22 g | 6.6 g
        Protein | 8 g | 2.4 g
        Salt | 0.9 g | 0.27 g
        Ingredients: wheat flour, sugar, palm oil, whey powder, salt.
        Contains: wheat, milk.
    """

    @Test fun `parses per 100 and per serving columns`() {
        val l = NutritionLabelParser.parse(SyntheticOcr.document(label))
        assertThat(l.servingSize).isEqualTo(BigDecimal("30"))
        assertThat(l.servingsPerPack).isEqualTo(BigDecimal("10"))
        assertThat(l.per100(Nutrient.FAT)?.amount).isEqualTo(BigDecimal("12.5"))
        assertThat(l.perServing(Nutrient.FAT)?.amount).isEqualTo(BigDecimal("3.8"))
        assertThat(l.per100(Nutrient.SATURATED_FAT)?.amount).isEqualTo(BigDecimal("5"))
        assertThat(l.per100(Nutrient.SUGARS)?.amount).isEqualTo(BigDecimal("22"))
        assertThat(l.per100(Nutrient.ENERGY_KCAL)?.amount).isEqualTo(BigDecimal("395"))
        assertThat(l.perServing(Nutrient.ENERGY_KCAL)?.amount).isEqualTo(BigDecimal("119"))
        assertThat(l.ingredients).isEqualTo("wheat flour, sugar, palm oil, whey powder, salt")
        assertThat(l.allergenStatement).isEqualTo("Contains: wheat, milk")
    }

    @Test fun `exact calculation for a confirmed quantity and the whole pack`() {
        val l = NutritionLabelParser.parse(SyntheticOcr.document(label))
        val calc = NutritionCalculator.forQuantity(l, BigDecimal("45")).associateBy { it.nutrient }
        assertThat(calc.getValue(Nutrient.FAT).amount).isEqualTo(BigDecimal("5.62")) // 12.5 × 45 / 100 = 5.625, rounded half-even
        assertThat(calc.getValue(Nutrient.ENERGY_KCAL).amount).isEqualTo(BigDecimal("177.75"))
        val pack = NutritionCalculator.forPack(l).associateBy { it.nutrient }
        assertThat(pack.getValue(Nutrient.PROTEIN).amount).isEqualTo(BigDecimal("24"))
    }

    @Test fun `ingredient highlighting never claims absence`() {
        val m = IngredientMatcher.find("wheat flour, sugar, whey powder", listOf("milk", "nuts"))
        assertThat(m[0].found).isTrue() // whey
        assertThat(m[1].found).isFalse()
        assertThat(IngredientMatcher.NOT_FOUND_NOTE).contains("doesn't mean")
    }
}
