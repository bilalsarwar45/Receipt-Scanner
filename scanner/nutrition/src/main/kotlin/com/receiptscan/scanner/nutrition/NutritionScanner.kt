package com.receiptscan.scanner.nutrition

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.Divider
import com.receiptscan.design.FieldState
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.nutrition.engine.Basis
import com.receiptscan.scanner.nutrition.engine.IngredientMatcher
import com.receiptscan.scanner.nutrition.engine.Nutrient
import com.receiptscan.scanner.nutrition.engine.NutritionCalculator
import com.receiptscan.scanner.nutrition.engine.NutritionLabel
import com.receiptscan.scanner.nutrition.engine.NutritionLabelParser
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.OcrScanFlow
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.ui.SourcePhoto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

@Serializable
data class NutritionPayload(val servingSize: String?, val servingUnit: String?, val per100: Map<String, String>, val perServing: Map<String, String>, val ingredients: String?, val allergens: String?)

data class NutritionScan(val label: NutritionLabel, val image: Bitmap?, val sourceIds: List<String>)

/**
 * Nutrition and ingredients (S-Nutrition). Label facts ("From photo"), calculations from them
 * ("Calculated", with the formula) and ingredient matches are kept visibly separate.
 */
object NutritionScanner : ScannerModule<NutritionScan> {
    val ID = CapabilityId("nutrition")
    override val info = ModuleInfo(ID, "Nutrition", "Nutrition tables and ingredients", ModuleColor.NUTRITION, AppIcons.nutrition,
        listOf("Fill the frame with the table only", "Avoid glare", "Check the serving size we read"))

