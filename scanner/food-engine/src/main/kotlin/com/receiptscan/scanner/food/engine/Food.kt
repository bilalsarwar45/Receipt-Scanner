package com.receiptscan.scanner.food.engine

import com.receiptscan.scanner.nutrition.engine.Nutrient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

/** A household measure for a food, e.g. "1 cup cooked" = 158 g, taken from the dataset. */
@Serializable
data class Portion(val label: String, val grams: String)

/**
 * One food in the offline nutrition pack. Values are per 100 g as published by the source
 * (USDA FoodData Central for the default pack); [sourceId] keeps the FDC id for provenance.
 */
@Serializable
data class FoodRecord(
    val id: String,
    val name: String,
    val source: String,
    val sourceId: String,
    val per100g: Map<String, String>,
    val portions: List<Portion> = emptyList(),
    /** Classifier labels that may show this food, from the reviewed label map. */
    val labels: List<String> = emptyList(),
) {
    fun value(n: Nutrient): BigDecimal? = per100g[n.name]?.toBigDecimal()
}

@Serializable
data class FoodPack(val packId: String, val version: String, val source: String, val license: String, val foods: List<FoodRecord>)

/** Lookup over the installed pack plus foods the person entered themselves. */
class FoodDatabase(private val pack: FoodPack?, private val custom: List<FoodRecord> = emptyList()) {
    val all: List<FoodRecord> get() = (pack?.foods.orEmpty()) + custom
    val isInstalled: Boolean get() = pack != null

    fun search(query: String, limit: Int = 20): List<FoodRecord> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.filter { it.name.lowercase().contains(q) }
            .sortedWith(compareBy<FoodRecord> { !it.name.lowercase().startsWith(q) }.thenBy { it.name.length })
            .take(limit)
    }

    /** Foods mapped to a classifier label. A label alone never fixes the nutrition: the person picks. */
    fun forLabel(label: String): List<FoodRecord> = all.filter { label.lowercase() in it.labels.map(String::lowercase) }

    fun byId(id: String) = all.firstOrNull { it.id == id }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parsePack(text: String): FoodPack = json.decodeFromString(FoodPack.serializer(), text)
    }
}

/** A food on the plate with a portion the person confirmed. */
data class PlateComponent(val food: FoodRecord, val grams: BigDecimal, val note: String? = null)

data class PlateEstimate(val totals: Map<Nutrient, BigDecimal>, val lines: List<Pair<PlateComponent, Map<Nutrient, BigDecimal>>>, val missing: List<String>) {
    /** Calories are always estimates: portion mass, hidden oil and recipes are not visible in a photo. */
    val kcal: BigDecimal? get() = totals[Nutrient.ENERGY_KCAL]
}

object PlateCalculator {
    private val tracked = listOf(Nutrient.ENERGY_KCAL, Nutrient.PROTEIN, Nutrient.CARBOHYDRATE, Nutrient.FAT, Nutrient.FIBRE, Nutrient.SUGARS)

    fun estimate(components: List<PlateComponent>): PlateEstimate {
        val totals = mutableMapOf<Nutrient, BigDecimal>()
        val missing = mutableListOf<String>()
        val lines = components.map { c ->
            require(c.grams.signum() > 0) { "Portion must be more than 0 g" }
            val m = tracked.mapNotNull { n ->
                val per100 = c.food.value(n)
                if (per100 == null) { if (n == Nutrient.ENERGY_KCAL) missing += c.food.name; null }
                else n to per100.multiply(c.grams).divide(BigDecimal(100), 1, RoundingMode.HALF_EVEN)
            }.toMap()
            m.forEach { (n, v) -> totals[n] = (totals[n] ?: BigDecimal.ZERO) + v }
            c to m
        }
        return PlateEstimate(totals, lines, missing)
    }
}
