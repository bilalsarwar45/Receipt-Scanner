package com.receiptscan.ml

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Where a model comes from: an APK asset or a verified pack file. */
sealed interface ModelSource {
    data class Asset(val path: String) : ModelSource
    data class FileSource(val file: File) : ModelSource
}

/** Runtime availability. A missing native library or a bad model never crashes the app. */
sealed interface RuntimeStatus {
    data object Ready : RuntimeStatus
    data class Unavailable(val reason: String) : RuntimeStatus
}

class ModelUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

private fun baseOptions(context: Context, source: ModelSource): BaseOptions {
    val b = BaseOptions.builder().setDelegate(Delegate.CPU)
    when (source) {
        is ModelSource.Asset -> b.setModelAssetPath(source.path)
        is ModelSource.FileSource -> b.setModelAssetBuffer(mapFile(source.file))
    }
    return b.build()
}

private fun mapFile(f: File): ByteBuffer = FileInputStream(f).use { s -> s.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length()) }

fun assetExists(context: Context, path: String): Boolean = runCatching { context.assets.open(path).close(); true }.getOrDefault(false)

data class DetectedObject(
    /** Normalised 0..1 box in the input bitmap. */
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val label: String,
    val score: Float,
)

/**
 * MediaPipe Tasks object detector (EfficientDet-Lite0, COCO classes). EfficientDet-Lite0 returns at
 * most 25 objects per pass, so crowded images are also run as overlapping tiles and merged by the caller.
 */
class ObjectDetectorRuntime private constructor(private val detector: ObjectDetector) {
    fun detect(bitmap: Bitmap): List<DetectedObject> {
        val result = detector.detect(BitmapImageBuilder(bitmap).build())
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        return result.detections().mapNotNull { d ->
            val c = d.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
            val r: RectF = d.boundingBox()
            DetectedObject(r.left / w, r.top / h, r.right / w, r.bottom / h, c.categoryName(), c.score())
        }
    }

    /** Runs the full frame plus a 2×2 grid of overlapping tiles; boxes are returned in full-frame coordinates. */
    suspend fun detectTiled(bitmap: Bitmap): List<DetectedObject> = withContext(Dispatchers.Default) {
        val out = detect(bitmap).toMutableList()
        val tw = (bitmap.width * 0.6f).toInt(); val th = (bitmap.height * 0.6f).toInt()
        val xs = listOf(0, bitmap.width - tw); val ys = listOf(0, bitmap.height - th)
        for (x in xs) for (y in ys) {
            val tile = Bitmap.createBitmap(bitmap, x, y, tw, th)
            detect(tile).forEach { d ->
                out += DetectedObject(
                    (x + d.left * tw) / bitmap.width, (y + d.top * th) / bitmap.height,
                    (x + d.right * tw) / bitmap.width, (y + d.bottom * th) / bitmap.height, d.label, d.score,
                )
            }
        }
        out
    }

    fun close() = detector.close()

    companion object {
        fun create(context: Context, source: ModelSource, scoreThreshold: Float = 0.3f): ObjectDetectorRuntime = try {
            val opts = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions(context, source))
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(scoreThreshold)
                .setMaxResults(100)
                .build()
            ObjectDetectorRuntime(ObjectDetector.createFromOptions(context, opts))
        } catch (t: Throwable) {
            throw ModelUnavailableException(t.message ?: "Object detector could not start", t)
        }
    }
}

data class ClassLabel(val index: Int, val name: String, val displayName: String?, val score: Float)

/** MediaPipe Tasks image classifier for specialist plant and food models with embedded label metadata. */
class ImageClassifierRuntime private constructor(private val classifier: ImageClassifier) {
    fun classify(bitmap: Bitmap): List<ClassLabel> {
        val result = classifier.classify(BitmapImageBuilder(bitmap).build())
        val head = result.classificationResult().classifications().firstOrNull() ?: return emptyList()
        return head.categories().map { ClassLabel(it.index(), it.categoryName(), it.displayName().ifBlank { null }, it.score()) }
    }

    fun close() = classifier.close()

    companion object {
        fun create(context: Context, source: ModelSource, maxResults: Int = 5): ImageClassifierRuntime = try {
            val opts = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(baseOptions(context, source))
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(maxResults)
                .build()
            ImageClassifierRuntime(ImageClassifier.createFromOptions(context, opts))
        } catch (t: Throwable) {
            throw ModelUnavailableException(t.message ?: "Classifier could not start", t)
        }
    }
}
