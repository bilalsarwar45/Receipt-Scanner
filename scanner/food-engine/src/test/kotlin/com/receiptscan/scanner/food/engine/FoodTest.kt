package com.receiptscan.scanner.food.engine

import com.google.common.truth.Truth.assertThat
import com.receiptscan.scanner.nutrition.engine.Nutrient
import org.junit.Test
import java.math.BigDecimal

class FoodTest {
    // Test fixture with round illustrative numbers; not real nutrition data.
    private val pack = FoodDatabase.parsePack(
        """
        {"packId":"test","version":"1","source":"fixture","license":"test","foods":[
          {"id":"a","name":"Test rice","source":"fixture","sourceId":"1","per100g":{"ENERGY_KCAL":"100","PROTEIN":"2"},"labels":["rice"],"portions":[{"label":"1 cup","grams":"150"}]},
          {"id":"b","name":"Test lentils","source":"fixture","sourceId":"2","per100g":{"ENERGY_KCAL":"120","PROTEIN":"9"},"labels":["dal"]}
        ]}
        """,
    )

    @Test fun `deterministic plate totals from confirmed portions`() {
        val db = FoodDatabase(pack)
        val est = PlateCalculator.estimate(listOf(PlateComponent(db.byId("a")!!, BigDecimal("150")), PlateComponent(db.byId("b")!!, BigDecimal("200"))))
        assertThat(est.kcal).isEqualTo(BigDecimal("390.0"))
        assertThat(est.totals[Nutrient.PROTEIN]).isEqualTo(BigDecimal("21.0"))
    }

    @Test fun `labels map to candidates and search works`() {
        val db = FoodDatabase(pack)
        assertThat(db.forLabel("rice").map { it.id }).containsExactly("a")
        assertThat(db.search("len").map { it.id }).containsExactly("b")
        assertThat(FoodDatabase(null).isInstalled).isFalse()
    }
}
