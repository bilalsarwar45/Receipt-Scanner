package com.receiptscan.scanner.receipt

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.receiptscan.capture.Images
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppType
import com.receiptscan.design.ButtonSize
import com.receiptscan.design.ChipStyle
import com.receiptscan.design.ModuleColor
import com.receiptscan.ocr.OcrInput
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.FailReason
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.receipt.engine.Categorizer
import com.receiptscan.scanner.receipt.engine.ReceiptExtraction
import com.receiptscan.scanner.receipt.engine.ReceiptParser
import com.receiptscan.scanner.receipt.engine.ReceiptParserConfig
import com.receiptscan.scanner.ui.CaptureScreen
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ProcessingView
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.time.DateOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/** A read receipt plus the images it came from. [queueAnother] asks the host to start the next scan after saving. */
data class ReceiptScan(
    val extraction: ReceiptExtraction,
    val pages: List<Bitmap>,
    val sourceIds: List<String>,
    val queueAnother: Boolean,
    val ocrEngine: String,
)

/**
 * Receipt scanner (R-Capture → R-Long → R-Crop → R-Processing → R-Review). Returns a draft through
 * [ACTION_DRAFT] or [ACTION_CONFIRM] custom actions carrying [ReceiptDraftPayload] JSON.
 */
object ReceiptScanner : ScannerModule<ReceiptScan> {
    val ID = CapabilityId("receipt")
    const val ACTION_DRAFT = "receipt.draft"
    const val ACTION_CONFIRM = "receipt.confirm"
    override val info = ModuleInfo(ID, "Receipts", "Scan a receipt into an expense", ModuleColor.RECEIPT, AppIcons.receipt,
        listOf("Flatten the receipt on a dark surface", "Use Long receipt for receipts that don't fit", "Check highlighted lines before saving"))

    /** Learned corrections and settings the host can set before showing the scanner. */
    @Volatile var dateOrder: DateOrder = DateOrder.DMY
    @Volatile var merchantAliases: Map<String, String> = emptyMap()
    @Volatile var merchantRules: Map<String, String> = emptyMap()
    @Volatile var itemRules: Map<String, String> = emptyMap()
    @Volatile var enhanceByDefault: Boolean = true
    @Volatile var autoCapture: Boolean = true

    fun parser(input: ScanInput, today: LocalDate) = ReceiptParser(
        ReceiptParserConfig(today, input.currency, dateOrder, merchantAliases = merchantAliases, categorizer = Categorizer(merchantRules, itemRules)),
    )

