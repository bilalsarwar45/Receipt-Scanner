package com.receiptscan.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.receiptscan.ocr.model.Box
import com.receiptscan.ocr.model.OcrBlock
import com.receiptscan.ocr.model.OcrDocument
import com.receiptscan.ocr.model.OcrElement
import com.receiptscan.ocr.model.OcrLine
import com.receiptscan.ocr.model.OcrPage
import kotlinx.coroutines.tasks.await

/** One processed image to recognise. [sourceId] is the host's id for the stored image. */
class OcrInput(val bitmap: Bitmap, val sourceId: String, val pageIndex: Int)

/**
 * Text recognition port. The shipped implementation is ML Kit Text Recognition v2 (Latin script,
 * bundled model, fully on device). Urdu/Arabic script is not supported by this model and is not advertised.
 */
interface TextRecognizerEngine {
    val engine: String
    val engineVersion: String
    val script: String
    suspend fun recognize(input: OcrInput): OcrPage
    suspend fun recognize(inputs: List<OcrInput>): OcrDocument = OcrDocument(inputs.map { recognize(it) }, engine, engineVersion, script)
    fun close() {}
}

class MlKitTextRecognizer : TextRecognizerEngine {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    override val engine = "mlkit-text-recognition-latin"
    override val engineVersion = "16.0.1"
    override val script = "latin"

    override suspend fun recognize(input: OcrInput): OcrPage {
        val text = client.process(InputImage.fromBitmap(input.bitmap, 0)).await()
        return text.toPage(input.sourceId, input.pageIndex, input.bitmap.width, input.bitmap.height)
    }

    override fun close() = client.close()
}

internal fun Rect?.toBox(): Box = if (this == null) Box(0, 0, 0, 0) else Box(left, top, right, bottom)

fun Text.toPage(sourceId: String, pageIndex: Int, width: Int, height: Int): OcrPage = OcrPage(
    sourceId = sourceId,
    pageIndex = pageIndex,
    width = width,
    height = height,
    blocks = textBlocks.map { b ->
        OcrBlock(
            text = b.text,
            box = b.boundingBox.toBox(),
            lines = b.lines.map { l ->
                OcrLine(
                    text = l.text,
                    box = l.boundingBox.toBox(),
                    elements = l.elements.map { e -> OcrElement(e.text, e.boundingBox.toBox(), e.confidence) },
                    confidence = l.confidence,
                    language = l.recognizedLanguage.takeIf { it.isNotBlank() && it != "und" },
                )
            },
        )
    },
)

/** On-device language identification (ML Kit). Returns a BCP-47 tag or null when undetermined. */
object LanguageDetector {
    private val client by lazy { LanguageIdentification.getClient() }

    suspend fun detect(text: String): String? {
        if (text.isBlank()) return null
        val tag = client.identifyLanguage(text.take(2000)).await()
        return tag.takeIf { it != "und" }
    }
}
