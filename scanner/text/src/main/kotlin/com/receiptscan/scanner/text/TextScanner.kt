package com.receiptscan.scanner.text

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ChipStyle
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.skin
import com.receiptscan.ocr.LanguageDetector
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.EnvelopeEncodable
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.text.engine.EntityType
import com.receiptscan.scanner.text.engine.Script
import com.receiptscan.scanner.text.engine.TextEntities
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
import java.time.LocalDate
import java.util.Locale

@Serializable
data class TextScanPayload(val text: String, val language: String?, val script: String, val engine: String, val sourceIds: List<String>)

data class TextResult(
    val text: String,
    val language: String?,
    val script: Script,
    val engine: String,
    val sourceIds: List<String>,
    val image: Bitmap?,
) : EnvelopeEncodable {
    override fun toEnvelope() = ScanEnvelope(
        TextScanner.ID, 1, text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "Text", text.take(300),
        Json.encodeToString(TextScanPayload.serializer(), TextScanPayload(text, language, script.name, engine, sourceIds)), sourceIds,
    )
}

/** Text/OCR scanner (S-Text): editable recognised text, smart chips, copy, save and hand-off to Translate. */
object TextScanner : ScannerModule<TextResult> {
    val ID = CapabilityId("text")
    override val info = ModuleInfo(ID, "Text", "Copy text from paper", ModuleColor.TEXT, AppIcons.text, listOf("Hold parallel to the page", "Tap text to edit before copying"))

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<TextResult>) -> Unit, onCancel: () -> Unit) {
        val src = input.source
        if (src is ScanSource.Text) {
            LaunchedEffect(src) { onResult(ScanOutcome.Success(TextResult(src.text, src.languageTag, TextEntities.dominantScript(src.text), "provided", emptyList(), null))) }
            return
        }
        OcrScanFlow(info, input, host, noun = "page", allowPdf = true, onCancel = onCancel, onFailed = onResult, onDocument = { cap ->
            val text = cap.document.fullText
            onResult(ScanOutcome.Success(TextResult(text, null, TextEntities.dominantScript(text), cap.document.engine, cap.sourceIds, cap.images.firstOrNull())))
        })
    }

    @Composable
    override fun Result(result: TextResult, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        var text by remember(result) { mutableStateOf(result.text) }
        var language by remember(result) { mutableStateOf(result.language) }
        var query by remember { mutableStateOf("") }
        val edited = text != result.text
        val today = remember { LocalDate.now(host.clock.withZone(input.zone)) }
        val entities = remember(text) { TextEntities.find(text, today) }
        LaunchedEffect(result) { if (language == null) language = runCatching { LanguageDetector.detect(result.text) }.getOrNull() }
        val t = skin
        ResultScaffold(
            info, "Text", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("Copy", { onAction(ResultAction.Copy(text)) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.copy, fill = true)
                    if (host.capabilities.canSave) AppButton("Save", { onAction(ResultAction.Save(result.copy(text = text).toEnvelope())) }, Modifier.weight(1f), fill = true)
                }
            },
        ) {
            ModuleHeader(info, "${text.lines().count { it.isNotBlank() }} lines", language?.let { "Detected language: ${Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH)}" } ?: "Language not detected")
            SourcePhoto(result.image)
            if (result.script != Script.LATIN && result.script != Script.NONE) {
                Banner(BadgeKind.WARN, "${result.script.label} script may not be read correctly", "The on-device reader in this version supports Latin script only. Check every word before using it.")
            }
            Spacer(Modifier.height(8.dp))
            AppTextField(text, { text = it }, "Recognised text", singleLine = false, minLines = 6, textStyle = AppType.b1, badge = { SourceBadge(if (edited) ValueSource.EDITED else ValueSource.FROM_PHOTO) })
            if (entities.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Found in the text", style = AppType.lab, color = t.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    entities.distinctBy { it.normalized }.take(12).forEach { e ->
                        val (icon, link) = when (e.type) {
                            EntityType.URL -> AppIcons.link to (if (e.normalized.startsWith("http")) e.normalized else "https://${e.normalized}")
                            EntityType.EMAIL -> AppIcons.email to "mailto:${e.normalized}"
                            EntityType.PHONE -> AppIcons.phone to "tel:${e.normalized}"
                            EntityType.DATE -> AppIcons.calendar to null
                        }
                        AppChip(e.text, { if (link != null && host.capabilities.canOpenLinks) onAction(ResultAction.OpenLink(link)) else onAction(ResultAction.Copy(e.normalized)) }, icon = icon, style = ChipStyle.TONAL)
                    }
                }
                Text("Links open only when you tap them.", style = AppType.cap, color = t.muted)
            }
            Spacer(Modifier.height(12.dp))
            AppTextField(query, { query = it }, "Find in text", placeholder = "Search")
            if (query.isNotBlank()) {
                val n = TextEntities.occurrences(text, query).size
                Text(if (n == 0) "No matches" else "$n match${if (n == 1) "" else "es"}", style = AppType.cap, color = t.muted)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Translate", { onAction(ResultAction.HandOff(CapabilityId("translate"), ScanSource.Text(text, language))) }, Modifier.weight(1f), variant = ButtonVariant.OUTLINE, icon = AppIcons.translate, fill = true)
                if (host.capabilities.canShare) AppButton("Share", { onAction(ResultAction.Share(text)) }, Modifier.weight(1f), variant = ButtonVariant.OUTLINE, icon = AppIcons.share, fill = true)
            }
            AppButton("Scan another", { onAction(ResultAction.ScanAgain) }, variant = ButtonVariant.GHOST, fill = true)
        }
    }
}
