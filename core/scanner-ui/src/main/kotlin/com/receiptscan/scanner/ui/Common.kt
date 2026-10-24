package com.receiptscan.scanner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppProgress
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.EmptyState
import com.receiptscan.design.InlineBanner
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.skin
import com.receiptscan.ocr.OcrInput
import com.receiptscan.ocr.model.OcrDocument
import com.receiptscan.scanner.contracts.FailReason
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** First-use tips (handover 8.3): at most three short lines, dismissible, never multi-page. */
@Composable
fun EducationSheet(info: ModuleInfo, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val t = skin
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = t.card, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModuleTile(info.color, info.icon, size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(info.title, style = AppType.h2, color = t.text)
                    Text("Quick tips", style = AppType.cap, color = t.muted)
                }
            }
            Spacer(Modifier.height(16.dp))
            info.tips.take(3).forEach { tip ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(AppIcons.check, null, tint = t.ok, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tip, style = AppType.b1, color = t.text)
                }
            }
            Spacer(Modifier.height(16.dp))
            AppButton("Got it", onDismiss, fill = true)
        }
    }
}

/** A determinate step list with linear progress (R-Processing pattern). */
@Composable
fun ProcessingView(title: String, steps: List<String>, current: Int, onCancel: (() -> Unit)?, detail: String? = null) {
    val t = skin
    if (onCancel != null) BackHandler(onBack = onCancel)
    ScreenSurface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = AppType.h1, color = t.text)
            Spacer(Modifier.height(16.dp))
            AppProgress(((current + 0.5f) / steps.size.coerceAtLeast(1)))
            Spacer(Modifier.height(20.dp))
            steps.forEachIndexed { i, s ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when {
                        i < current -> AppIcons.checkCircle to t.ok
                        i == current -> AppIcons.clock to t.primary
                        else -> AppIcons.clock to t.muted.copy(alpha = 0.5f)
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(s, style = if (i == current) AppType.t1 else AppType.b1, color = if (i <= current) t.text else t.muted)
                }
            }
            if (detail != null) {
                Spacer(Modifier.height(8.dp))
                Text(detail, style = AppType.cap, color = t.muted)
            }
            if (onCancel != null) {
                Spacer(Modifier.height(24.dp))
                AppButton("Cancel", onCancel, variant = ButtonVariant.OUTLINE, fill = true)
            }
        }
    }
}

/** Recovery copy for every failure (handover 8.4): title, what still works, one primary action. */
fun failureCopy(reason: FailReason, detail: String? = null): Pair<String, String> = when (reason) {
    FailReason.NO_TEXT -> "No text found" to "Try again closer, with more light, or import a clearer photo."
    FailReason.BLURRY -> "The photo is blurry" to "Hold the phone steady and try again. Importing a photo also works."
    FailReason.UNSUPPORTED -> "Not supported" to (detail ?: "This content isn't supported by this scanner. Other tools still work.")
    FailReason.PERMISSION_DENIED -> "Camera is off" to "Turn on camera access in Android settings, or import a photo instead."
    FailReason.PACK_MISSING -> "Feature pack needed" to (detail ?: "Download the pack once to use this offline. Other tools still work.")
    FailReason.PROVIDER_UNAVAILABLE -> "Not available on this phone" to (detail ?: "This feature can't run on this device. Other tools still work.")
    FailReason.STORAGE_FULL -> "Storage is full" to "Free up some space, then try again. Your saved items are safe."
    FailReason.UNREADABLE_FILE -> "Couldn't open that file" to (detail ?: "Pick a JPG, PNG or PDF that isn't password protected.")
    FailReason.CANCELLED -> "Cancelled" to "Nothing was saved."
}

@Composable
fun FailureView(info: ModuleInfo, reason: FailReason, detail: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    val (title, body) = failureCopy(reason, detail)
    BackHandler(onBack = onClose)
    ScreenSurface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            AppTopBar(info.title, onBack = onClose)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(if (reason == FailReason.PACK_MISSING) AppIcons.download else AppIcons.alert, title, body, actionLabel = "Try again", onAction = onRetry, secondaryLabel = "Close", onSecondary = onClose)
            }
        }
    }
}

/** Standard result layout: app bar, scrolling content, sticky bottom actions. */
@Composable
fun ResultScaffold(
    info: ModuleInfo,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    bottom: (@Composable () -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = skin
    BackHandler(onBack = onBack)
    ScreenSurface(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            AppTopBar(title, onBack = onBack, actions = actions)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                content()
                Spacer(Modifier.height(24.dp))
            }
            if (bottom != null) {
                Box(Modifier.fillMaxWidth().background(t.bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) { bottom() }
            }
        }
    }
}

/** Header row with the module tile, used at the top of result screens. */
@Composable
fun ModuleHeader(info: ModuleInfo, title: String, subtitle: String?) {
    val t = skin
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ModuleTile(info.color, info.icon, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.h2, color = t.text)
            if (subtitle != null) Text(subtitle, style = AppType.cap, color = t.muted)
        }
    }
}

