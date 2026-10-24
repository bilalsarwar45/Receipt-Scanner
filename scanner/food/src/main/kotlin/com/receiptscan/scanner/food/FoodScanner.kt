package com.receiptscan.scanner.food

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ChipStyle
import com.receiptscan.design.FieldState
import com.receiptscan.design.ListRow
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.skin
import com.receiptscan.ml.ImageClassifierRuntime
import com.receiptscan.ml.ModelSource
import com.receiptscan.ml.assetExists
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.food.engine.FoodDatabase
import com.receiptscan.scanner.food.engine.FoodPack
import com.receiptscan.scanner.food.engine.FoodRecord
import com.receiptscan.scanner.food.engine.PlateCalculator
import com.receiptscan.scanner.food.engine.PlateComponent
import com.receiptscan.scanner.nutrition.engine.Nutrient
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.ImageScanFlow
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.ui.SourcePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class FoodItemPayload(val name: String, val source: String, val sourceId: String, val grams: Double, val kcal: Double?, val proteinG: Double?, val carbsG: Double?, val fatG: Double?)

/** Saved plate. Every nutrition figure is an estimate. */
@Serializable
data class FoodPayload(val items: List<FoodItemPayload>, val totalKcal: Double?, val estimate: Boolean = true, val dataSource: String?)

data class FoodScan(val image: Bitmap?, val suggestions: List<String>, val modelMissing: Boolean, val sourceIds: List<String>)

/** Loads the bundled USDA FoodData Central pack once. Returns null when this build has no pack. */
object FoodPackLoader {
    const val ASSET = "packs/food_usda_sr.json"
    private val lock = Mutex()
    @Volatile private var cached: FoodPack? = null
    @Volatile private var missing = false

    suspend fun load(context: Context): FoodPack? = lock.withLock {
        cached ?: if (missing) null else withContext(Dispatchers.IO) {
            runCatching { context.assets.open(ASSET).bufferedReader().use { FoodDatabase.parsePack(it.readText()) } }.getOrNull()
        }.also { if (it == null) missing = true else cached = it }
    }
}

/**
 * Food & calories (S-Food): the Google AIY food V1 classifier suggests dish names; the person picks
 * foods from the offline USDA pack and confirms portions. Calories are always shown as ≈ estimates.
 */
