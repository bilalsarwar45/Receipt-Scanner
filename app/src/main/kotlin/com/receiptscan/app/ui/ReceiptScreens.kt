package com.receiptscan.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppState
import com.receiptscan.app.ExportRequest
import com.receiptscan.app.ReminderChannel
import com.receiptscan.app.ReminderRequest
import com.receiptscan.app.Screen
import com.receiptscan.data.local.ShoppingItemRow
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ChipStyle
import com.receiptscan.design.ConfirmedBadge
import com.receiptscan.design.Divider
import com.receiptscan.design.FieldState
import com.receiptscan.design.ListRow
import com.receiptscan.design.NeedsReviewBadge
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.skin
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.LedgerValidationException
import com.receiptscan.expenses.LinkKind
import com.receiptscan.expenses.Receipt
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptItem
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import com.receiptscan.money.sum
import com.receiptscan.scanner.receipt.ReceiptCategories
import com.receiptscan.scanner.receipt.ReceiptScanner
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.MoneyField
import com.receiptscan.scanner.ui.editText
import com.receiptscan.scanner.ui.parseMoney
import com.receiptscan.time.DisplayDates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/** R-Success: the receipt is confirmed and counted once. */
@Composable
fun ReceiptSavedScreen(graph: AppGraph, state: AppState, s: Screen.ReceiptSaved) {
    val t = skin
    ScreenSurface {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(44.dp)).background(t.okBg), contentAlignment = Alignment.Center) {
                Icon(AppIcons.checkCircle, null, tint = t.ok, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Saved and counted", style = AppType.h1, color = t.text)
            Spacer(Modifier.height(6.dp))
            Text(s.summary, style = AppType.b1, color = t.text)
            Text("Added once to your expenses. Edit any time from the Library.", style = AppType.b2, color = t.muted)
            Spacer(Modifier.height(28.dp))
            if (s.queueAnother) AppButton("Scan next receipt", { state.replace(Screen.Scanner(ReceiptScanner.ID.value)) }, fill = true, icon = AppIcons.scan)
            AppButton("View receipt", { state.replace(Screen.ReceiptDetail(s.receiptId)) }, fill = true, variant = if (s.queueAnother) ButtonVariant.TONAL else ButtonVariant.PRIMARY)
            Spacer(Modifier.height(8.dp))
            if (!s.queueAnother) AppButton("Scan another", { state.replace(Screen.Scanner(ReceiptScanner.ID.value)) }, fill = true, variant = ButtonVariant.TONAL, icon = AppIcons.scan)
            AppButton("Done", { state.pop() }, fill = true, variant = ButtonVariant.GHOST)
        }
    }
}


/** Live receipt lookup; null while loading, and [MISSING] when it no longer exists. */
@Composable
private fun rememberReceipt(graph: AppGraph, id: String): Receipt? {
    val all by remember { graph.ledgerStore.observeReceipts(ReceiptFilter()) }.collectAsState(null)
    return all?.let { list -> list.firstOrNull { it.id == id } ?: MISSING }
}

private val MISSING = Receipt("missing", ReceiptStatus.DELETED, null, null, null, com.receiptscan.money.Currencies.PKR, null, null, null, null, null, java.time.Instant.EPOCH, "")

private enum class DetailTab(val label: String) { ITEMS("Items"), TEXT("Text"), IMAGE("Photo") }

