package com.receiptscan.feature.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.receiptscan.capture.Images
import com.receiptscan.capture.rememberDocumentPicker
import com.receiptscan.capture.rememberImagePicker
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonSize
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ChipStyle
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.skin
import com.receiptscan.ocr.OcrInput
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.CaptureScreen
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ProcessingView
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One captured page, kept on disk; only a small thumbnail stays in memory. */
data class DocPage(val id: String, val file: File, val thumb: Bitmap, val rotation: Int = 0)

/** A finished PDF. The host stores the record; [file] is in [outputDir]. */
data class SavedDocument(
    val id: String, val title: String, val folder: String, val mode: DocMode, val pageCount: Int,
    val file: File, val searchable: Boolean, val text: String, val sizeBytes: Long,
)

/**
 * PDF / documents (S-PDF). Integrated feature, not an embeddable scanner: capture or import pages,
 * reorder/rotate/remove, then write a PDF (searchable when OCR ran).
 */
object DocumentsFeature {
    val ID = CapabilityId("pdf")
    val info = ModuleInfo(ID, "PDF", "Scan pages into a PDF", ModuleColor.DOCUMENT, AppIcons.doc,
        listOf("Pick Document, Whiteboard or Book", "Add pages, then drag to reorder", "Searchable PDFs include the recognised text"))

    private const val THUMB = 320

    fun outputDir(context: Context) = File(context.filesDir, "documents").apply { mkdirs() }

    internal suspend fun addPage(host: ScannerHost, bmp: Bitmap): DocPage = withContext(Dispatchers.IO) {
        val f = host.temp.newTempFile("page-", ".jpg")
        Images.saveJpeg(bmp, f, 88)
        DocPage(UUID.randomUUID().toString(), f, Images.scaleToMax(bmp, THUMB))
    }

    internal suspend fun loadPage(p: DocPage): Bitmap = withContext(Dispatchers.IO) {
        val b = BitmapFactory.decodeFile(p.file.path) ?: error("Page image is missing")
        if (p.rotation == 0) b else Images.rotate(b, p.rotation)
    }

    @Composable
    fun Flow(host: ScannerHost, input: ScanInput, onSaved: (SavedDocument) -> Unit, onClose: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val pages = remember { mutableStateListOf<DocPage>() }
        var mode by remember { mutableStateOf(DocMode.DOCUMENT) }
        var camera by remember { mutableStateOf(true) }
        var saving by remember { mutableStateOf<SaveRequest?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var importing by remember { mutableStateOf(false) }

        val pickImages = rememberImagePicker(multiple = true) { uris ->
            scope.launch {
                importing = true
                try { uris.take(50).forEach { u -> PageProcessor.split(mode, Images.load(context, u)).forEach { pages += addPage(host, it) } } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = "Couldn't open one of the images: ${e.message}" }
                importing = false; camera = false
            }
        }
        val pickPdf = rememberDocumentPicker(arrayOf("application/pdf")) { uri ->
            scope.launch {
                importing = true
                try { Images.renderPdf(context, uri, maxPages = 30, targetWidth = 1400).forEach { pages += addPage(host, it) } }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't open that PDF" }
                importing = false; camera = false
            }
        }

        val req = saving
        when {
            req != null -> SaveProgress(host, req, pages, onDone = { doc -> pages.forEach { host.temp.release(it.file) }; onSaved(doc) }, onFailed = { msg -> saving = null; error = msg })
            importing -> ProcessingView("Adding pages…", listOf("Opening files"), 0, null)
            camera -> CaptureScreen(
                info, host, input,
                onCaptured = { shots -> scope.launch { shots.forEach { s -> PageProcessor.split(mode, s).forEach { pages += addPage(host, it) } } } },
                onCancel = { if (pages.isEmpty()) onClose() else camera = false },
                noun = "page",
                autoCaptureAvailable = true,
                hint = if (pages.isEmpty()) null else "${pages.size} page${if (pages.size == 1) "" else "s"} · keep going or tap Done",
                modeChips = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DocMode.entries.forEach { m -> AppChip(m.label, { mode = m }, selected = mode == m, style = ChipStyle.TONAL) }
                    }
                },
                bottomExtra = { if (pages.isNotEmpty()) AppButton("Done · ${pages.size}", { camera = false }, size = ButtonSize.SMALL) },
            )
            else -> PagesScreen(pages, mode, error, onDismissError = { error = null }, onAddCamera = { camera = true }, onAddImages = { pickImages() }, onAddPdf = { pickPdf() },
                onSave = { r -> saving = r }, onClose = { pages.forEach { host.temp.release(it.file) }; onClose() })
        }
    }
}

data class SaveRequest(val title: String, val folder: String, val mode: DocMode, val enhance: Boolean, val searchable: Boolean)

