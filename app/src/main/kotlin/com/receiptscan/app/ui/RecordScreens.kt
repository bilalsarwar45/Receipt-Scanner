package com.receiptscan.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppScannerHost
import com.receiptscan.app.AppState
import com.receiptscan.app.Modules
import com.receiptscan.app.ReminderChannel
import com.receiptscan.app.Screen
import com.receiptscan.app.mb
import com.receiptscan.app.newTempStorage
import com.receiptscan.data.local.DocumentRow
import com.receiptscan.data.local.ScanRow
import com.receiptscan.data.local.WarrantyRow
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.EmptyState
import com.receiptscan.design.FieldState
import com.receiptscan.design.ListRow
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.SearchPill
import com.receiptscan.design.SkeletonBlock
import com.receiptscan.design.skin
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.Invoice
import com.receiptscan.expenses.Receipt
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.feature.documents.DocumentsFeature
import com.receiptscan.scanner.product.WarrantyPayload
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.time.DisplayDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val lenient = Json { ignoreUnknownKeys = true }

/** Saved result from any tool. Shows the module's own title and summary plus the stored values. */
@Composable
fun ScanDetailScreen(graph: AppGraph, state: AppState, id: String) {
    val t = skin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val row by produceState<ScanRow?>(null, id) { value = graph.records.scan(id) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(row) { if (row != null) loaded = true }
    val s = row ?: run { Page("Saved scan", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    val info = Modules.info(s.capabilityId)
    val fields = remember(s) { runCatching { flatten(lenient.parseToJsonElement(s.payloadJson)) }.getOrDefault(emptyList()) }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    Page(info?.title ?: "Saved scan", { state.pop() }, actions = {
        AppIconButton(AppIcons.trash, "Delete", {
            scope.launch {
                graph.records.deleteScan(s.id)
                state.pop()
                state.undoable("Deleted") {
                    val ids = runCatching { lenient.parseToJsonElement(s.sourceIds).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())
                    graph.records.saveScan(s.capabilityId, s.payloadVersion, s.title, s.summary, s.payloadJson, s.searchText, ids, s.id)
                }
            }
        })
    }) {
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (info != null) ModuleTile(info.color, info.icon)
                Column(Modifier.weight(1f)) {
                    Text(s.title, style = AppType.h2, color = t.text)
                    if (s.summary.isNotBlank()) Text(s.summary, style = AppType.b2, color = t.muted)
                    Text("Saved ${DisplayDates.relative(Instant.ofEpochMilli(s.createdAt).atZone(graph.zone()).toLocalDate(), today)}", style = AppType.cap, color = t.muted)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val text = (listOf(s.title, s.summary) + fields.map { "${it.first}: ${it.second}" }).filter { it.isNotBlank() }.joinToString("\n")
            AppButton("Copy", { copy(context, text); scope.launch { state.toast("Copied") } }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.copy, fill = true)
            AppButton("Share", { share(context, text, s.title) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.share, fill = true)
        }
        if (info != null) AppButton("Scan again with ${info.title}", { state.replace(Screen.Scanner(s.capabilityId)) }, variant = ButtonVariant.GHOST, icon = AppIcons.scan)
        if (fields.isNotEmpty()) {
            Section("Details")
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fields.forEach { (k, v) ->
                        Column { Text(k, style = AppType.lab, color = t.muted); Text(v, style = AppType.b2, color = t.text) }
                    }
                }
            }
        }
    }
}

/** Turns a module payload into readable label/value pairs. Values are shown exactly as stored. */
private fun flatten(e: JsonElement, prefix: String = ""): List<Pair<String, String>> = when (e) {
    is JsonObject -> e.entries.flatMap { (k, v) -> flatten(v, if (prefix.isEmpty()) label(k) else "$prefix · ${label(k)}") }
    is JsonArray -> if (e.all { it is JsonPrimitive }) listOf(prefix to e.joinToString(", ") { (it as JsonPrimitive).content }).filter { it.second.isNotBlank() }
        else e.flatMapIndexed { i, v -> flatten(v, "$prefix ${i + 1}") }
    is JsonNull -> emptyList()
    is JsonPrimitive -> if (e.content.isBlank() || prefix.endsWith("Minor") || prefix.lowercase().contains("version")) emptyList() else listOf(prefix to e.content)
}

private fun label(key: String) = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

/** S-PDF host: runs the Documents feature and stores the saved PDF in the Library. */
@Composable
fun PdfScreen(graph: AppGraph, state: AppState) {
    val scope = rememberCoroutineScope()
    val temp = remember { newTempStorage(graph) }
    val host = remember { AppScannerHost(graph, temp) }
    DisposableEffect(Unit) { onDispose { temp.clear() } }
    val settings = graph.current
    DocumentsFeature.Flow(
        host = host,
        input = ScanInput(currency = graph.currency(), zone = graph.zone(), showEducation = settings.showTips && "pdf" !in settings.dismissedTips),
        onSaved = { d ->
            scope.launch {
                graph.records.saveDocument(DocumentRow(d.id, d.title, d.mode.name, d.pageCount, d.file.path, d.folder, d.searchable, d.text, graph.clock.millis(), d.sizeBytes))
                state.replace(Screen.DocumentDetail(d.id))
                state.toast("PDF saved · ${d.pageCount} page${if (d.pageCount == 1) "" else "s"}")
            }
        },
        onClose = { state.pop() },
    )
}

/** D-Detail: a saved PDF. Open, share, read its recognised text, or delete it. */
@Composable
fun DocumentDetailScreen(graph: AppGraph, state: AppState, id: String) {
    val t = skin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc by produceState<DocumentRow?>(null, id) { value = graph.records.document(id) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    val d = doc ?: run { Page("Document", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    val file = d.pdfPath?.let(::File)?.takeIf { it.exists() }
    fun uri() = FileProvider.getUriForFile(context, "${context.packageName}.files", file!!)

    Page(d.title, { state.pop() }, actions = { AppIconButton(AppIcons.trash, "Delete", { confirmDelete = true }) }) {
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModuleTile(ModuleColor.DOCUMENT, AppIcons.doc)
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = AppType.h2, color = t.text)
                    Text("${d.pageCount} page${if (d.pageCount == 1) "" else "s"} · ${mb(d.sizeBytes)} · ${d.folder}", style = AppType.b2, color = t.muted)
                    Text(if (d.searchable) "Searchable PDF (text from this phone's text recognition)" else "Image-only PDF", style = AppType.cap, color = t.muted)
                }
            }
        }
        if (file == null) Banner(BadgeKind.ERR, "PDF file is missing", "It may have been removed. Delete this entry and scan again.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Open", {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(), "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { scope.launch { state.toast("No app on this phone can open PDFs") } }
            }, Modifier.weight(1f), fill = true, icon = AppIcons.external, enabled = file != null)
            AppButton("Share", {
                val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri()).putExtra(Intent.EXTRA_SUBJECT, d.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Share PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, Modifier.weight(1f), fill = true, variant = ButtonVariant.TONAL, icon = AppIcons.share, enabled = file != null)
        }
        if (d.text.isNotBlank()) {
            AppButton(if (showText) "Hide recognised text" else "Show recognised text", { showText = !showText }, variant = ButtonVariant.GHOST, icon = AppIcons.text)
            if (showText) {
                AppCard(Modifier.fillMaxWidth()) { Text(d.text, style = AppType.b2, color = t.text) }
                AppButton("Copy text", { copy(context, d.text); scope.launch { state.toast("Copied") } }, variant = ButtonVariant.TONAL, icon = AppIcons.copy)
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete \"${d.title}\"?") },
        text = { Text("The PDF is removed from this phone. You can undo for a few seconds.") },
        confirmButton = {
            TextButton({
                confirmDelete = false
                scope.launch {
                    graph.records.deleteDocument(d.id)
                    state.pop()
                    var undone = false
                    state.undoable("Document deleted") { undone = true; graph.records.saveDocument(d) }
                    if (!undone) withContext(Dispatchers.IO) { d.pdfPath?.let { File(it).delete() } }
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

/** A-Warranty: product, dates and terms, linked to a receipt when there is one. Terms are never invented. */
@Composable
fun WarrantyScreen(graph: AppGraph, state: AppState, s: Screen.Warranty) {
    val loaded by produceState<Pair<WarrantyRow?, Receipt?>?>(null, s) {
        val w = s.id?.let { graph.records.warranty(it) }
        val r = (w?.receiptId ?: s.receiptId)?.let { graph.ledgerStore.receipt(it) }
        value = w to r
    }
    val l = loaded ?: run { Page("Warranty", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    WarrantyForm(graph, state, l.first, l.second, s.prefillJson?.let { runCatching { lenient.decodeFromString(WarrantyPayload.serializer(), it) }.getOrNull() })
}

@Composable
private fun WarrantyForm(graph: AppGraph, state: AppState, w: WarrantyRow?, receipt: Receipt?, prefill: WarrantyPayload?) {
    val t = skin
    val scope = rememberCoroutineScope()
    fun day(v: Long?) = v?.let(LocalDate::ofEpochDay)
    fun parse(v: String?) = v?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var name by rememberSaveable { mutableStateOf(w?.productName ?: prefill?.productName ?: receipt?.items?.maxByOrNull { it.lineTotal.minor }?.description.orEmpty()) }
    var merchant by rememberSaveable { mutableStateOf(w?.merchant ?: receipt?.merchant.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(w?.model ?: prefill?.model.orEmpty()) }
    var serial by rememberSaveable { mutableStateOf(w?.serial ?: prefill?.serial.orEmpty()) }
    var purchase by remember { mutableStateOf(day(w?.purchaseDate) ?: parse(prefill?.purchaseDate) ?: receipt?.purchaseDate) }
    var returnBy by remember { mutableStateOf(day(w?.returnBy) ?: parse(prefill?.returnBy)) }
    var until by remember { mutableStateOf(day(w?.warrantyUntil) ?: parse(prefill?.warrantyUntil)) }
    var terms by rememberSaveable { mutableStateOf(w?.termsNote ?: prefill?.termsNote.orEmpty()) }
    var remind by rememberSaveable { mutableStateOf(true) }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))

    fun save() {
        scope.launch {
            val id = w?.id ?: UUID.randomUUID().toString()
            val row = WarrantyRow(id, name.trim(), merchant.trim().ifBlank { null }, receipt?.id ?: w?.receiptId, model.trim().ifBlank { null }, serial.trim().ifBlank { null },
                w?.barcode ?: prefill?.barcode, purchase?.toEpochDay(), returnBy?.toEpochDay(), until?.toEpochDay(), terms.trim().ifBlank { null }, w?.createdAt ?: graph.clock.millis())
            graph.records.saveWarranty(row)
            graph.reminders.cancelFor(id)
            if (remind) {
                returnBy?.minusDays(2)?.takeIf { it.isAfter(today) }?.let { graph.reminders.schedule("Return window closing: ${row.productName}", "Last day to return is ${DisplayDates.format(returnBy!!)}", it.atTime(9, 0), graph.zone(), ReminderChannel.WARRANTY.id, id) }
                until?.minusDays(30)?.takeIf { it.isAfter(today) }?.let { graph.reminders.schedule("Warranty ends soon: ${row.productName}", "Covered until ${DisplayDates.format(until!!)}", it.atTime(9, 0), graph.zone(), ReminderChannel.WARRANTY.id, id) }
            }
            state.pop()
            state.toast(if (remind && (returnBy != null || until != null) && !graph.reminders.notificationsAllowed()) "Warranty saved · notifications are off" else "Warranty saved")
        }
    }

    Page(if (w == null) "Add warranty" else "Warranty", { state.pop() }, bottom = {
        AppButton("Save warranty", ::save, fill = true, enabled = name.isNotBlank(), disabledReason = "Add the product name")
    }) {
        receipt?.let { r ->
            ListRow("Linked receipt", meta = "${r.merchant ?: "Receipt"} · ${fmt(r.total)}", leading = { Icon(AppIcons.receipt, null, tint = t.muted) },
                onClick = { state.push(if (r.status == ReceiptStatus.CONFIRMED) Screen.ReceiptDetail(r.id) else Screen.ReceiptEdit(r.id)) })
        }
        AppTextField(name, { name = it }, "Product", state = if (name.isBlank()) FieldState.ERROR else FieldState.DEFAULT, helper = if (name.isBlank()) "Required" else null)
        AppTextField(merchant, { merchant = it }, "Bought from (optional)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(model, { model = it }, "Model", Modifier.weight(1f))
            AppTextField(serial, { serial = it }, "Serial", Modifier.weight(1f))
        }
        DateField("Purchase date", purchase, { purchase = it })
        DateField("Return by (optional)", returnBy, { returnBy = it })
        DateField("Warranty until (optional)", until, { until = it }, helper = if (until == null) "Only from the label, card or receipt. We don't guess warranty terms." else null)
        AppTextField(terms, { terms = it }, "Terms note (optional)", singleLine = false, minLines = 2)
        if (prefill?.termsSource?.isNotBlank() == true) Text("Dates from: ${prefill.termsSource}", style = AppType.cap, color = t.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remind me", style = AppType.b1, color = t.text)
                Text("2 days before the return window closes and 30 days before the warranty ends", style = AppType.cap, color = t.muted)
            }
            Switch(remind, { remind = it })
        }
    }
}

/** Search across receipts, expenses, bills, documents and saved scans (handover: global search). */
@Composable
fun SearchScreen(graph: AppGraph, state: AppState) {
    val t = skin
    var q by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    val results by produceState<SearchResults?>(null, q) {
        val text = q.trim()
        if (text.length < 2) { value = null; return@produceState }
        delay(250)
        val receipts = graph.ledgerStore.receipts(ReceiptFilter(text = text))
        val expenses = graph.ledgerStore.expenses(ExpenseFilter(merchantContains = text)).filter { e -> receipts.none { it.merchant == e.merchant } }
        val bills = graph.ledgerStore.invoices().filter { it.supplier.contains(text, true) || it.invoiceNumber?.contains(text, true) == true }
        val docs = graph.records.observeDocuments().first().filter { it.title.contains(text, true) || it.text.contains(text, true) }
        val scanIds = graph.records.searchScans(text)
        val scans = graph.records.observeScans().first().filter { it.id in scanIds || it.title.contains(text, true) }
        value = SearchResults(receipts, expenses, bills, docs, scans)
    }
    Page("Search", { state.pop() }) {
        SearchPill(q, { q = it }, "Store, item, amount or text")
        val r = results
        when {
            r == null -> Text("Search what you've scanned: store names, item names, bill numbers and text inside PDFs.", style = AppType.b2, color = t.muted)
            r.isEmpty -> EmptyState(AppIcons.search, "No matches", "Try a shorter word or check the spelling.")
            else -> {
                if (r.receipts.isNotEmpty()) Section("Receipts · ${r.receipts.size}")
                r.receipts.forEach { rc -> ReceiptRow(rc, today) { state.push(if (rc.status == ReceiptStatus.NEEDS_REVIEW) Screen.ReceiptEdit(rc.id) else Screen.ReceiptDetail(rc.id)) } }
                if (r.expenses.isNotEmpty()) Section("Expenses · ${r.expenses.size}")
                r.expenses.forEach { e ->
                    ListRow(e.merchant ?: Categories.name(e.categoryId), meta = "${DisplayDates.relative(e.effectiveDate, today)} · ${e.source.label}",
                        trailing = { Text(fmtShort(e.signedAmount), style = AppType.num, color = t.text) }, onClick = { state.push(Screen.ExpenseDetail(e.id)) })
                }
                if (r.bills.isNotEmpty()) Section("Bills · ${r.bills.size}")
                r.bills.forEach { b ->
                    ListRow(b.supplier, meta = b.dueDate?.let { "Due ${DisplayDates.format(it)}" }, leading = { ModuleTile(ModuleColor.BILLS, AppIcons.bill, size = 40.dp) },
                        trailing = { Text(fmtShort(b.amountDue), style = AppType.num, color = t.text) }, onClick = { state.push(Screen.BillDetail(b.id)) })
                }
                if (r.docs.isNotEmpty()) Section("Documents · ${r.docs.size}")
                r.docs.forEach { d ->
                    ListRow(d.title, meta = "${d.pageCount} pages · ${d.folder}", leading = { ModuleTile(ModuleColor.DOCUMENT, AppIcons.doc, size = 40.dp) }, onClick = { state.push(Screen.DocumentDetail(d.id)) })
                }
                if (r.scans.isNotEmpty()) Section("Other scans · ${r.scans.size}")
                r.scans.forEach { s ->
                    val info = Modules.info(s.capabilityId)
                    ListRow(s.title, meta = info?.title, leading = { if (info != null) ModuleTile(info.color, info.icon, size = 40.dp) }, onClick = { state.push(Screen.ScanDetail(s.id)) })
                }
            }
        }
    }
}

private data class SearchResults(val receipts: List<Receipt>, val expenses: List<ExpenseEntry>, val bills: List<Invoice>, val docs: List<DocumentRow>, val scans: List<ScanRow>) {
    val isEmpty get() = receipts.isEmpty() && expenses.isEmpty() && bills.isEmpty() && docs.isEmpty() && scans.isEmpty()
}
