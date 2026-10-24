package com.receiptscan.scanner.translation

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.receiptscan.capture.uprightBitmap
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppProgress
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ChipStyle
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.ocr.LanguageDetector
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.EnvelopeEncodable
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.CaptureScreen
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ProcessingView
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.ui.SourcePhoto
import com.receiptscan.scanner.ui.recognize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** ML Kit on-device translation. Language models are downloaded and verified by ML Kit itself. */
object Translator2 {
    val languages: List<String> get() = TranslateLanguage.getAllLanguages().sorted()

    fun name(tag: String): String = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    fun supported(tag: String?): String? = tag?.let { TranslateLanguage.fromLanguageTag(it) }

    suspend fun downloaded(): Set<String> = runCatching {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
    }.getOrDefault(emptySet())

    fun client(source: String, target: String): Translator = Translation.getClient(
        TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
    )

    suspend fun ensureModels(t: Translator, wifiOnly: Boolean) {
        val c = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        t.downloadModelIfNeeded(c).await()
    }

    suspend fun deleteModel(tag: String) {
        RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(tag).build()).await()
    }
}

@Serializable
data class TranslationPayload(val source: String, val target: String, val original: String, val translated: String)

data class TranslationInput(val original: String, val sourceTag: String?, val image: Bitmap?, val sourceIds: List<String>)

data class TranslationResult(val input: TranslationInput, val source: String, val target: String, val translated: String) : EnvelopeEncodable {
    override fun toEnvelope() = ScanEnvelope(
        TranslationScanner.ID, 1, "Translation · ${Translator2.name(source)} → ${Translator2.name(target)}", translated.take(300),
        Json.encodeToString(TranslationPayload.serializer(), TranslationPayload(source, target, input.original, translated)), input.sourceIds,
    )
}

/**
 * Translation (S-Translate). OCR language coverage (Latin script) and translation language coverage
 * (ML Kit's 50+ languages) differ, and the UI says so instead of promising Urdu OCR.
 */
object TranslationScanner : ScannerModule<TranslationInput> {
    val ID = CapabilityId("translate")
    override val info = ModuleInfo(ID, "Translate", "Translate signs and menus offline", ModuleColor.TRANSLATE, AppIcons.translate,
        listOf("Download the language pair once to work offline", "Tap the eye to see the original"), requiredPacks = listOf("translate-ml-kit"))