@Composable
fun Banner(kind: BadgeKind, title: String, body: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    InlineBanner(kind, title, Modifier.padding(vertical = 6.dp), body, actionLabel, onAction)
}

/** Result of the shared "capture → OCR" step. */
data class OcrCapture(val document: OcrDocument, val images: List<Bitmap>, val sourceIds: List<String>)

/** Runs OCR over [images]. Evidence images are offered to the host; unkept images get session ids. */
suspend fun recognize(host: ScannerHost, images: List<Bitmap>, enhance: Boolean = false): OcrCapture {
    val prepared = if (enhance) images.map { com.receiptscan.capture.Images.enhance(it) } else images
    val ids = images.map { host.keepImage(it) ?: "session-${UUID.randomUUID()}" }
    val doc = host.ocr.recognize(prepared.mapIndexed { i, b -> OcrInput(b, ids[i], i) })
    return OcrCapture(doc, images, ids)
}

/**
 * The shared capture → OCR flow every text-based scanner uses. Handles Camera, Image and PDF sources;
 * [ScanSource.Text] skips the camera and is handled by the module itself.
 */
@Composable
fun OcrScanFlow(
    info: ModuleInfo,
    input: ScanInput,
    host: ScannerHost,
    noun: String,
    onDocument: (OcrCapture) -> Unit,
    onFailed: (ScanOutcome.Failed) -> Unit,
    onCancel: () -> Unit,
    steps: List<String> = listOf("Image cleaned up", "Finding text"),
    allowPdf: Boolean = false,
    enhance: Boolean = false,
    autoCapture: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var images by remember { mutableStateOf<List<Bitmap>?>(null) }
    var step by remember { mutableStateOf(0) }
    val src = input.source
    LaunchedEffect(src) {
        if (images == null && (src is ScanSource.Image || src is ScanSource.Pdf)) {
            images = try {
                when (src) {
                    is ScanSource.Image -> listOf(com.receiptscan.capture.Images.load(context, android.net.Uri.parse(src.uri)))
                    is ScanSource.Pdf -> com.receiptscan.capture.Images.renderPdf(context, android.net.Uri.parse(src.uri))
                    else -> null
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                onFailed(ScanOutcome.Failed(FailReason.UNREADABLE_FILE, e.message)); null
            }
        }
    }
    val current = images
    if (current == null) {
        if (src is ScanSource.Camera) {
            CaptureScreen(info, host, input, onCaptured = { images = it }, onCancel = onCancel, noun = noun, allowPdf = allowPdf, autoCaptureAvailable = autoCapture)
        } else {
            ProcessingView("Opening…", listOf("Opening file"), 0, onCancel)
        }
    } else {
        ProcessingView("Reading", steps, step.coerceAtMost(steps.lastIndex), onCancel = { images = null; if (src !is ScanSource.Camera) onCancel() })
        LaunchedEffect(current) {
            step = 1
            try {
                val res = recognize(host, current, enhance)
                if (res.document.isEmpty) {
                    images = null
                    onFailed(ScanOutcome.Failed(FailReason.NO_TEXT))
                } else onDocument(res)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                images = null
                onFailed(ScanOutcome.Failed(FailReason.PROVIDER_UNAVAILABLE, "Text recognition failed: ${e.message}"))
            }
        }
    }
}

/** Small round icon used in lists of chips/actions. */
@Composable
fun Dot(modifier: Modifier = Modifier) {
    Box(modifier.size(6.dp).clip(CircleShape).background(skin.muted))
}

@Composable
fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = AppType.b2, color = skin.muted, textAlign = TextAlign.Center)
    }
}

/** Runs a scanner end to end: Capture, then Result. Used by hosts that don't need custom routing. */
@Composable
fun <R : Any> ScannerFlow(module: ScannerModule<R>, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onClose: () -> Unit) {
    var outcome by remember { mutableStateOf<ScanOutcome<R>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    when (val o = outcome) {
        null -> androidx.compose.runtime.key(attempt) {
            host.PackGate(module.info, module.info.requiredPacks, onClose) {
                module.Capture(input, host, onResult = { outcome = it }, onCancel = onClose)
            }
        }
        is ScanOutcome.Failed -> if (o.reason == FailReason.CANCELLED) {
            LaunchedEffect(Unit) { onClose() }
        } else FailureView(module.info, o.reason, o.detail, onRetry = { outcome = null; attempt++ }, onClose = onClose)
        is ScanOutcome.Success -> module.Result(o.result, input, host, onAction = { a ->
            if (a == ResultAction.ScanAgain) { outcome = null; attempt++ } else onAction(a)
        }, onBack = { outcome = null; attempt++ })
        is ScanOutcome.NeedsReview -> module.Result(o.result, input, host, onAction = { a ->
            if (a == ResultAction.ScanAgain) { outcome = null; attempt++ } else onAction(a)
        }, onBack = { outcome = null; attempt++ })
    }
}
