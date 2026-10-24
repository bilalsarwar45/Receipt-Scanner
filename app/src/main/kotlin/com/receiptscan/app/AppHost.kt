package com.receiptscan.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.receiptscan.capture.SessionTempStorage
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.skin
import com.receiptscan.ocr.MlKitTextRecognizer
import com.receiptscan.ocr.TextRecognizerEngine
import com.receiptscan.packs.PackState
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.HostCapabilities
import com.receiptscan.scanner.contracts.TempStorage
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ScannerHost
import kotlinx.coroutines.launch
import java.time.Clock

/** The app's implementation of the module host contract. */
class AppScannerHost(private val graph: AppGraph, override val temp: TempStorage) : ScannerHost {
    override val capabilities = HostCapabilities()
    override val clock: Clock get() = graph.clock
    override val ocr: TextRecognizerEngine by lazy { MlKitTextRecognizer() }

    /** Evidence photos are kept only when "Keep photos" is on. */
    override suspend fun keepImage(bitmap: Bitmap): String? = if (graph.current.keepPhotos) graph.images.save(bitmap) else null
    override fun dismissEducation(module: CapabilityId) { graph.scope.launch { graph.prefs.dismissTip(module.value) } }
    override val cameraAskedBefore: Boolean get() = graph.current.cameraAsked
    override fun onCameraAsked() { graph.scope.launch { graph.prefs.setCameraAsked() } }

    @Composable
    override fun PackGate(module: ModuleInfo, packIds: List<String>, onCancel: () -> Unit, content: @Composable () -> Unit) {
        val id = packIds.firstOrNull { graph.packs.spec(it)?.platformManaged == false }
        if (id == null) { content(); return }
        val state by remember(id) { graph.packs.observe(id) }.collectAsState(graph.packs.state(id))
        if (state is PackState.Ready) content() else PackScreen(graph, module, id, state, onCancel)
    }
}

fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

/** M-Intro (not downloaded) and M-Download (in progress) for one feature pack. */
@Composable
fun PackScreen(graph: AppGraph, module: ModuleInfo, packId: String, state: PackState, onCancel: () -> Unit) {
    val t = skin
    val spec = graph.packs.spec(packId)
    val wifiOnly = graph.current.wifiOnlyDownloads
    ScreenSurface {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AppTopBar(module.title, onBack = onCancel)
            Column(Modifier.weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    is PackState.Downloading, PackState.Verifying -> {
                        val (done, total) = (state as? PackState.Downloading)?.let { it.downloaded to it.total } ?: ((spec?.sizeBytes ?: 0L) to (spec?.sizeBytes ?: 0L))
                        Box(contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.size(120.dp), strokeWidth = 8.dp)
                            Text(if (state == PackState.Verifying) "Checking" else "${(if (total > 0) done * 100 / total else 0)}%", style = AppType.h2, color = t.text)
                        }
                        Text(if (state == PackState.Verifying) "Verifying the download…" else "${mb(done)} of ${mb(total)}", style = AppType.b1, color = t.text)
                        Text("You can leave this screen. The download continues in the background.", style = AppType.b2, color = t.muted, textAlign = TextAlign.Center)
                    }
                    else -> {
                        ModuleTile(module.color, module.icon)
                        Text(module.title, style = AppType.h1, color = t.text)
                        Text(module.subtitle, style = AppType.b2, color = t.muted, textAlign = TextAlign.Center)
                        module.tips.take(3).forEachIndexed { i, tip -> Text("${i + 1}. $tip", style = AppType.b2, color = t.text) }
                        Spacer(Modifier.height(8.dp))
                        spec?.let { Text("Needs a one-time ${mb(it.sizeBytes)} feature pack · ${it.license}", style = AppType.cap, color = t.muted, textAlign = TextAlign.Center) }
                        when (state) {
                            is PackState.Failed -> Banner(BadgeKind.ERR, "Download didn't finish", state.reason)
                            is PackState.NeedsSpace -> Banner(BadgeKind.WARN, "Not enough space", "Needs ${mb(state.requiredBytes)} free; ${mb(state.freeBytes)} available. Free up space in Settings → Data.")
                            PackState.Paused -> Banner(BadgeKind.INFO, "Paused", "Resume to continue where it stopped.")
                            is PackState.Unavailable -> Banner(BadgeKind.WARN, "Not available", state.reason)
                            else -> {}
                        }
                        if (wifiOnly) Text("Downloads wait for Wi‑Fi (change in Settings → Feature packs).", style = AppType.cap, color = t.muted, textAlign = TextAlign.Center)
                    }
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is PackState.Downloading, PackState.Verifying -> {
                        AppButton("Pause", { graph.packs.pause(packId) }, variant = ButtonVariant.TONAL, fill = true, icon = AppIcons.pause)
                        AppButton("Cancel download", { graph.packs.cancel(packId) }, variant = ButtonVariant.GHOST, fill = true)
                    }
                    is PackState.Unavailable -> AppButton("Back", onCancel, fill = true)
                    else -> {
                        AppButton(if (state == PackState.Paused) "Resume" else "Download and start", { graph.packs.download(packId, wifiOnly) }, fill = true, icon = AppIcons.download)
                        AppButton("Not now", onCancel, variant = ButtonVariant.GHOST, fill = true)
                    }
                }
            }
        }
    }
}

fun newTempStorage(graph: AppGraph) = SessionTempStorage(graph.context)