/** R-Detail: a confirmed receipt with items, recognised text and photo, plus actions. */
@Composable
fun ReceiptDetailScreen(graph: AppGraph, state: AppState, id: String) {
    val t = skin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = rememberReceipt(graph, id)
    var tab by rememberSaveable { mutableStateOf(DetailTab.ITEMS) }
    var confirmDelete by remember { mutableStateOf(false) }
    var shoppingPick by remember { mutableStateOf(false) }
    val linkedExpense by produceState<ExpenseEntry?>(null, r) {
        value = graph.ledgerStore.linkForTarget(LinkKind.RECEIPT, id)?.let { graph.ledgerStore.expense(it.expenseId) }
    }
    val warranties by produceState(emptyList<com.receiptscan.data.local.WarrantyRow>(), id) { value = graph.records.warrantiesForReceipt(id) }
    if (r == null) { Page("Receipt", { state.pop() }) { com.receiptscan.design.SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    if (r === MISSING) { LaunchedEffect(Unit) { state.pop() }; return }

    Page(r.merchant ?: "Receipt", { state.pop() }, actions = {
        AppIconButton(AppIcons.edit, "Edit", { state.push(Screen.ReceiptEdit(id)) })
        AppIconButton(AppIcons.trash, "Delete", { confirmDelete = true })
    }) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(r.total), style = AppType.d1, color = t.text, modifier = Modifier.weight(1f))
                    if (r.status == ReceiptStatus.CONFIRMED) ConfirmedBadge() else NeedsReviewBadge()
                }
                Text(listOfNotNull(r.purchaseDate?.let { DisplayDates.format(it) }, r.purchaseTime, Categories.name(r.categoryId ?: "uncategorized")).joinToString(" · "), style = AppType.b2, color = t.muted)
                r.paymentMethod?.let { Text("Paid by $it", style = AppType.cap, color = t.muted) }
                r.reference?.let { Text("Ref. $it", style = AppType.cap, color = t.muted) }
            }
        }
        if (r.status == ReceiptStatus.NEEDS_REVIEW) Banner(BadgeKind.WARN, "Not counted yet", "Check and confirm this receipt to add it to your expenses.", "Review") { state.push(Screen.ReceiptEdit(id)) }
        linkedExpense?.let { e ->
            ListRow("Counted as an expense", meta = "${fmt(e.amount)} · ${Categories.name(e.categoryId)} · ${e.source.label}", leading = { Icon(AppIcons.tag, null, tint = t.muted) },
                trailing = { Icon(AppIcons.chev, null, tint = t.muted) }, onClick = { state.push(Screen.ExpenseDetail(e.id)) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip("Split", { state.push(Screen.Split(id)) }, icon = AppIcons.users, style = ChipStyle.TONAL, enabled = r.total != null)
            AppChip("Compare prices", { state.push(Screen.Compare(id)) }, icon = AppIcons.trend, style = ChipStyle.TONAL, enabled = r.items.isNotEmpty())
            AppChip("Remind", {
                state.reminderRequest = ReminderRequest("Check ${r.merchant ?: "receipt"}", "${fmt(r.total)} on ${r.purchaseDate?.let { DisplayDates.format(it) } ?: "—"}",
                    LocalDate.now(graph.clock.withZone(graph.zone())).plusDays(7).atTime(9, 0), ReminderChannel.RECEIPT.id, id)
            }, icon = AppIcons.bell, style = ChipStyle.TONAL)
            AppChip("Warranty", { state.push(Screen.Warranty(receiptId = id)) }, icon = AppIcons.shield, style = ChipStyle.TONAL)
            AppChip("Shopping list", { shoppingPick = true }, icon = AppIcons.basket, style = ChipStyle.TONAL, enabled = r.items.isNotEmpty())
            AppChip("Export", { state.exportRequest = ExportRequest(receiptId = id, title = "Export receipt") }, icon = AppIcons.download, style = ChipStyle.TONAL)
            AppChip("Share", { share(context, receiptText(r), r.merchant) }, icon = AppIcons.share, style = ChipStyle.TONAL)
        }
        warranties.forEach { w ->
            ListRow(w.productName, meta = w.warrantyUntil?.let { "Warranty until ${DisplayDates.format(LocalDate.ofEpochDay(it))}" } ?: "Warranty record",
                leading = { Icon(AppIcons.shield, null, tint = t.muted) }, onClick = { state.push(Screen.Warranty(id = w.id)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DetailTab.entries.forEach { dt -> AppChip(dt.label, { tab = dt }, selected = tab == dt) } }
        when (tab) {
            DetailTab.ITEMS -> ItemsBreakdown(r)
            DetailTab.TEXT -> AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.rawText.ifBlank { "No recognised text was kept." }, style = AppType.b2.copy(fontFamily = FontFamily.Monospace), color = t.text)
                    r.ocrEngine?.let { Text("Read on this phone with $it", style = AppType.cap, color = t.muted) }
                    if (r.rawText.isNotBlank()) AppButton("Copy text", { copy(context, r.rawText); scope.launch { state.toast("Copied") } }, variant = ButtonVariant.TONAL, icon = AppIcons.copy)
                }
            }
            DetailTab.IMAGE -> ReceiptPhotos(graph, r.sourceImageIds)
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this receipt?") },
        text = { Text("${r.merchant ?: "Receipt"} · ${fmt(r.total)}. ${if (linkedExpense != null) "Its expense stops counting. " else ""}You can undo for a few seconds.") },
        confirmButton = {
            TextButton({
                confirmDelete = false
                scope.launch {
                    val token = graph.ledger.deleteReceipt(id)
                    graph.reminders.cancelFor(id)
                    state.pop()
                    state.undoable("Receipt deleted") { graph.ledger.undoDelete(token) }
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
    if (shoppingPick) ShoppingPickDialog(r, onDismiss = { shoppingPick = false }) { picked ->
        shoppingPick = false
        scope.launch {
            picked.forEach { graph.records.saveShopping(ShoppingItemRow(UUID.randomUUID().toString(), it.description, it.quantity?.stripTrailingZeros()?.toPlainString(), r.id, false, graph.clock.millis())) }
            val res = state.snackbar.showSnackbar("Added ${picked.size} to shopping list", actionLabel = "View")
            if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) state.push(Screen.Shopping)
        }
    }
}

fun receiptText(r: Receipt): String = buildString {
    appendLine(r.merchant ?: "Receipt")
    r.purchaseDate?.let { appendLine(DisplayDates.format(it)) }
    r.items.forEach { appendLine("${it.description}  ${MoneyFormat.format(it.lineTotal)}") }
    r.subtotal?.let { appendLine("Subtotal  ${MoneyFormat.format(it)}") }
    r.tax?.let { appendLine("Tax  ${MoneyFormat.format(it)}") }
    r.discount?.let { appendLine("Discount  −${MoneyFormat.format(it)}") }
    append("Total  ${fmt(r.total)}")
}

@Composable
private fun ItemsBreakdown(r: Receipt) {
    val t = skin
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.items.isEmpty()) Text("No items were listed on this receipt.", style = AppType.b2, color = t.muted)
            r.items.sortedBy { it.position }.forEach { i ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(i.description, style = AppType.b1, color = t.text)
                        val meta = listOfNotNull(
                            i.quantity?.let { q -> "${q.stripTrailingZeros().toPlainString()}${i.unit?.let { " $it" } ?: ""}" + (i.unitPrice?.let { " × ${MoneyFormat.format(it, false)}" } ?: "") },
                            i.categoryId?.let { Categories.name(it) },
                            if (i.provenance == "edited") "Edited" else null,
                        )
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = AppType.cap, color = t.muted)
                        if (i.uncertain) Text("Unclear on the photo", style = AppType.cap, color = t.warn)
                    }
                    Text(MoneyFormat.format(i.lineTotal, false), style = AppType.num, color = t.text)
                }
            }
            Divider()
            r.subtotal?.let { Line("Subtotal", MoneyFormat.format(it, false)) }
            r.tax?.let { Line("Tax", MoneyFormat.format(it, false)) }
            r.discount?.let { Line("Discount", "−" + MoneyFormat.format(it, false)) }
            Line("Total", fmt(r.total), strong = true)
            val itemsSum = r.items.map { it.lineTotal }.filter { it.currency == r.currency }.sum(r.currency)
            if (r.items.isNotEmpty() && r.subtotal != null && itemsSum != r.subtotal)
                Text("Items add up to ${MoneyFormat.format(itemsSum)}, which differs from the printed subtotal.", style = AppType.cap, color = t.warn)
        }
    }
}