    private sealed interface Stage {
        data object Camera : Stage
        data class Crop(val image: Bitmap, val long: Boolean) : Stage
        data class Reading(val pages: List<Bitmap>, val enhance: Boolean, val queueAnother: Boolean) : Stage
    }

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<ReceiptScan>) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        var stage by remember { mutableStateOf<Stage?>(null) }
        var pages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
        var longMode by remember { mutableStateOf(false) }
        var longParts by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
        var step by remember { mutableStateOf(0) }
        var lines by remember { mutableStateOf(0) }
        val src = input.source

        LaunchedEffect(src) {
            if (stage != null) return@LaunchedEffect
            stage = when (src) {
                is ScanSource.Image -> try { Stage.Crop(Images.load(context, android.net.Uri.parse(src.uri)), false) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    onResult(ScanOutcome.Failed(FailReason.UNREADABLE_FILE, e.message)); null
                }
                is ScanSource.Pdf -> try { Stage.Reading(Images.renderPdf(context, android.net.Uri.parse(src.uri), maxPages = 6), enhanceByDefault, false) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    onResult(ScanOutcome.Failed(FailReason.UNREADABLE_FILE, e.message)); null
                }
                else -> Stage.Camera
            }
        }

        when (val s = stage) {
            null -> ProcessingView("Opening…", listOf("Opening file"), 0, onCancel)
            Stage.Camera -> CaptureScreen(
                info, host, input,
                onCaptured = { shots ->
                    val shot = shots.firstOrNull() ?: return@CaptureScreen
                    if (longMode) longParts = longParts + shot else stage = Stage.Crop(shot, false)
                },
                onCancel = { if (pages.isNotEmpty()) stage = Stage.Reading(pages, enhanceByDefault, false) else onCancel() },
                noun = "receipt",
                autoCaptureAvailable = autoCapture && !longMode,
                allowPdf = pages.isEmpty() && !longMode,
                hint = when {
                    longMode && longParts.isNotEmpty() -> "Part ${longParts.size + 1} · keep the last line in view"
                    longMode -> "Start at the top of the receipt"
                    pages.isNotEmpty() -> "Page ${pages.size + 1}"
                    else -> null
                },
                modeChips = {
                    if (pages.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppChip("Single", { longMode = false; longParts = emptyList() }, selected = !longMode, style = ChipStyle.TONAL)
                        AppChip("Long receipt", { longMode = true }, selected = longMode, style = ChipStyle.TONAL)
                    }
                },
                bottomExtra = {
                    if (longMode && longParts.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        longParts.forEach { p -> Image(p.asImageBitmap(), null, Modifier.size(40.dp, 56.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop) }
                        Text("${longParts.size} part${if (longParts.size == 1) "" else "s"}", style = AppType.cap, color = Color.White)
                        AppButton("Done", { stage = Stage.Crop(Images.stitchVertical(longParts), true) }, size = ButtonSize.SMALL)
                    }
                },
            )
            is Stage.Crop -> CropScreen(
                s.image, pages.size, allowCorners = !s.long, enhanceDefault = enhanceByDefault,
                onRead = { bmp, enhance, queue ->
                    // Long receipts are read part by part (the parser drops overlapping rows); the stitched image is the preview.
                    val all = if (s.long) pages + longParts else pages + bmp
                    stage = Stage.Reading(all, enhance, queue)
                },
                onAddPage = { bmp -> pages = pages + (if (s.long) longParts else listOf(bmp)); longParts = emptyList(); longMode = false; stage = Stage.Camera },
                onRetake = { if (s.long) { stage = Stage.Camera } else stage = Stage.Camera },
            )
            is Stage.Reading -> {
                val steps = listOf("Image cleaned up", if (lines > 0) "Text found ($lines lines)" else "Finding text", "Matching items and totals", "Checking the arithmetic")
                ProcessingView("Reading receipt", steps, step, onCancel = { stage = if (src is ScanSource.Camera) Stage.Crop(s.pages.last(), false) else null.also { onCancel() } })
                LaunchedEffect(s) {
                    try {
                        step = 0
                        val prepared = withContext(Dispatchers.Default) { if (s.enhance) s.pages.map { Images.enhance(it) } else s.pages }
                        step = 1
                        val ids = s.pages.map { host.keepImage(it) ?: "session-${UUID.randomUUID()}" }
                        val doc = host.ocr.recognize(prepared.mapIndexed { i, b -> OcrInput(b, ids[i], i) })
                        lines = doc.pages.sumOf { it.lines.size }
                        if (doc.isEmpty) { stage = Stage.Camera; onResult(ScanOutcome.Failed(FailReason.NO_TEXT)); return@LaunchedEffect }
                        step = 2
                        val extraction = withContext(Dispatchers.Default) { parser(input, LocalDate.now(host.clock)).parse(doc) }
                        step = 3
                        val scan = ReceiptScan(extraction, s.pages, ids, s.queueAnother, "${host.ocr.engine} ${host.ocr.engineVersion}")
                        onResult(if (extraction.issues.isEmpty()) ScanOutcome.Success(scan) else ScanOutcome.NeedsReview(scan, extraction.issues))
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        stage = Stage.Camera
                        onResult(ScanOutcome.Failed(FailReason.PROVIDER_UNAVAILABLE, "Text recognition failed: ${e.message}"))
                    }
                }
            }
        }
    }

    @Composable
    override fun Result(result: ReceiptScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        ReceiptReview(result, input, host, onAction, onBack)
    }
}