@Composable
private fun PagesScreen(
    pages: MutableList<DocPage>, mode: DocMode, error: String?, onDismissError: () -> Unit,
    onAddCamera: () -> Unit, onAddImages: () -> Unit, onAddPdf: () -> Unit, onSave: (SaveRequest) -> Unit, onClose: () -> Unit,
) {
    val t = skin
    var title by remember { mutableStateOf("Scan ${java.time.LocalDate.now()}") }
    var folder by remember { mutableStateOf("Documents") }
    var searchable by remember { mutableStateOf(true) }
    var enhance by remember { mutableStateOf(true) }
    BackHandler(onBack = onClose)
    ScreenSurface {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AppTopBar("${mode.label} · ${pages.size} page${if (pages.size == 1) "" else "s"}", onBack = onClose)
            LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) item(span = { GridItemSpan(maxLineSpan) }) { Banner(BadgeKind.ERR, "Something went wrong", error, "Dismiss", onDismissError) }
                if (pages.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Banner(BadgeKind.INFO, "No pages yet", "Add pages with the camera or import images or a PDF.") }
                itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
                    AppCard(Modifier.fillMaxWidth(), padding = PaddingValues(8.dp)) {
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(8.dp))) {
                                Image(p.thumb.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize().rotate(p.rotation.toFloat()), contentScale = ContentScale.Fit)
                            }
                            Text("Page ${i + 1}", style = AppType.cap, color = t.muted)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AppIconButton(AppIcons.back, "Move page ${i + 1} earlier", { if (i > 0) { val x = pages.removeAt(i); pages.add(i - 1, x) } }, enabled = i > 0)
                                AppIconButton(AppIcons.chev, "Move page ${i + 1} later", { if (i < pages.lastIndex) { val x = pages.removeAt(i); pages.add(i + 1, x) } }, enabled = i < pages.lastIndex)
                                AppIconButton(AppIcons.rotate, "Rotate page ${i + 1}", { pages[i] = p.copy(rotation = (p.rotation + 90) % 360) })
                                AppIconButton(AppIcons.trash, "Remove page ${i + 1}", { pages.removeAt(i) })
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppChip("Camera", onAddCamera, icon = AppIcons.camera)
                            AppChip("Images", onAddImages, icon = AppIcons.image)
                            AppChip("PDF", onAddPdf, icon = AppIcons.doc)
                        }
                        AppTextField(title, { title = it.take(80) }, "Name")
                        AppTextField(folder, { folder = it.take(40) }, "Folder")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Searchable", style = AppType.b1, color = t.text); Text("Adds the recognised text so you can search and copy it", style = AppType.cap, color = t.muted) }
                            Switch(searchable, { searchable = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Enhance", style = AppType.b1, color = t.text); Text(if (mode == DocMode.WHITEBOARD) "Boosts marker contrast" else "Clean black-and-white pages", style = AppType.cap, color = t.muted) }
                            Switch(enhance, { enhance = it })
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            Box(Modifier.padding(16.dp)) {
                AppButton("Save PDF", { onSave(SaveRequest(title.trim().ifEmpty { "Scan" }, folder.trim().ifEmpty { "Documents" }, mode, enhance, searchable)) }, fill = true, enabled = pages.isNotEmpty(), disabledReason = "Add at least one page")
            }
        }
    }
}


@Composable
private fun SaveProgress(host: ScannerHost, req: SaveRequest, pages: List<DocPage>, onDone: (SavedDocument) -> Unit, onFailed: (String) -> Unit) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(0) }
    val steps = listOf("Preparing pages", if (req.searchable) "Reading text ($done of ${pages.size})" else "Adding pages ($done of ${pages.size})", "Writing PDF")
    var step by remember { mutableStateOf(0) }
    ProcessingView("Saving PDF", steps, step, null)
    LaunchedEffect(req) {
        val id = UUID.randomUUID().toString()
        val out = File(DocumentsFeature.outputDir(context), "$id.pdf")
        val writer = PdfWriter(out)
        val text = StringBuilder()
        try {
            step = 1
            pages.forEachIndexed { i, p ->
                // One page in memory at a time.
                val raw = DocumentsFeature.loadPage(p)
                val img = withContext(Dispatchers.Default) { PageProcessor.clean(req.mode, raw, req.enhance) }
                val ocr = if (req.searchable) host.ocr.recognize(OcrInput(img, p.id, i)) else null
                ocr?.lines?.joinTo(text, "\n") { it.text }?.also { text.append("\n\n") }
                withContext(Dispatchers.IO) { writer.addPage(img, ocr) }
                if (img !== raw) img.recycle()
                raw.recycle()
                done = i + 1
            }
            step = 2
            val file = withContext(Dispatchers.IO) { writer.finish() }
            val hasText = text.isNotBlank()
            onDone(SavedDocument(id, req.title, req.folder, req.mode, pages.size, file, req.searchable && hasText, text.toString().trim(), file.length()))
        } catch (e: CancellationException) { writer.abort(); throw e } catch (e: Exception) {
            writer.abort()
            onFailed(if (e is java.io.IOException && e.message?.contains("ENOSPC") == true) "Not enough storage to save the PDF" else "Couldn't save the PDF: ${e.message}")
        }
    }
}
