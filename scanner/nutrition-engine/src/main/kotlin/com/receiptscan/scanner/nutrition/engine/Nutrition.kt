package com.receiptscan.scanner.nutrition.engine

import com.receiptscan.ocr.model.Layout
import com.receiptscan.ocr.model.OcrDocument
import com.receiptscan.scanner.contracts.EvidenceRegion
import java.math.BigDecimal
import java.math.RoundingMode

enum class Nutrient(val label: String, val unit: String) {
    ENERGY_KCAL("Energy", "kcal"), ENERGY_KJ("Energy", "kJ"), FAT("Fat", "g"), SATURATED_FAT("Saturated fat", "g"),
    TRANS_FAT("Trans fat", "g"), CHOLESTEROL("Cholesterol", "mg"), SODIUM("Sodium", "mg"), SALT("Salt", "g"),
    CARBOHYDRATE("Carbohydrate", "g"), SUGARS("Sugars", "g"), ADDED_SUGARS("Added sugars", "g"), FIBRE("Fibre", "g"), PROTEIN("Protein", "g"),
}

/** Which amount a printed column refers to. */
sealed interface Basis {
    data class Per100(val unit: String) : Basis // "g" or "ml"
    data class PerServing(val servingSize: BigDecimal?, val unit: String?) : Basis
    data object PerPack : Basis
}

/** One value exactly as printed in one column. */
data class LabelValue(val nutrient: Nutrient, val basis: Basis, val amount: BigDecimal, val unit: String, val raw: String, val region: EvidenceRegion?)

data class NutritionLabel(
    val values: List<LabelValue>,
    val servingSize: BigDecimal?,
    val servingUnit: String?,
    val servingsPerPack: BigDecimal?,
    val ingredients: String?,
    val allergenStatement: String?,
    val rawText: String,
) {
    fun per100(n: Nutrient) = values.firstOrNull { it.nutrient == n && it.basis is Basis.Per100 }
    fun perServing(n: Nutrient) = values.firstOrNull { it.nutrient == n && it.basis is Basis.PerServing }
}

/** A value calculated from label facts and a confirmed quantity, with its formula. */
data class CalculatedValue(val nutrient: Nutrient, val amount: BigDecimal, val unit: String, val formula: String)

object NutritionLabelParser {
    private val nutrientPatterns: List<Pair<Nutrient, Regex>> = listOf(
        Nutrient.SATURATED_FAT to Regex("(?i)(saturated|sat\\.?\\s*fat|of which saturates|saturates)"),
        Nutrient.TRANS_FAT to Regex("(?i)trans"),
        Nutrient.ADDED_SUGARS to Regex("(?i)added sugars?"),
        Nutrient.SUGARS to Regex("(?i)(sugars?|of which sugars)"),
        Nutrient.FIBRE to Regex("(?i)(fib(re|er)|dietary fib)"),
        Nutrient.CARBOHYDRATE to Regex("(?i)(carbohydrates?|carbs|total carb)"),
        Nutrient.PROTEIN to Regex("(?i)protein"),
        Nutrient.CHOLESTEROL to Regex("(?i)cholesterol"),
        Nutrient.SODIUM to Regex("(?i)sodium"),
        Nutrient.SALT to Regex("(?i)\\bsalt\\b"),
        Nutrient.FAT to Regex("(?i)(total fat|\\bfat\\b|lipids?)"),
        Nutrient.ENERGY_KCAL to Regex("(?i)(energy|calories|kcal)"),
    )
    private val valueRx = Regex("""(?i)(<\s*)?(\d+(?:[.,]\d+)?)\s*(kcal|kj|mg|mcg|µg|g|ml)?\b""")