    private val shown = listOf(Nutrient.ENERGY_KCAL, Nutrient.FAT, Nutrient.SATURATED_FAT, Nutrient.CARBOHYDRATE, Nutrient.SUGARS, Nutrient.FIBRE, Nutrient.PROTEIN, Nutrient.SODIUM, Nutrient.SALT)

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<NutritionScan>) -> Unit, onCancel: () -> Unit) {
        OcrScanFlow(info, input, host, noun = "table", onCancel = onCancel, onFailed = onResult, onDocument = { cap ->
            val label = NutritionLabelParser.parse(cap.document)
            val scan = NutritionScan(label, cap.images.firstOrNull(), cap.sourceIds)
            onResult(if (label.values.isEmpty()) ScanOutcome.NeedsReview(scan, emptyList()) else ScanOutcome.Success(scan))
        })
    }

    @Composable
    override fun Result(result: NutritionScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        val label = result.label
        var serving by remember { mutableStateOf(label.servingSize?.stripTrailingZeros()?.toPlainString() ?: "") }
        val servingEdited = serving != (label.servingSize?.stripTrailingZeros()?.toPlainString() ?: "")
        val qty = serving.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val calculated = remember(serving) { qty?.let { NutritionCalculator.forQuantity(label, it) }.orEmpty() }
        var terms by remember { mutableStateOf(setOf<String>()) }
        val matches = remember(terms) { if (label.ingredients == null) emptyList() else IngredientMatcher.find(label.ingredients!!, terms.toList()) }
        val unit = label.servingUnit ?: (label.values.firstNotNullOfOrNull { (it.basis as? Basis.Per100)?.unit } ?: "g")
        ResultScaffold(
            info, "Nutrition", onBack,
            bottom = {
                if (host.capabilities.canSave) AppButton("Save", {
                    val p = NutritionPayload(qty?.toPlainString(), unit,
                        label.values.filter { it.basis is Basis.Per100 }.associate { it.nutrient.name to it.amount.toPlainString() },
                        label.values.filter { it.basis is Basis.PerServing }.associate { it.nutrient.name to it.amount.toPlainString() },
                        label.ingredients, label.allergenStatement)
                    onAction(ResultAction.Save(ScanEnvelope(ID, 1, "Nutrition label", label.per100(Nutrient.ENERGY_KCAL)?.let { "${it.amount.toPlainString()} kcal per 100 $unit" } ?: "Nutrition facts",
                        Json.encodeToString(NutritionPayload.serializer(), p), result.sourceIds)))
                }, enabled = label.values.isNotEmpty(), fill = true)
            },
        ) {
            ModuleHeader(info, "Nutrition facts", "${label.values.size} values read from the label")
            SourcePhoto(result.image)
            if (label.values.isEmpty()) Banner(BadgeKind.WARN, "Couldn't find a nutrition table", "Fill the frame with the table only and avoid glare, then try again.")
            Spacer(Modifier.height(8.dp))
            AppTextField(serving, { serving = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, "Serving size ($unit)", keyboardType = KeyboardType.Decimal,
                state = if (serving.isNotEmpty() && qty == null) FieldState.ERROR else FieldState.DEFAULT,
                helper = if (serving.isNotEmpty() && qty == null) "Must be more than 0" else if (label.servingSize == null) "Not printed. Enter the amount you'll eat." else null,
                badge = { SourceBadge(if (servingEdited) ValueSource.EDITED else ValueSource.FROM_PHOTO) })
            Spacer(Modifier.height(12.dp))
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    Row {
                        Text("Nutrient", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1.3f))
                        Text("Per 100 $unit", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f))
                        Text(if (qty != null) "Per ${qty.toPlainString()} $unit" else "Per serving", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f))
                    }
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Spacer(Modifier.weight(1.3f))
                        Box1 { SourceBadge(ValueSource.FROM_PHOTO) }
                        Box1 { SourceBadge(ValueSource.CALCULATED) }
                    }
                    Divider()
                    shown.forEach { n ->
                        val read = label.per100(n)
                        val calc = calculated.firstOrNull { it.nutrient == n }
                        val printedServing = label.perServing(n)
                        if (read == null && calc == null && printedServing == null) return@forEach
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                            Text(n.label + if (n == Nutrient.ENERGY_KCAL) " (kcal)" else "", style = AppType.b2, color = t.text, modifier = Modifier.weight(1.3f))
                            Text(read?.let { "${it.amount.toPlainString()} ${it.unit}" } ?: "—", style = AppType.num, color = t.text, modifier = Modifier.weight(1f))
                            Column(Modifier.weight(1f)) {
                                Text(calc?.let { "${it.amount.toPlainString()} ${it.unit}" } ?: printedServing?.let { "${it.amount.toPlainString()} ${it.unit}" } ?: "—", style = AppType.num, color = t.text)
                                calc?.let { Text(it.formula, style = AppType.cap, color = t.muted) }
                                if (calc == null && printedServing != null) Text("Printed", style = AppType.cap, color = t.muted)
                            }
                        }
                    }
                }
            }
            label.servingsPerPack?.let { Text("Servings per pack (printed): ${it.toPlainString()}", style = AppType.cap, color = t.muted, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))
            Text("Ingredients", style = AppType.h2, color = t.text)
            if (label.ingredients == null) {
                Text("No ingredient list found in this photo.", style = AppType.b2, color = t.muted)
            } else {
                Text(label.ingredients!!, style = AppType.b2, color = t.text)
                label.allergenStatement?.let { Text(it, style = AppType.lab, color = t.warn, modifier = Modifier.padding(top = 6.dp)) }
                Spacer(Modifier.height(8.dp))
                Text("Look for", style = AppType.lab, color = t.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("milk", "gluten", "nuts", "soy", "egg").forEach { term ->
                        AppChip(term.replaceFirstChar { it.uppercase() }, { terms = if (term in terms) terms - term else terms + term }, selected = term in terms)
                    }
                }
                matches.forEach { m ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        StatusBadge(if (m.found) BadgeKind.WARN else BadgeKind.NEUTRAL, if (m.found) "${m.term}: found ${m.positions.size}×" else "${m.term}: not found")
                    }
                    if (!m.found) Text(IngredientMatcher.NOT_FOUND_NOTE, style = AppType.cap, color = t.muted)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Values come from the label. Recognition can misread digits; compare with the pack.", style = AppType.cap, color = t.muted)
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.Box1(content: @Composable () -> Unit) {
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) { content() }
    }
}

private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()
