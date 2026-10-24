package com.receiptscan.scanner.plant

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.ml.ImageClassifierRuntime
import com.receiptscan.ml.ModelSource
import com.receiptscan.ml.assetExists
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.FailReason
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.plant.engine.PlantCandidate
import com.receiptscan.scanner.plant.engine.PlantOutcome
import com.receiptscan.scanner.plant.engine.PlantPostprocess
import com.receiptscan.scanner.plant.engine.SpeciesLabel
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PlantPayload(val chosen: String?, val candidates: List<String>, val model: String)

data class PlantScan(val image: Bitmap, val outcome: PlantOutcome, val sourceIds: List<String>)

/**
 * Plant identifier (S-Plant): the Google AIY plants V1 classifier, shown as "Likely matches" with no
 * percentages, and a safety note. Low confidence becomes "no match" rather than a guess.
 */
object PlantScanner : ScannerModule<PlantScan> {
    val ID = CapabilityId("plant")
    const val MODEL_ASSET = "models/aiy_plants_v1.tflite"
    const val MODEL_NAME = "Google AIY Vision classifier plants_V1"
    override val info = ModuleInfo(ID, "Plants", "Likely matches for a plant", ModuleColor.PLANT, AppIcons.plant,
        listOf("Fill the frame with one leaf or flower", "Use daylight and a plain background", "Try another angle if matches look unsure"))

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<PlantScan>) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        ImageScanFlow(info, input, host, noun = "plant", steps = listOf("Photo ready", "Comparing with known plants"), onFailed = onResult, onCancel = onCancel,
            hint = "One leaf or flower, close up",
            process = { bmp ->
                if (!assetExists(context, MODEL_ASSET)) { onResult(ScanOutcome.Failed(FailReason.PACK_MISSING, "The plant model isn't included in this build")); return@ImageScanFlow }
                val labels = withContext(Dispatchers.Default) {
                    val rt = ImageClassifierRuntime.create(context, ModelSource.Asset(MODEL_ASSET), maxResults = 5)
                    try { rt.classify(bmp) } finally { rt.close() }
                }
                val outcome = PlantPostprocess.fromTop(labels.map { PlantCandidate(SpeciesLabel(it.index, it.name, it.displayName), it.score) })
                onResult(ScanOutcome.Success(PlantScan(bmp, outcome, listOfNotNull(host.keepImage(bmp)))))
            })
    }

    @Composable
    override fun Result(result: PlantScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        val matches = (result.outcome as? PlantOutcome.Matches)?.candidates.orEmpty()
        var chosen by remember(result) { mutableStateOf<PlantCandidate?>(null) }
        fun name(c: PlantCandidate) = c.label.commonName?.let { "$it (${c.label.scientificName})" } ?: c.label.scientificName
        ResultScaffold(
            info, "Plants", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("Scan again", { onAction(ResultAction.ScanAgain) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.camera, fill = true)
                    if (host.capabilities.canSave) AppButton("Save", {
                        val p = PlantPayload(chosen?.let(::name), matches.map(::name), MODEL_NAME)
                        onAction(ResultAction.Save(ScanEnvelope(ID, 1, chosen?.let(::name) ?: "Plant photo", if (matches.isEmpty()) "No match" else "Likely: " + matches.joinToString { it.label.scientificName }, Json.encodeToString(PlantPayload.serializer(), p), result.sourceIds)))
                    }, Modifier.weight(1f), fill = true)
                }
            },
        ) {
            SourcePhoto(result.image)
            when (val o = result.outcome) {
                PlantOutcome.NoMatch -> {
                    ModuleHeader(info, "No clear match", null)
                    Banner(BadgeKind.INFO, "We couldn't match this plant", "Try a closer photo of one leaf or flower, in daylight. The model knows about 2,000 common species.")
                }
                is PlantOutcome.Matches -> {
                    ModuleHeader(info, "Likely matches", "Best first. Tap the one that looks right.")
                    o.candidates.forEachIndexed { i, c ->
                        AppCard(Modifier.fillMaxWidth(), onClick = { chosen = c }) {
                            Column {
                                Row {
                                    Text(c.label.commonName ?: c.label.scientificName, style = AppType.h2, color = t.text, modifier = Modifier.weight(1f))
                                    if (chosen == c) StatusBadge(BadgeKind.OK, "Chosen") else if (i == 0) StatusBadge(BadgeKind.NEUTRAL, "Best match")
                                }
                                if (c.label.commonName != null) Text(c.label.scientificName, style = AppType.b2.copy(fontStyle = FontStyle.Italic), color = t.muted)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (o.suggestAnotherPhoto) Banner(BadgeKind.WARN, "Not sure", "These matches are close. Another photo from a different angle may help.")
                }
            }
            Banner(BadgeKind.WARN, "Safety", PlantPostprocess.SAFETY_NOTE)
        }
    }
}
