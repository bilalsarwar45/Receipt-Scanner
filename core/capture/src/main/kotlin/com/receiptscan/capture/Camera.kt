package com.receiptscan.capture

import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Handle to the bound camera: full-resolution capture and torch. */
class CameraHandle internal constructor(private val controller: LifecycleCameraController, private val context: Context) {
    /** Captures a full-resolution JPEG into [file]. */
    suspend fun capture(file: File): File = suspendCancellableCoroutine { cont ->
        file.parentFile?.mkdirs()
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { if (cont.isActive) cont.resume(file) }
                override fun onError(exception: ImageCaptureException) { if (cont.isActive) cont.resumeWithException(exception) }
            },
        )
    }

    fun setTorch(on: Boolean) { controller.enableTorch(on) }
    val hasFlash: Boolean get() = controller.cameraInfo?.hasFlashUnit() == true
}

/**
 * CameraX preview with image capture and an optional analysis stream (about 10 fps, latest frame only).
 * The analyzer must not hold on to the [ImageProxy]; it is closed after the callback returns.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    analyzer: ((ImageProxy) -> Unit)? = null,
    onReady: (CameraHandle) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or (if (analyzer != null) CameraController.IMAGE_ANALYSIS else 0))
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }
    DisposableEffect(lifecycleOwner, analyzer) {
        if (analyzer != null) {
            var last = 0L
            controller.setImageAnalysisAnalyzer(executor) { proxy ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - last >= 100) { last = now; analyzer(proxy) }
                } finally {
                    proxy.close()
                }
            }
        }
        controller.bindToLifecycle(lifecycleOwner)
        onReady(CameraHandle(controller, context))
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
        }
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                this.controller = controller
            }
        },
    )
}

/** Measures sharpness/brightness/paper bounds from the Y plane of a camera frame. */
fun ImageProxy.quality(): FrameQuality {
    val plane = planes[0]
    val buf = plane.buffer
    val bytes = ByteArray(buf.remaining())
    buf.get(bytes)
    val q = QualityMeter.measure(bytes, width, height, plane.rowStride)
    val p = q.paper ?: return q
    // Rotate the paper box into display orientation.
    val rotated = when (imageInfo.rotationDegrees) {
        90 -> FloatRect(1 - p.bottom, p.left, 1 - p.top, p.right)
        180 -> FloatRect(1 - p.right, 1 - p.bottom, 1 - p.left, 1 - p.top)
        270 -> FloatRect(p.top, 1 - p.right, p.bottom, 1 - p.left)
        else -> p
    }
    return q.copy(paper = rotated)
}

/** Converts an analysis frame to an upright bitmap (used by live translate and counters). */
fun ImageProxy.uprightBitmap(): Bitmap = Images.rotate(toBitmap(), imageInfo.rotationDegrees)