@Composable
private fun Line(label: String, value: String, strong: Boolean = false) {
    val t = skin
    Row { Text(label, style = if (strong) AppType.h2 else AppType.b2, color = if (strong) t.text else t.muted, modifier = Modifier.weight(1f)); Text(value, style = if (strong) AppType.h2 else AppType.num, color = t.text) }
}

@Composable
private fun ReceiptPhotos(graph: AppGraph, ids: List<String>) {
    val t = skin
    val bitmaps by produceState(emptyList<android.graphics.Bitmap>(), ids) {
        value = withContext(Dispatchers.IO) { ids.mapNotNull { id -> graph.images.fileFor(id).takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path) } } }
    }
    if (ids.isEmpty() || (bitmaps.isEmpty())) {
        Text(if (ids.isEmpty()) "No photo kept. Turn on \"Keep receipt photos\" in Settings → Receipt defaults to keep them." else "Loading photo…", style = AppType.b2, color = t.muted)
    }
    bitmaps.forEachIndexed { i, b ->
        Image(b.asImageBitmap(), "Receipt photo ${i + 1}", Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth)
    }
}

@Composable
private fun ShoppingPickDialog(r: Receipt, onDismiss: () -> Unit, onAdd: (List<ReceiptItem>) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buy again") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                r.items.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(i.id in picked, { c -> if (c) picked.add(i.id) else picked.remove(i.id) })
                        Text(i.description)
                    }
                }
            }
        },
        confirmButton = { TextButton({ onAdd(r.items.filter { it.id in picked }) }, enabled = picked.isNotEmpty()) { Text("Add ${picked.size}") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}


private class EditItem(id: String, description: String, amount: String, val source: ReceiptItem?) {
    val id = id
    var description by mutableStateOf(description)
    var amount by mutableStateOf(amount)
}

/** R-Review for a saved receipt: edit any value, then save as a draft or confirm (counts it once). */
@Composable
fun ReceiptEditScreen(graph: AppGraph, state: AppState, id: String) {
    val r = rememberReceipt(graph, id)
    if (r == null) { Page("Review receipt", { state.pop() }) { com.receiptscan.design.SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    if (r === MISSING) { LaunchedEffect(Unit) { state.pop() }; return }
    ReceiptEditForm(graph, state, r)
}

@Composable
private fun ReceiptEditForm(graph: AppGraph, state: AppState, r: Receipt) {
    val t = skin
    val scope = rememberCoroutineScope()
    val cur = r.currency
    var merchant by rememberSaveable { mutableStateOf(r.merchant.orEmpty()) }
    var date by remember { mutableStateOf(r.purchaseDate) }
    var total by rememberSaveable { mutableStateOf(r.total?.editText().orEmpty()) }
    var tax by rememberSaveable { mutableStateOf(r.tax?.editText().orEmpty()) }
    var discount by rememberSaveable { mutableStateOf(r.discount?.editText().orEmpty()) }
    var category by rememberSaveable { mutableStateOf(r.categoryId ?: "uncategorized") }
    val items = remember { mutableStateListOf<EditItem>().apply { addAll(r.items.sortedBy { it.position }.map { EditItem(it.id, it.description, it.lineTotal.editText(), it) }) } }
    var force by remember { mutableStateOf(false) }
    var duplicates by remember { mutableStateOf<List<ExpenseEntry>>(emptyList()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val itemTotals = items.mapNotNull { parseMoney(it.amount, cur, allowZero = true) }
    val itemsSum = itemTotals.sum(cur)
    val totalMoney = parseMoney(total, cur)

    fun build(): Receipt = r.copy(
        merchant = merchant.trim().ifBlank { null }, purchaseDate = date, total = totalMoney,
        tax = parseMoney(tax, cur, true), discount = parseMoney(discount, cur, true), categoryId = category,
        items = items.mapIndexedNotNull { i, e ->
            val m = parseMoney(e.amount, cur, allowZero = true) ?: return@mapIndexedNotNull null
            val src = e.source
            val edited = src == null || src.description != e.description || src.lineTotal != m
            (src ?: ReceiptItem(e.id, r.id, i, e.description, null, null, null, m, null, "edited")).copy(
                position = i, description = e.description.ifBlank { "Item ${i + 1}" }, lineTotal = m, provenance = if (edited) "edited" else src!!.provenance,
                unitPrice = if (edited) null else src!!.unitPrice, quantity = if (edited && src?.lineTotal != m) null else src?.quantity,
            )
        },
    )

    fun confirmNow(attachTo: String?) {
        scope.launch {
            try {
                confirm(graph, state, build(), attachTo)
            } catch (e: LedgerValidationException) {
                errors = e.errors.associate { it.field to it.message }
            }
        }
    }

    Page(if (r.status == ReceiptStatus.CONFIRMED) "Edit receipt" else "Review receipt", { state.pop() }, bottom = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.status != ReceiptStatus.CONFIRMED) AppButton("Save draft", {
                scope.launch { graph.ledger.saveReceiptDraft(build()); state.pop(); state.toast("Saved · still needs review") }
            }, Modifier.weight(1f), variant = ButtonVariant.TONAL, fill = true)
            AppButton(if (r.status == ReceiptStatus.CONFIRMED) "Save changes" else "Confirm & save", {
                force = true
                val candidate = build()
                val errs = graph.ledger.validateForConfirm(candidate)
                errors = errs.associate { it.field to it.message }
                if (errs.isEmpty()) scope.launch {
                    val d = if (r.status == ReceiptStatus.CONFIRMED) emptyList() else graph.ledger.possibleDuplicates(candidate)
                    if (d.isEmpty()) confirmNow(null) else duplicates = d
                }
            }, Modifier.weight(1f), fill = true)
        }
    }) {
        if (r.reviewReasons.isNotEmpty() && r.status != ReceiptStatus.CONFIRMED) Banner(BadgeKind.WARN, "Please check", r.reviewReasons.joinToString("\n"))
        if (r.status == ReceiptStatus.CONFIRMED) Text("Changes update the counted expense. Nothing is added twice.", style = AppType.cap, color = t.muted)
        AppTextField(merchant, { merchant = it }, "Store", state = if (errors["merchant"] != null) FieldState.ERROR else FieldState.DEFAULT, helper = errors["merchant"])
        DateField("Date", date, { date = it }, state = if (errors["date"] != null) FieldState.ERROR else FieldState.DEFAULT, helper = errors["date"])
        MoneyField(total, { total = it }, "Total", cur, forceValidate = force,
            warn = when {
                errors["total"] != null -> errors["total"]
                totalMoney != null && itemTotals.isNotEmpty() && run {
                    val expected = itemsSum + (parseMoney(tax, cur, true) ?: Money.zero(cur)) - (parseMoney(discount, cur, true) ?: Money.zero(cur))
                    expected != totalMoney && itemsSum != totalMoney
                } -> "Items add up to ${MoneyFormat.format(itemsSum)}. Check the total or the items."
                else -> null
            })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField(tax, { tax = it }, "Tax", cur, Modifier.weight(1f), allowZero = true)
            MoneyField(discount, { discount = it }, "Discount", cur, Modifier.weight(1f), allowZero = true)
        }
        Section("Category")
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReceiptCategories.all.forEach { (cid, name) -> AppChip(name, { category = cid }, selected = category == cid) }
        }
        Section("Items · ${items.size}") { AppButton("Add item", { items.add(EditItem(UUID.randomUUID().toString(), "", "", null)) }, variant = ButtonVariant.GHOST, icon = AppIcons.plus) }
        items.forEachIndexed { idx, e ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppTextField(e.description, { e.description = it }, "Item ${idx + 1}", Modifier.weight(1.4f), state = if (e.source?.uncertain == true) FieldState.WARN else FieldState.DEFAULT)
                MoneyField(e.amount, { e.amount = it }, "Amount", cur, Modifier.weight(1f), allowZero = true)
                AppIconButton(AppIcons.close, "Remove item", { items.remove(e) })
            }
        }
        if (items.isNotEmpty()) Text("Items: ${MoneyFormat.format(itemsSum)}", style = AppType.cap, color = t.muted)
        if (r.rawText.isNotBlank()) {
            Section("What we read")
            Text(r.rawText.take(1200), style = AppType.cap.copy(fontFamily = FontFamily.Monospace), color = t.muted)
        }
    }

    if (duplicates.isNotEmpty()) {
        val m = duplicates.first()
        AlertDialog(
            onDismissRequest = { duplicates = emptyList() },
            title = { Text("Possible duplicate") },
            text = { Text("You already have ${MoneyFormat.format(m.amount)}${m.merchant?.let { " at $it" } ?: ""} on ${DisplayDates.format(m.effectiveDate)} (${m.source.label}). Attach this receipt to it so it's counted once, or keep both.") },
            confirmButton = { TextButton({ duplicates = emptyList(); confirmNow(m.id) }) { Text("Attach to it") } },
            dismissButton = { TextButton({ duplicates = emptyList(); confirmNow(null) }) { Text("Keep both") } },
        )
    }
}