    /** Target language the host prefers (from settings); defaults to the device language. */
    var preferredTarget: String = Locale.getDefault().language
    var wifiOnlyDownloads: Boolean = true

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<TranslationInput>) -> Unit, onCancel: () -> Unit) {
        val src = input.source
        if (src is ScanSource.Text) {
            LaunchedEffect(src) { onResult(ScanOutcome.Success(TranslationInput(src.text, src.languageTag, null, emptyList()))) }
            return
        }
        var images by remember { mutableStateOf<List<Bitmap>?>(null) }
        var live by remember { mutableStateOf(false) }
        var overlay by remember { mutableStateOf<List<LiveBlock>>(emptyList()) }
        var frameSize by remember { mutableStateOf(1 to 1) }
        val busy = remember { AtomicBoolean(false) }
        val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        var liveTranslator by remember { mutableStateOf<Translator?>(null) }
        val scope = rememberCoroutineScope()
        DisposableEffect(Unit) { onDispose { recognizer.close(); liveTranslator?.close() } }
        LaunchedEffect(live) {
            if (live && liveTranslator == null) {
                // Live mode needs a known source; use English→target by default, refined after the first frame.
                val target = Translator2.supported(preferredTarget) ?: TranslateLanguage.ENGLISH
                val source = if (target == TranslateLanguage.ENGLISH) TranslateLanguage.FRENCH else TranslateLanguage.ENGLISH
                val tr = Translator2.client(source, target)
                runCatching { Translator2.ensureModels(tr, wifiOnlyDownloads) }
                liveTranslator = tr
            }
        }
        val current = images
        if (current == null) {
            CaptureScreen(
                info, host, input,
                onCaptured = { images = it },
                onCancel = onCancel,
                noun = "text",
                measureGuidance = false,
                hint = if (live) "Live translation · hold steady" else "Point at text, then take a photo",
                analyzer = { proxy ->
                    val tr = liveTranslator
                    if (live && tr != null && busy.compareAndSet(false, true)) {
                        val bmp = proxy.uprightBitmap()
                        frameSize = bmp.width to bmp.height
                        scope.launch {
                            try {
                                val text = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                                overlay = text.textBlocks.take(12).map { b ->
                                    val r = b.boundingBox ?: android.graphics.Rect()
                                    LiveBlock(r.left.toFloat() / bmp.width, r.top.toFloat() / bmp.height, r.width().toFloat() / bmp.width, runCatching { tr.translate(b.text).await() }.getOrDefault(b.text))
                                }
                            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                            } finally { busy.set(false) }
                        }
                    }
                },
                modeChips = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppChip("Photo", { live = false; overlay = emptyList() }, selected = !live)
                        AppChip("Live", { live = true }, selected = live)
                    }
                },
                overlay = {
                    if (live) {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val w = maxWidth; val h = maxHeight
                            overlay.forEach { b ->
                                Text(
                                    b.text, fontSize = 13.sp, color = Color.Black,
                                    modifier = Modifier.offset(w * b.x, h * b.y).widthIn(max = (w * b.w).coerceAtLeast(80.dp))
                                        .clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.9f)).padding(4.dp),
                                )
                            }
                            Text("Offline translation · approximate placement", style = AppType.cap, color = Color.White, modifier = Modifier.padding(16.dp).offset(y = h - 260.dp))
                        }
                    }
                },
            )
        } else {
            ProcessingView("Reading", listOf("Finding text", "Detecting language"), 0, onCancel = { images = null })
            LaunchedEffect(current) {
                try {
                    val cap = recognize(host, current)
                    if (cap.document.isEmpty) { images = null; onResult(ScanOutcome.Failed(com.receiptscan.scanner.contracts.FailReason.NO_TEXT)); return@LaunchedEffect }
                    val text = cap.document.fullText
                    val lang = runCatching { LanguageDetector.detect(text) }.getOrNull()
                    onResult(ScanOutcome.Success(TranslationInput(text, lang, current.first(), cap.sourceIds)))
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    images = null
                    onResult(ScanOutcome.Failed(com.receiptscan.scanner.contracts.FailReason.PROVIDER_UNAVAILABLE, e.message))
                }
            }
        }
    }

    @Composable
    override fun Result(result: TranslationInput, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        var original by remember(result) { mutableStateOf(result.original) }
        var source by remember(result) { mutableStateOf(Translator2.supported(result.sourceTag) ?: TranslateLanguage.ENGLISH) }
        var target by remember { mutableStateOf(Translator2.supported(preferredTarget)?.takeIf { it != source } ?: if (source == TranslateLanguage.ENGLISH) TranslateLanguage.URDU else TranslateLanguage.ENGLISH) }
        var translated by remember { mutableStateOf<String?>(null) }
        var state by remember { mutableStateOf("idle") }
        var error by remember { mutableStateOf<String?>(null) }
        var showOriginal by remember { mutableStateOf(false) }
        var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
        var pick by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(source, target, original) {
            downloaded = Translator2.downloaded()
            val needs = listOf(source, target).filter { it !in downloaded }
            state = if (needs.isEmpty()) "translating" else "downloading"
            error = null
            val client = Translator2.client(source, target)
            try {
                Translator2.ensureModels(client, wifiOnlyDownloads)
                state = "translating"
                translated = client.translate(original).await()
                state = "done"
                downloaded = Translator2.downloaded()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                state = "error"
                error = if (wifiOnlyDownloads && needs.isNotEmpty()) "Language pack needs Wi-Fi to download (${needs.joinToString { Translator2.name(it) }}). Change this in Settings → Feature packs." else (e.message ?: "Translation failed")
            } finally { client.close() }
        }
        val offline = source in downloaded && target in downloaded
        ResultScaffold(
            info, "Translate", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("Copy", { onAction(ResultAction.Copy(if (showOriginal) original else translated ?: "")) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.copy, enabled = translated != null, fill = true)
                    if (host.capabilities.canSave) AppButton("Save", { translated?.let { onAction(ResultAction.Save(TranslationResult(result.copy(original = original), source, target, it).toEnvelope())) } }, Modifier.weight(1f), enabled = translated != null, fill = true)
                }
            },
        ) {
            ModuleHeader(info, "${Translator2.name(source)} → ${Translator2.name(target)}", if (result.sourceTag != null) "Source language detected on this phone" else "Source language chosen by you")
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StatusBadge(if (offline) BadgeKind.OK else BadgeKind.NEUTRAL, if (offline) "Offline" else "Pack needed", icon = if (offline) AppIcons.offline else AppIcons.download)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    AppChip("From: ${Translator2.name(source)}", { pick = "source" }, style = ChipStyle.OUTLINE)
                    LanguageMenu(pick == "source", { pick = null }) { source = it; pick = null }
                }
                AppIconSwap { val s = source; source = target; target = s; translated?.let { original = it } }
                Box {
                    AppChip("To: ${Translator2.name(target)}", { pick = "target" }, style = ChipStyle.OUTLINE)
                    LanguageMenu(pick == "target", { pick = null }) { target = it; pick = null }
                }
            }
            SourcePhoto(result.image)
            Spacer(Modifier.height(8.dp))
            when (state) {
                "downloading" -> { Text("Downloading language pack… This happens once.", style = AppType.b2, color = t.muted); AppProgress(null) }
                "translating" -> AppProgress(null)
                "error" -> Banner(BadgeKind.ERR, "Couldn't translate", error)
            }
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (showOriginal) "Original" else "Translation", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f))
                        com.receiptscan.design.AppIconButton(AppIcons.eye, if (showOriginal) "Show translation" else "Show original", { showOriginal = !showOriginal })
                    }
                    Text(if (showOriginal) original else translated ?: "…", style = AppType.b1, color = t.text)
                    Spacer(Modifier.height(6.dp))
                    Text("Machine translation can be wrong. Check important details with a person.", style = AppType.cap, color = t.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
            AppTextField(original, { original = it }, "Text we read (edit to fix mistakes)", singleLine = false, minLines = 3, badge = { SourceBadge(if (original != result.original) ValueSource.EDITED else ValueSource.FROM_PHOTO) })
            Text("The camera reader supports Latin script. Translation also covers other languages when you type or paste the text.", style = AppType.cap, color = t.muted, modifier = Modifier.padding(top = 6.dp))
            AppButton("Scan another", { onAction(ResultAction.ScanAgain) }, variant = ButtonVariant.GHOST, fill = true)
        }
    }

    @Composable
    private fun LanguageMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            Translator2.languages.sortedBy { Translator2.name(it) }.forEach { tag ->
                DropdownMenuItem(text = { Text(Translator2.name(tag)) }, onClick = { onPick(tag) })
            }
        }
    }

    @Composable
    private fun AppIconSwap(onClick: () -> Unit) {
        com.receiptscan.design.AppIconButton(AppIcons.refresh, "Swap languages", onClick)
    }
}

data class LiveBlock(val x: Float, val y: Float, val w: Float, val text: String)
