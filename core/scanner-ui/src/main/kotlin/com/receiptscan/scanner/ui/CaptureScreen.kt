package com.receiptscan.scanner.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageProxy
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.receiptscan.capture.CameraHandle
import com.receiptscan.capture.CameraPreview
import com.receiptscan.capture.FloatRect
import com.receiptscan.capture.Images
import com.receiptscan.capture.PermissionStatus
import com.receiptscan.capture.QualityMeter
import com.receiptscan.capture.UnreadableImageException
import com.receiptscan.capture.openAppSettings
import com.receiptscan.capture.quality
import com.receiptscan.capture.rememberCameraPermission
import com.receiptscan.capture.rememberDocumentPicker
import com.receiptscan.capture.rememberImagePicker
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppType
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.LocalReduceMotion
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.skin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Guidance shown in the pill and announced politely to screen readers. */
data class Guidance(val text: String, val locked: Boolean = false)

/** Converts frame measurements into the handover's guidance cycle. */
class GuidanceTracker(private val noun: String) {
    private var lastPaper: FloatRect? = null
    private var steadyFrames = 0

    fun update(q: com.receiptscan.capture.FrameQuality): Guidance {
        val steady = QualityMeter.steady(lastPaper, q.paper)
        lastPaper = q.paper
        steadyFrames = if (steady && q.paperFillsFrame && !q.isBlurry) steadyFrames + 1 else 0
        return when {
            q.isDark -> Guidance("More light needed")
            q.paper == null -> Guidance("Find the $noun")
            q.paperTooSmall -> Guidance("Move closer")
            q.isBlurry || !steady -> Guidance("Hold steady")
            steadyFrames >= 3 -> Guidance("Hold steady · edges found", locked = true)
            else -> Guidance("Hold steady")
        }
    }

    /** Frames the camera has been locked on the page (about 10 per second). */
    val lockedFrames get() = steadyFrames
}

/**
 * Shared camera chrome (handover 4: dark screen, radius-28 viewfinder, edge outline, guidance pill,
 * 80 dp shutter, 52 dp side buttons). Handles the camera pre-prompt, permanent denial and import.
 */
