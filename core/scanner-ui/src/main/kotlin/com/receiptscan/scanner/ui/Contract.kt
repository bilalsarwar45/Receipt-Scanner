package com.receiptscan.scanner.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import com.receiptscan.design.ModuleColor
import com.receiptscan.money.Currencies
import com.receiptscan.money.CurrencySpec
import com.receiptscan.ocr.MlKitTextRecognizer
import com.receiptscan.ocr.TextRecognizerEngine
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.HostCapabilities
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.TempStorage
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** What the host passes to a scanner (handover section 10). The theme comes from the host's ReceiptScanTheme. */
data class ScanInput(
    val source: ScanSource = ScanSource.Camera,
    val locale: Locale = Locale.getDefault(),
    val currency: CurrencySpec = Currencies.PKR,
    val zone: ZoneId = ZoneId.systemDefault(),
    val showEducation: Boolean = true,
)

/** Actions a result screen asks the host to perform. The host decides; modules never write to the Library. */
sealed interface ResultAction {
    data class Save(val envelope: ScanEnvelope) : ResultAction
    data class Copy(val text: String) : ResultAction
    data class Share(val text: String, val subject: String? = null) : ResultAction
    data class OpenLink(val url: String) : ResultAction
    data class Remind(val title: String, val body: String, val at: LocalDateTime, val channel: String, val recordHint: String? = null) : ResultAction
    /** Hand recognised text to another capability (e.g. Text → Translate). Hosts that don't embed it ignore this. */
    data class HandOff(val target: CapabilityId, val source: ScanSource) : ResultAction
    data object ScanAgain : ResultAction
    data object Done : ResultAction
    /** Module-specific action with a serialized payload, e.g. "wifi.connect". */
    data class Custom(val id: String, val payload: String) : ResultAction
}

/** Services the host provides to modules. Every optional ability can be switched off through [capabilities]. */
interface ScannerHost {
    val capabilities: HostCapabilities
    val temp: TempStorage
    val clock: Clock get() = Clock.systemDefaultZone()
    val ocr: TextRecognizerEngine
    /** Keeps an evidence image the host may store with a saved result; returns its id, or null if the host keeps none. */
    suspend fun keepImage(bitmap: Bitmap): String? = null
    fun dismissEducation(module: CapabilityId) {}
    /** True when the host has already asked for camera permission once (to tell permanent denial apart). */
    val cameraAskedBefore: Boolean get() = false
    fun onCameraAsked() {}
    /** Host-provided feature-pack gate (M-Intro / M-Download). Default: no packs managed, show content. */
    @Composable fun PackGate(module: ModuleInfo, packIds: List<String>, onCancel: () -> Unit, content: @Composable () -> Unit) { content() }
}

/** Display metadata a host uses for tiles and headers. */
data class ModuleInfo(
    val id: CapabilityId,
    val title: String,
    val subtitle: String,
    val color: ModuleColor,
    val icon: ImageVector,
    val tips: List<String>,
    val requiredPacks: List<String> = emptyList(),
)

/** One embeddable scanner (handover section 10). [R] is owned by the module. */
interface ScannerModule<R : Any> {
    val info: ModuleInfo
    val id: CapabilityId get() = info.id

    @Composable fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<R>) -> Unit, onCancel: () -> Unit)

    @Composable fun Result(result: R, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit)
}

/** Simple host for samples and tests: ML Kit OCR, session temp storage, no persistence. */
open class BasicScannerHost(
    override val temp: TempStorage,
    override val capabilities: HostCapabilities = HostCapabilities(),
    override val ocr: TextRecognizerEngine = MlKitTextRecognizer(),
) : ScannerHost

val LocalScannerHost = staticCompositionLocalOf<ScannerHost?> { null }
