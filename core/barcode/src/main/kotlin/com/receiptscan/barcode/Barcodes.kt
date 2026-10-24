package com.receiptscan.barcode

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/** A decoded code. [raw] is the exact payload; typed handling lives in the scanner modules. */
data class DecodedBarcode(
    val raw: String,
    val format: String,
    val isProductCode: Boolean,
    val isQr: Boolean,
    val box: Rect?,
)

/** Supported formats (ML Kit Barcode Scanning, bundled model). */
enum class CodeSet(internal val formats: IntArray) {
    ALL(intArrayOf(Barcode.FORMAT_ALL_FORMATS)),
    QR_ONLY(intArrayOf(Barcode.FORMAT_QR_CODE)),
    PRODUCT(intArrayOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)),
}

class BarcodeReader(codes: CodeSet = CodeSet.ALL) {
    private val client = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().apply {
            val f = codes.formats
            if (f.size == 1) setBarcodeFormats(f[0]) else setBarcodeFormats(f[0], *f.copyOfRange(1, f.size))
        }.build(),
    )

    suspend fun read(bitmap: Bitmap): List<DecodedBarcode> = client.process(InputImage.fromBitmap(bitmap, 0)).await().mapNotNull { it.toDecoded() }

    /** Decodes a camera frame synchronously-ish; call from the analysis executor. */
    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    fun read(proxy: ImageProxy, onResult: (List<DecodedBarcode>) -> Unit) {
        val media = proxy.image ?: return
        val task = client.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
        // Wait so the frame stays valid until ML Kit is done with it.
        val result = runCatching { com.google.android.gms.tasks.Tasks.await(task) }.getOrNull() ?: return
        onResult(result.mapNotNull { it.toDecoded() })
    }

    fun close() = client.close()
}

private fun Barcode.toDecoded(): DecodedBarcode? {
    val raw = rawValue ?: displayValue ?: return null
    val name = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        else -> "Barcode"
    }
    val product = format in setOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
    return DecodedBarcode(raw, name, product, format == Barcode.FORMAT_QR_CODE, boundingBox)
}