@Composable
fun CaptureScreen(
    info: ModuleInfo,
    host: ScannerHost,
    input: ScanInput,
    onCaptured: (List<Bitmap>) -> Unit,
    onCancel: () -> Unit,
    noun: String = "page",
    autoCaptureAvailable: Boolean = false,
    allowPdf: Boolean = false,
    allowMultipleImport: Boolean = false,
    measureGuidance: Boolean = true,
    analyzer: ((ImageProxy) -> Unit)? = null,
    hint: String? = null,
    topExtra: @Composable () -> Unit = {},
    modeChips: @Composable () -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    bottomExtra: @Composable () -> Unit = {},
    shutterEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduceMotion = LocalReduceMotion.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var handle by remember { mutableStateOf<CameraHandle?>(null) }
    var torch by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(autoCaptureAvailable) }
    var guidance by remember { mutableStateOf(Guidance(hint ?: "Find the $noun")) }
    var showTips by remember { mutableStateOf(input.showEducation && info.tips.isNotEmpty()) }
    val tracker = remember { GuidanceTracker(noun) }
    var autoFired by remember { mutableIntStateOf(0) }
    val permission = rememberCameraPermission(host.cameraAskedBefore) { host.onCameraAsked() }

    BackHandler(onBack = onCancel)

    val autoState = rememberUpdatedState(auto)
    val externalAnalyzer = rememberUpdatedState(analyzer)
    val qualityAnalyzer: ((ImageProxy) -> Unit)? = remember(measureGuidance, analyzer != null) {
        if (!measureGuidance && analyzer == null) null else { proxy: ImageProxy ->
            externalAnalyzer.value?.invoke(proxy)
            if (measureGuidance) {
                val g = tracker.update(proxy.quality())
                guidance = g
                if (autoState.value && g.locked && tracker.lockedFrames == 8) autoFired++
            }
        }
    }

    fun deliver(load: suspend () -> List<Bitmap>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val bitmaps = load()
                if (bitmaps.isEmpty()) error = "Nothing was imported" else onCaptured(bitmaps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnreadableImageException) {
                error = e.message ?: "Couldn't open that file"
            } catch (e: Exception) {
                error = "Couldn't take the photo. Try again or import one."
            } finally {
                busy = false
            }
        }
    }

    val pickImages = rememberImagePicker(multiple = allowMultipleImport) { uris: List<Uri> ->
        deliver { uris.map { Images.load(context, it) } }
    }
    val pickPdf = rememberDocumentPicker(arrayOf("application/pdf", "image/*")) { uri ->
        deliver {
            val type = context.contentResolver.getType(uri) ?: ""
            if (type.contains("pdf")) Images.renderPdf(context, uri) else listOf(Images.load(context, uri))
        }
    }
    val importAction: () -> Unit = if (allowPdf) pickPdf else pickImages

    fun shoot() {
        val h = handle ?: return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        deliver {
            val f = host.temp.newTempFile("capture-", ".jpg")
            try {
                listOf(Images.load(h.capture(f)))
            } finally {
                host.temp.release(f)
            }
        }
    }

    val t = skin
    Box(Modifier.fillMaxSize().background(t.cameraBg)) {
        when (permission.status) {
            PermissionStatus.GRANTED -> {
                CameraPreview(Modifier.fillMaxSize(), qualityAnalyzer) { handle = it }
                LaunchedEffect(autoFired) { if (autoFired > 0 && !busy) shoot() }
            }
            else -> PermissionPanel(
                status = permission.status,
                info = info,
                onContinue = { permission.request() },
                onImport = importAction,
                onSettings = { openAppSettings(context) },
            )
        }

        // Viewfinder outline
        if (permission.granted) {
            val edge by animateColorAsState(if (guidance.locked) t.edgeLocked else t.edgeSearching, tween(if (reduceMotion) 0 else 200), label = "edge")
            Box(
                Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 120.dp, bottom = 230.dp)
                    .border(2.dp, edge, RoundedCornerShape(28.dp)),
            )
        }
        Box(Modifier.fillMaxSize()) { overlay() }

        // Top chrome
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CameraIcon(AppIcons.close, "Close", onCancel)
                Text(info.title, style = AppType.t1, color = Color.White, modifier = Modifier.weight(1f).padding(start = 4.dp))
                topExtra()
                if (handle?.hasFlash == true) CameraIcon(AppIcons.flash, if (torch) "Turn off light" else "Turn on light", { torch = !torch; handle?.setTorch(torch) }, active = torch)
                if (info.tips.isNotEmpty()) CameraIcon(AppIcons.help, "Tips", { showTips = true })
            }
            if (permission.granted) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        error ?: guidance.text,
                        style = AppType.lab,
                        color = Color.White,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0x99000000))
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }

        // Bottom chrome
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            modeChips()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SideButton(AppIcons.gallery, if (allowPdf) "Import photo or PDF" else "Import photo", importAction)
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(Color.White.copy(alpha = if (permission.granted && shutterEnabled) 1f else 0.38f))
                        .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        .clickable(enabled = permission.granted && !busy && shutterEnabled, role = Role.Button) { shoot() }
                        .semantics { contentDescription = "Take photo" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(28.dp), color = t.primary, strokeWidth = 3.dp)
                }
                if (autoCaptureAvailable) {
                    SideButton(AppIcons.bolt, if (auto) "Auto capture on" else "Auto capture off", { auto = !auto }, active = auto, label = if (auto) "Auto" else "Manual")
                } else bottomExtra()
            }
        }

        if (showTips) {
            EducationSheet(info, onDismiss = { showTips = false; host.dismissEducation(info.id) })
        }
    }
}

@Composable
private fun CameraIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, active: Boolean = false) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (active) Color.White.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = Color.White) }
}

@Composable
private fun SideButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit, active: Boolean = false, label: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = if (active) 0.3f else 0.14f))
                .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Color.White) }
        if (label != null) Text(label, style = AppType.cap, color = Color.White)
    }
}

/** R-Permission pre-prompt and the "Camera is off" state. */
@Composable
fun PermissionPanel(status: PermissionStatus, info: ModuleInfo, onContinue: () -> Unit, onImport: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ModuleTile(info.color, AppIcons.camera, size = 72.dp)
        Spacer(Modifier.height(20.dp))
        if (status == PermissionStatus.PERMANENTLY_DENIED) {
            Text("Camera is off", style = AppType.h2, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text("Turn on camera access in Android settings, or import a photo instead.", style = AppType.b2, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            AppButton("Open settings", onSettings, fill = true)
            Spacer(Modifier.height(8.dp))
            AppButton("Import", onImport, variant = ButtonVariant.GHOST, fill = true)
        } else {
            Text("Use the camera to scan", style = AppType.h2, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                "Photos are read on this phone and never uploaded. Android will ask you to allow the camera next.",
                style = AppType.b2, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            AppButton("Continue", onContinue, fill = true)
            Spacer(Modifier.height(8.dp))
            AppButton("Import instead", onImport, variant = ButtonVariant.GHOST, fill = true)
        }
    }
}

/** Thumbnail of a source photo; tapping opens it full screen. */
@Composable
fun SourcePhoto(bitmap: Bitmap?, modifier: Modifier = Modifier, label: String = "View photo") {
    if (bitmap == null) return
    var open by remember { mutableStateOf(false) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(skin.surface2).clickable { open = true }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(image, null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Text(label, style = AppType.lab, color = skin.primary)
    }
    if (open) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { open = false }) {
                Image(image, "Source photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
    }
}