    fun parse(doc: OcrDocument): NutritionLabel {
        val rows = Layout.rows(doc)
        val text = doc.fullText
        // Column headers
        var columns: List<Basis> = emptyList()
        var servingSize: BigDecimal? = null
        var servingUnit: String? = null
        Regex("(?i)serving size[^\\d]*(\\d+(?:[.,]\\d+)?)\\s*(g|ml)").find(text)?.let { servingSize = it.groupValues[1].replace(',', '.').toBigDecimal(); servingUnit = it.groupValues[2].lowercase() }
        val perServingParen = Regex("(?i)per serving\\s*\\(?\\s*(\\d+(?:[.,]\\d+)?)\\s*(g|ml)").find(text)
        if (servingSize == null && perServingParen != null) { servingSize = perServingParen.groupValues[1].replace(',', '.').toBigDecimal(); servingUnit = perServingParen.groupValues[2].lowercase() }
        val servings = Regex("(?i)servings? per (?:pack|container|package)[^\\d]*(\\d+(?:[.,]\\d+)?)").find(text)?.groupValues?.get(1)?.replace(',', '.')?.toBigDecimal()

        for (r in rows) {
            val t = r.text.lowercase().replace(Regex("servings? per (pack|package|container)"), " ").replace(Regex("serving size"), " ")
            val found = mutableListOf<Pair<Int, Basis>>()
            Regex("per\\s*100\\s*(g|ml)").findAll(t).forEach { found += it.range.first to Basis.Per100(it.groupValues[1]) }
            Regex("per\\s*(serving|portion|serve)").findAll(t).forEach { found += it.range.first to Basis.PerServing(servingSize, servingUnit) }
            Regex("per\\s*(pack|package)").findAll(t).forEach { found += it.range.first to Basis.PerPack }
            if (found.isNotEmpty()) { columns = found.sortedBy { it.first }.map { it.second }; break }
        }
        if (columns.isEmpty()) columns = listOf(if (servingSize != null) Basis.PerServing(servingSize, servingUnit) else Basis.Per100("g"))

        val values = mutableListOf<LabelValue>()
        for (r in rows) {
            val t = r.text
            val label = t.takeWhile { !it.isDigit() && it != '<' }
            val nutrient = nutrientPatterns.firstOrNull { it.second.containsMatchIn(label) }?.first ?: continue
            if (Regex("(?i)per\\s*(100|serving)").containsMatchIn(t) && t.count { it.isDigit() } <= 3 && nutrient != Nutrient.ENERGY_KCAL) continue
            val nums = valueRx.findAll(t.substring(label.length)).filter { m -> m.groupValues[3].lowercase() != "%" }.toList()
                .filterNot { m -> t.substring(label.length).getOrNull(m.range.last + 1) == '%' }
            if (nums.isEmpty()) continue
            var col = 0
            nums.forEach { m ->
                val unit = m.groupValues[3].lowercase().ifEmpty { nutrient.unit.lowercase() }.let { if (it == "µg") "mcg" else it }
                val n = if (nutrient == Nutrient.ENERGY_KCAL && unit == "kj") Nutrient.ENERGY_KJ else nutrient
                // Energy rows often print kJ and kcal for each column; keep column index per unit.
                val idx = if (nutrient == Nutrient.ENERGY_KCAL) values.count { it.nutrient == n && it.raw == t } else col
                val basis = columns.getOrNull(idx) ?: return@forEach
                values += LabelValue(n, basis, m.groupValues[2].replace(',', '.').toBigDecimal(), unit, t, r.page.region(r.box))
                if (nutrient != Nutrient.ENERGY_KCAL) col++
            }
        }
        val ingredients = Regex("(?is)ingredients?\\s*[:\\-]\\s*(.+?)(?=allergen|contains\\s*:|may contain|nutrition|$)").find(text)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()?.trimEnd('.')
        val allergens = Regex("(?is)((?:allergens?|contains|may contain)\\s*[:\\-]?\\s*[^\\n.]+)").find(text)?.groupValues?.get(1)?.trim()
        return NutritionLabel(values.distinctBy { Triple(it.nutrient, it.basis, it.amount) }, servingSize, servingUnit, servings, ingredients, allergens, text)
    }
}

object NutritionCalculator {
    /**
     * Values for a confirmed [quantity] (g or ml) from per-100 label facts: value × quantity ÷ 100.
     * Falls back to per-serving facts × (quantity ÷ serving size) when only those are printed.
     */
    fun forQuantity(label: NutritionLabel, quantity: BigDecimal): List<CalculatedValue> {
        require(quantity.signum() > 0) { "Quantity must be more than 0" }
        val out = mutableListOf<CalculatedValue>()
        Nutrient.entries.forEach { n ->
            val p100 = label.per100(n)
            val ps = label.perServing(n)
            when {
                p100 != null -> out += CalculatedValue(n, p100.amount.multiply(quantity).divide(BigDecimal(100), 2, RoundingMode.HALF_EVEN).stripTrailingZeros(), p100.unit,
                    "${p100.amount.toPlainString()} ${p100.unit} per 100 × ${quantity.toPlainString()} ÷ 100")
                ps != null && label.servingSize != null -> out += CalculatedValue(n, ps.amount.multiply(quantity).divide(label.servingSize, 2, RoundingMode.HALF_EVEN).stripTrailingZeros(), ps.unit,
                    "${ps.amount.toPlainString()} ${ps.unit} per serving × ${quantity.toPlainString()} ÷ ${label.servingSize.toPlainString()}")
            }
        }
        return out
    }

    /** Whole-pack values from per-serving facts × servings per pack, when both are printed. */
    fun forPack(label: NutritionLabel): List<CalculatedValue> {
        val servings = label.servingsPerPack ?: return emptyList()
        return Nutrient.entries.mapNotNull { n ->
            label.perServing(n)?.let { CalculatedValue(n, it.amount.multiply(servings).stripTrailingZeros(), it.unit, "${it.amount.toPlainString()} × ${servings.toPlainString()} servings") }
        }
    }
}

/** Highlights requested ingredients found in recognised text. "Not found" never means "free from". */
object IngredientMatcher {
    data class Match(val term: String, val found: Boolean, val positions: List<IntRange>)

    private val synonyms = mapOf(
        "milk" to listOf("milk", "lactose", "whey", "casein", "butter", "cream", "dairy"),
        "gluten" to listOf("gluten", "wheat", "barley", "rye", "oats", "semolina"),
        "nuts" to listOf("nut", "almond", "cashew", "hazelnut", "walnut", "pistachio", "peanut"),
        "soy" to listOf("soy", "soya", "lecithin (soy"),
        "egg" to listOf("egg", "albumin"),
    )

    fun find(text: String, terms: List<String>): List<Match> = terms.map { term ->
        val words = synonyms[term.lowercase()] ?: listOf(term.lowercase())
        val lower = text.lowercase()
        val pos = words.flatMap { w -> Regex("\\b" + Regex.escape(w)).findAll(lower).map { it.range }.toList() }.sortedBy { it.first }
        Match(term, pos.isNotEmpty(), pos)
    }

    const val NOT_FOUND_NOTE = "Not found in the text we read. This doesn't mean the product is free from it; check the pack."
}