object FoodScanner : ScannerModule<FoodScan> {
    val ID = CapabilityId("food")
    const val MODEL_ASSET = "models/aiy_food_v1.tflite"
    const val ACTION_LOG = "food.log"
    override val info = ModuleInfo(ID, "Food", "Estimate calories for a meal", ModuleColor.FOOD, AppIcons.food,
        listOf("Shoot from above with the whole plate in frame", "Pick the foods and confirm portions", "Oil, sauces and recipes change calories a lot"))

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<FoodScan>) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        val hasModel = remember { assetExists(context, MODEL_ASSET) }
        val src = input.source
        if (!hasModel || src is ScanSource.Text) {
            LaunchedEffect(Unit) { onResult(ScanOutcome.Success(FoodScan(null, listOfNotNull((src as? ScanSource.Text)?.text), !hasModel, emptyList()))) }
            return
        }
        ImageScanFlow(info, input, host, noun = "plate", steps = listOf("Photo ready", "Suggesting foods"), onFailed = onResult, onCancel = onCancel,
            hint = "Whole plate, from above",
            process = { bmp ->
                val labels = withContext(Dispatchers.Default) {
                    val rt = ImageClassifierRuntime.create(context, ModelSource.Asset(MODEL_ASSET), maxResults = 5)
                    try { rt.classify(bmp) } finally { rt.close() }
                }
                val names = labels.filter { it.score >= 0.1f && it.name.lowercase() !in setOf("background", "__background__", "") }
                    .map { (it.displayName ?: it.name).replace('_', ' ') }
                onResult(ScanOutcome.Success(FoodScan(bmp, names, false, listOfNotNull(host.keepImage(bmp)))))
            })
    }

    private fun BigDecimal?.d() = this?.toDouble()
    private fun fmt(v: BigDecimal?, unit: String) = v?.setScale(0, RoundingMode.HALF_EVEN)?.toPlainString()?.let { "$it $unit" } ?: "—"

    @Composable
    override fun Result(result: FoodScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        val context = LocalContext.current
        var pack by remember { mutableStateOf<FoodPack?>(null) }
        var loaded by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { pack = FoodPackLoader.load(context); loaded = true }
        val db = remember(pack) { FoodDatabase(pack) }
        var query by remember(result) { mutableStateOf(result.suggestions.firstOrNull().orEmpty()) }
        val found = remember(db, query) { db.search(query, 12).ifEmpty { query.split(' ').filter { it.length > 3 }.flatMap { db.search(it, 4) }.distinct().take(12) } }
        val plate = remember(result) { mutableStateListOf<Pair<FoodRecord, String>>() }
        val components = plate.mapNotNull { (f, g) -> g.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { PlateComponent(f, it) } }
        val estimate = remember(components) { PlateCalculator.estimate(components) }

        fun payload() = FoodPayload(
            estimate.lines.map { (c, m) -> FoodItemPayload(c.food.name, c.food.source, c.food.sourceId, c.grams.toDouble(), m[Nutrient.ENERGY_KCAL].d(), m[Nutrient.PROTEIN].d(), m[Nutrient.CARBOHYDRATE].d(), m[Nutrient.FAT].d()) },
            estimate.kcal.d(), true, pack?.source,
        )
        ResultScaffold(
            info, "Food", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (host.capabilities.canSave) AppButton("Add to food log", { onAction(ResultAction.Custom(ACTION_LOG, Json.encodeToString(FoodPayload.serializer(), payload()))) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, enabled = components.isNotEmpty(), fill = true)
                    if (host.capabilities.canSave) AppButton("Save", {
                        val p = payload()
                        onAction(ResultAction.Save(ScanEnvelope(ID, 1, components.joinToString { it.food.name }.take(60), "≈ ${fmt(estimate.kcal, "kcal")}", Json.encodeToString(FoodPayload.serializer(), p), result.sourceIds)))
                    }, Modifier.weight(1f), enabled = components.isNotEmpty(), fill = true)
                    else AppButton("Copy", { onAction(ResultAction.Copy(components.joinToString("\n") { "${it.food.name} ${it.grams.toPlainString()} g" } + "\n≈ ${fmt(estimate.kcal, "kcal")}")) }, Modifier.weight(1f), enabled = components.isNotEmpty(), fill = true)
                }
            },
        ) {
            SourcePhoto(result.image)
            if (result.modelMissing) Banner(BadgeKind.INFO, "Photo suggestions aren't in this build", "Search for foods below instead.")
            if (loaded && pack == null) Banner(BadgeKind.WARN, "Food data isn't installed", "This build has no nutrition database, so calories can't be estimated.")
            if (result.suggestions.isNotEmpty()) {
                ModuleHeader(info, "Likely foods", "Tap one to search for it")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.suggestions.forEach { s -> AppChip(s, { query = s }, selected = s == query, style = ChipStyle.TONAL) }
                }
                Spacer(Modifier.height(8.dp))
            }
            AppTextField(query, { query = it }, "Search foods", leading = { androidx.compose.material3.Icon(AppIcons.search, null) }, placeholder = "e.g. rice, chicken curry, apple")
            found.forEach { f ->
                ListRow(f.name, meta = "${fmt(f.value(Nutrient.ENERGY_KCAL), "kcal")} per 100 g", onClick = {
                    plate += f to (f.portions.firstOrNull()?.grams ?: "100")
                }, trailing = { androidx.compose.material3.Icon(AppIcons.plus, "Add") }, compact = true)
            }
            if (query.isNotBlank() && found.isEmpty() && pack != null) Text("No foods match “$query”.", style = AppType.b2, color = t.muted)
            Spacer(Modifier.height(12.dp))
            if (plate.isNotEmpty()) {
                Text("On the plate", style = AppType.h2, color = t.text)
                plate.forEachIndexed { i, (f, g) ->
                    AppCard(Modifier.fillMaxWidth()) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, style = AppType.b1, color = t.text, modifier = Modifier.weight(1f))
                                AppIconButton(AppIcons.close, "Remove ${f.name}", { plate.removeAt(i) })
                            }
                            val grams = g.toBigDecimalOrNull()
                            AppTextField(g, { v -> plate[i] = f to v.filter { it.isDigit() || it == '.' } }, "Portion (g)", keyboardType = KeyboardType.Decimal,
                                state = if (grams == null || grams.signum() <= 0) FieldState.ERROR else FieldState.DEFAULT,
                                helper = if (grams == null || grams.signum() <= 0) "Enter grams" else grams.multiply(f.value(Nutrient.ENERGY_KCAL) ?: BigDecimal.ZERO).divide(BigDecimal(100), 0, RoundingMode.HALF_EVEN).let { "≈ $it kcal" },
                                badge = { SourceBadge(ValueSource.EDITED) })
                            if (f.portions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                f.portions.take(6).forEach { p -> AppChip("${p.label} · ${p.grams} g", { plate[i] = f to p.grams }, selected = p.grams == g, style = ChipStyle.OUTLINE) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                AppCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Total", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f)); SourceBadge(ValueSource.ESTIMATE)
                        }
                        Text("≈ ${fmt(estimate.kcal, "kcal")}", style = AppType.d1, color = t.text)
                        Row {
                            listOf(Nutrient.PROTEIN to "Protein", Nutrient.CARBOHYDRATE to "Carbs", Nutrient.FAT to "Fat").forEach { (n, l) ->
                                Column(Modifier.weight(1f)) { Text(l, style = AppType.cap, color = t.muted); Text("≈ ${fmt(estimate.totals[n], "g")}", style = AppType.b1, color = t.text) }
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Text("Per-100 g values from ${pack?.source ?: "the food database"} × your portions.", style = AppType.cap, color = t.muted)
                    }
                }
                if (estimate.missing.isNotEmpty()) Banner(BadgeKind.WARN, "Missing calories", "No energy value for: ${estimate.missing.joinToString()}")
            }
            Banner(BadgeKind.INFO, "Calories are estimates", "Portion sizes, cooking oil and recipes aren't visible in a photo. Weigh food for accuracy.")
        }
    }
}
