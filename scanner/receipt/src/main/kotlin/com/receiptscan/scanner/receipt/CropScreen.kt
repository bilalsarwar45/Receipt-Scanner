package com.receiptscan.scanner.receipt

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.receiptscan.capture.Images
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.skin

/** Normalised corner positions TL, TR, BR, BL. */
data class Corners(val pts: List<Offset>) {
    fun toPixels(b: Bitmap) = pts.map { PointF(it.x * b.width, it.y * b.height) }
    val isFull get() = pts == FULL.pts
    companion object {
        val FULL = Corners(listOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f)))
        val INSET = Corners(listOf(Offset(0.04f, 0.03f), Offset(0.96f, 0.03f), Offset(0.96f, 0.97f), Offset(0.04f, 0.97f)))
    }
}

/**
 * R-Crop / Adjust: corner handles, Rotate, Enhance (on by default), "Add another image to this receipt",
 * "Add another receipt" and "Read receipt". Long receipts (already stitched) skip the corner handles.
 */
@Composable
fun CropScreen(
    image: Bitmap,
    pageCount: Int,
    allowCorners: Boolean,
    enhanceDefault: Boolean,
    onRead: (Bitmap, enhance: Boolean, queueAnother: Boolean) -> Unit,
    onAddPage: (Bitmap) -> Unit,
    onRetake: () -> Unit,
) {
    val t = skin
    BackHandler(onBack = onRetake)
    var bmp by remember(image) { mutableStateOf(image) }
    var corners by remember(image) { mutableStateOf(if (allowCorners) Corners.INSET else Corners.FULL) }
    var enhance by remember { mutableStateOf(enhanceDefault) }
    fun result(): Bitmap = if (!allowCorners || corners.isFull) bmp else Images.warp(bmp, corners.toPixels(bmp))

    ScreenSurface {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AppTopBar(if (pageCount > 0) "Adjust · page ${pageCount + 1}" else "Adjust", onBack = onRetake)
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                val ratio = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
                BoxWithConstraints(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)) {
                    Image(bmp.asImageBitmap(), "Receipt photo", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    if (allowCorners) {
                        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
                        Canvas(Modifier.fillMaxSize()) {
                            val p = Path().apply {
                                corners.pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x * size.width, o.y * size.height) else lineTo(o.x * size.width, o.y * size.height) }
                                close()
                            }
                            drawPath(p, Color(0xFF4A56E8), style = Stroke(width = 3.dp.toPx()))
                        }
                        corners.pts.forEachIndexed { i, o ->
                            Box(
                                Modifier.offset(maxWidth * o.x - 22.dp, maxHeight * o.y - 22.dp).size(44.dp)
                                    .semantics { contentDescription = listOf("Top left", "Top right", "Bottom right", "Bottom left")[i] + " corner" }
                                    .pointerInput(i, w, h) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            val cur = corners.pts[i]
                                            val next = Offset((cur.x + drag.x / w).coerceIn(0f, 1f), (cur.y + drag.y / h).coerceIn(0f, 1f))
                                            corners = Corners(corners.pts.toMutableList().also { it[i] = next })
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White).border(3.dp, Color(0xFF4A56E8), CircleShape)) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip("Rotate", { bmp = Images.rotate(bmp, 90); corners = if (allowCorners) Corners.INSET else Corners.FULL }, icon = AppIcons.rotate)
                if (allowCorners) AppChip("Reset crop", { corners = Corners.FULL }, icon = AppIcons.crop)
                AppChip("Enhance", { enhance = !enhance }, selected = enhance, icon = AppIcons.enhance)
            }
            Text("Drag the corners to the receipt's edges.", style = AppType.cap, color = t.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Read receipt", { onRead(result(), enhance, false) }, fill = true, icon = AppIcons.check)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Add another image to this receipt", { onAddPage(result()) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, fill = true)
                    AppButton("Add another receipt", { onRead(result(), enhance, true) }, Modifier.weight(1f), variant = ButtonVariant.OUTLINE, fill = true)
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
