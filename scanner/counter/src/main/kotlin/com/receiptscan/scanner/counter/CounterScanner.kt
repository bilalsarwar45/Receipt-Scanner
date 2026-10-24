package com.receiptscan.scanner.counter

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonSize
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.skin
import com.receiptscan.ml.ModelSource
import com.receiptscan.ml.ObjectDetectorRuntime
import com.receiptscan.packs.android.PackManager
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.FailReason
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.counter.engine.CountSession
import com.receiptscan.scanner.counter.engine.Detection
import com.receiptscan.scanner.counter.engine.DetectionFilter
import com.receiptscan.scanner.counter.engine.NRect
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.ImageScanFlow
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CountPayload(val label: String?, val confirmedCount: Int, val detectedCount: Int, val added: Int, val removed: Int, val model: String)

/** What the detector found in one photo. All raw detections are kept so the class filter can change. */
data class CountScan(val image: Bitmap, val detections: List<Detection>, val sourceIds: List<String>)

/**
 * Object counter (S-Counter): EfficientDet-Lite0 (COCO) proposes markers; the person taps to add and
 * long-presses to remove. The confirmed count, not the detector's, is what gets saved.
 */
object CounterScanner : ScannerModule<CountScan> {
    val ID = CapabilityId("counter")
    const val PACK_ID = "detector-efficientdet-lite0"
    const val MODEL_NAME = "EfficientDet-Lite0 int8 (COCO)"
    override val info = ModuleInfo(ID, "Counter", "Count objects in a photo", ModuleColor.COUNTER, AppIcons.counter,
        listOf("Lay items flat with space between them", "Tap to add a missed item, long-press a marker to remove it", "Works best with common objects (bottles, cups, fruit, books)"),
        requiredPacks = listOf(PACK_ID))

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<CountScan>) -> Unit, onCancel: () -> Unit) {
        val context = LocalContext.current
        host.PackGate(info, info.requiredPacks, onCancel) {
            ImageScanFlow(info, input, host, noun = "items", steps = listOf("Photo ready", "Finding objects"), onFailed = onResult, onCancel = onCancel,
                hint = "Fit all items in the frame",
                process = { bmp ->
                    val file = PackManager.get(context).file(PACK_ID)
                    if (file == null) { onResult(ScanOutcome.Failed(FailReason.PACK_MISSING, "Download the counting pack first")); return@ImageScanFlow }
                    val found = withContext(Dispatchers.Default) {
                        val rt = ObjectDetectorRuntime.create(context, ModelSource.FileSource(file))
                        try { rt.detectTiled(bmp) } finally { rt.close() }
                    }
                    val ids = listOfNotNull(host.keepImage(bmp))
                    onResult(ScanOutcome.Success(CountScan(bmp, found.map { Detection(NRect(it.left, it.top, it.right, it.bottom), it.label, it.score) }, ids)))
                })
        }
    }

    /** Detector classes ordered by how many confident detections each has. */
    fun classesByCount(detections: List<Detection>): List<Pair<String, Int>> =
        DetectionFilter.filter(detections, null, null).groupingBy { it.label }.eachCount().toList().sortedByDescending { it.second }

    @Composable
    override fun Result(result: CountScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        val classes = remember(result) { classesByCount(result.detections) }
        var selected by remember(result) { mutableStateOf(classes.firstOrNull()?.first) }
        val session = remember(result, selected) {
            CountSession(DetectionFilter.filter(result.detections, selected?.let { setOf(it) }, null))
        }
        var version by remember { mutableIntStateOf(0) }
        val v = version // read so marker edits recompose
        val markers = if (v >= 0) session.current else emptyList()
        ResultScaffold(
            info, "Counter", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("Copy", { onAction(ResultAction.Copy("${session.count} ${selected ?: "items"}")) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.copy, fill = true)
                    if (host.capabilities.canSave) AppButton("Save", {
                        val p = CountPayload(selected, session.count, session.detectedCount, session.added, session.removed, MODEL_NAME)
                        onAction(ResultAction.Save(ScanEnvelope(ID, 1, "${session.count} ${selected ?: "items"}", "Detected ${session.detectedCount}, corrected to ${session.count}", Json.encodeToString(CountPayload.serializer(), p), result.sourceIds)))
                    }, Modifier.weight(1f), fill = true)
                }
            },
        ) {
            ModuleHeader(info, "Check the markers", "Tap to add · long-press a marker to remove")
            if (classes.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    classes.forEach { (label, n) -> AppChip("$label · $n", { selected = label; version++ }, selected = label == selected) }
                    AppChip("Everything", { selected = null; version++ }, selected = selected == null)
                }
                Spacer(Modifier.height(8.dp))
            }
            val ratio = result.image.width.toFloat() / result.image.height.coerceAtLeast(1)
            BoxWithConstraints(
                Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(16.dp))
                    .pointerInput(session) {
                        detectTapGestures(
                            onTap = { o -> if (session.add(o.x / size.width, o.y / size.height)) version++ },
                            onLongPress = { o -> if (session.removeNear(o.x / size.width, o.y / size.height)) version++ },
                        )
                    }
                    .semantics { contentDescription = "Photo with ${markers.size} markers" },
            ) {
                Image(result.image.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                val w = maxWidth; val h = maxHeight
                markers.forEachIndexed { i, m ->
                    Box(
                        Modifier.offset(w * m.x - 12.dp, h * m.y - 12.dp).size(24.dp).clip(CircleShape)
                            .background(if (m.fromDetector) Color(0xE6A16207) else Color(0xE60284C7))
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = AppType.cap, color = Color.White) }
                }
            }
            Spacer(Modifier.height(12.dp))
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Count", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f))
                        SourceBadge(if (session.added + session.removed > 0) ValueSource.EDITED else ValueSource.FROM_PHOTO)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AppIconButton(AppIcons.minus, "Remove last marker", { val last = markers.lastOrNull(); if (last != null && session.removeNear(last.x, last.y, 0.001f)) version++ }, surface = true, enabled = markers.isNotEmpty())
                        Text("${session.count}", style = AppType.d1, color = t.text)
                        AppIconButton(AppIcons.plus, "Add one", { if (session.add(0.5f, 0.5f)) version++ }, surface = true)
                        Spacer(Modifier.weight(1f))
                        AppButton("Undo", { if (session.undo()) version++ }, size = ButtonSize.SMALL, variant = ButtonVariant.GHOST, icon = AppIcons.undo, enabled = session.canUndo)
                    }
                    Text("Detected ${session.detectedCount} · you added ${session.added} · removed ${session.removed}", style = AppType.cap, color = t.muted)
                }
            }
            if (session.tooMany) Banner(BadgeKind.WARN, "Over ${session.cap} items", "Counting stops at ${session.cap}. Split the photo into parts for larger counts.")
            if (result.detections.isEmpty()) Banner(BadgeKind.INFO, "Nothing detected", "The model knows about 80 common objects. Tap the photo to count by hand.")
            else Banner(BadgeKind.INFO, "Check before you rely on it", "Overlapping or unusual items are often missed. Your corrections are what gets saved.")
            AppButton("Count again", { onAction(ResultAction.ScanAgain) }, variant = ButtonVariant.GHOST, fill = true)
        }
    }
}
