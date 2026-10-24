package com.receiptscan.scanner.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.receiptscan.capture.Images
import com.receiptscan.scanner.contracts.FailReason
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import kotlinx.coroutines.CancellationException

/**
 * Photo-only flow for model-backed scanners (counter, plants, food): camera or imported image, then
 * [process] runs off the main thread while a processing screen shows [steps].
 */
@Composable
fun ImageScanFlow(
    info: ModuleInfo,
    input: ScanInput,
    host: ScannerHost,
    noun: String,
    steps: List<String>,
    process: suspend (Bitmap) -> Unit,
    onFailed: (ScanOutcome.Failed) -> Unit,
    onCancel: () -> Unit,
    hint: String? = null,
) {
    val context = LocalContext.current
    var image by remember { mutableStateOf<Bitmap?>(null) }
    var step by remember { mutableStateOf(0) }
    val src = input.source
    LaunchedEffect(src) {
        if (image == null && src is ScanSource.Image) {
            image = try { Images.load(context, android.net.Uri.parse(src.uri)) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                onFailed(ScanOutcome.Failed(FailReason.UNREADABLE_FILE, e.message)); null
            }
        }
    }
    val current = image
    if (current == null) {
        if (src is ScanSource.Camera) CaptureScreen(info, host, input, onCaptured = { image = it.firstOrNull() }, onCancel = onCancel, noun = noun, measureGuidance = false, hint = hint)
        else ProcessingView("Opening…", listOf("Opening file"), 0, onCancel)
    } else {
        ProcessingView("Looking", steps, step.coerceAtMost(steps.lastIndex), onCancel = { image = null; if (src !is ScanSource.Camera) onCancel() })
        LaunchedEffect(current) {
            step = 1
            try { process(current) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                image = null
                onFailed(ScanOutcome.Failed(FailReason.PROVIDER_UNAVAILABLE, e.message))
            }
        }
    }
}
