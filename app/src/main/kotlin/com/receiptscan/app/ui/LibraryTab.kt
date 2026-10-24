package com.receiptscan.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppState
import com.receiptscan.app.Modules
import com.receiptscan.app.Screen
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ConfirmedBadge
import com.receiptscan.design.EmptyState
import com.receiptscan.design.ListRow
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.NeedsReviewBadge
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.DeleteToken
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.InvoiceStatus
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.time.DisplayDates
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private enum class LibTab(val label: String) { RECEIPTS("Receipts"), EXPENSES("Expenses"), BILLS("Bills"), DOCUMENTS("Documents"), SCANS("Other scans") }
private enum class RFilter { ALL, REVIEW, CONFIRMED }
private enum class Sort(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), AMOUNT("Amount") }

@Composable
fun LibraryTab(graph: AppGraph, state: AppState) {
    val t = skin
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(LibTab.RECEIPTS) }
    var filter by rememberSaveable { mutableStateOf(RFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(Sort.NEWEST) }
    val selected = remember { mutableStateListOf<String>() }
    var confirmDelete by remember { mutableStateOf(false) }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))

    val receipts by remember { graph.ledgerStore.observeReceipts(ReceiptFilter()) }.collectAsState(emptyList())
    val expenses by remember { graph.ledgerStore.observeExpenses(ExpenseFilter()) }.collectAsState(emptyList())
    val bills by remember { graph.ledgerStore.observeInvoices() }.collectAsState(emptyList())
    val docs by remember { graph.records.observeDocuments() }.collectAsState(emptyList())
    val scans by remember { graph.records.observeScans() }.collectAsState(emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibTab.entries.forEach { lt -> AppChip(lt.label, { tab = lt; selected.clear() }, selected = tab == lt) }
        }
        if (selected.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size} selected", style = AppType.h2, color = t.text, modifier = Modifier.weight(1f))
            AppButton("Cancel", { selected.clear() }, variant = ButtonVariant.GHOST)
            AppIconButton(AppIcons.trash, "Delete selected", { confirmDelete = true })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (tab) {
                LibTab.RECEIPTS -> {
                    val review = receipts.count { it.status == ReceiptStatus.NEEDS_REVIEW }
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppChip("All", { filter = RFilter.ALL }, selected = filter == RFilter.ALL)
                            AppChip("Needs review · $review", { filter = RFilter.REVIEW }, selected = filter == RFilter.REVIEW)
                            AppChip("Confirmed", { filter = RFilter.CONFIRMED }, selected = filter == RFilter.CONFIRMED)
                            AppChip("Sort: ${sort.label}", { sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size] }, icon = AppIcons.sort)
                        }
                    }
                    val shown = receipts.filter { when (filter) { RFilter.ALL -> true; RFilter.REVIEW -> it.status == ReceiptStatus.NEEDS_REVIEW; RFilter.CONFIRMED -> it.status == ReceiptStatus.CONFIRMED } }
                        .let { l -> when (sort) { Sort.NEWEST -> l.sortedByDescending { it.purchaseDate ?: LocalDate.MIN }; Sort.OLDEST -> l.sortedBy { it.purchaseDate ?: LocalDate.MAX }; Sort.AMOUNT -> l.sortedByDescending { it.total?.minor ?: 0 } } }
                    if (shown.isEmpty()) item {
                        EmptyState(AppIcons.receipt, if (receipts.isEmpty()) "No receipts yet" else "Nothing here", if (receipts.isEmpty()) "Scan your first receipt to start." else "Try another filter.",
                            actionLabel = if (receipts.isEmpty()) "Scan receipt" else null, onAction = { openTool(state, "receipt", graph) })
                    }
                    val groups = if (sort == Sort.AMOUNT) listOf("" to shown) else shown.groupBy { it.purchaseDate?.let { d -> YearMonth.from(d) } }.map { (ym, l) -> (ym?.let { "${it.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${it.year}" } ?: "No date") to l }
                    groups.forEach { (label, list) ->
                        if (label.isNotEmpty()) item(key = "h-$label") { Text(label.uppercase(), style = AppType.ov, color = t.muted, modifier = Modifier.padding(top = 10.dp)) }
                        items(list, key = { it.id }) { r ->
                            val sel = r.id in selected
                            ListRow(
                                r.merchant ?: "Receipt", meta = listOfNotNull(r.purchaseDate?.let { DisplayDates.relative(it, today) }, Categories.name(r.categoryId ?: "uncategorized")).joinToString(" · "),
                                leading = { ModuleTile(ModuleColor.RECEIPT, if (sel) AppIcons.check else AppIcons.receipt, size = 40.dp) },
                                trailing = { Column(horizontalAlignment = Alignment.End) { Text(fmtShort(r.total), style = AppType.num, color = t.text); if (r.status == ReceiptStatus.NEEDS_REVIEW) NeedsReviewBadge() else ConfirmedBadge() } },
                                selected = sel,
                                onClick = { if (selected.isNotEmpty()) { if (sel) selected.remove(r.id) else selected.add(r.id) } else state.push(if (r.status == ReceiptStatus.NEEDS_REVIEW) Screen.ReceiptEdit(r.id) else Screen.ReceiptDetail(r.id)) },
                                onLongClick = { if (!sel) selected.add(r.id) },
                            )
                        }
                    }
                }
                LibTab.EXPENSES -> {
                    item { Row(Modifier.padding(vertical = 6.dp)) { AppButton("Add expense", { state.push(Screen.ExpenseForm()) }, icon = AppIcons.plus, variant = ButtonVariant.TONAL) } }
                    if (expenses.isEmpty()) item { EmptyState(AppIcons.tag, "No expenses yet", "Confirmed receipts, voice and manual entries appear here, each counted once.") }
                    items(expenses.sortedByDescending { it.effectiveDate }, key = { it.id }) { e ->
                        ListRow(e.merchant ?: Categories.name(e.categoryId), meta = "${Categories.name(e.categoryId)} · ${DisplayDates.relative(e.effectiveDate, today)} · ${e.source.label}",
                            trailing = { Text(fmtShort(e.signedAmount), style = AppType.num, color = t.text) }, onClick = { state.push(Screen.ExpenseDetail(e.id)) })
                    }
                }
                LibTab.BILLS -> {
                    item { Row(Modifier.padding(vertical = 6.dp)) { AppButton("Scan a bill", { openTool(state, "invoice", graph) }, icon = AppIcons.bill, variant = ButtonVariant.TONAL) } }
                    if (bills.isEmpty()) item { EmptyState(AppIcons.bill, "No bills yet", "Scan a utility bill or invoice. It's counted only when you record a payment.") }
                    items(bills.sortedBy { it.dueDate ?: LocalDate.MAX }, key = { it.id }) { b ->
                        ListRow(b.supplier, meta = listOfNotNull(b.dueDate?.let { "Due ${DisplayDates.relative(it, today)}" }, b.invoiceNumber?.let { "No. $it" }).joinToString(" · "),
                            leading = { ModuleTile(ModuleColor.BILLS, AppIcons.bill, size = 40.dp) },
                            trailing = { Column(horizontalAlignment = Alignment.End) { Text(fmtShort(b.amountDue), style = AppType.num, color = t.text); BillStatusBadge(b.status, b.dueDate, today) } },
                            onClick = { state.push(Screen.BillDetail(b.id)) })
                    }
                }
                LibTab.DOCUMENTS -> {
                    item { Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { AppButton("Scan pages", { state.push(Screen.Pdf) }, icon = AppIcons.doc, variant = ButtonVariant.TONAL) } }
                    if (docs.isEmpty()) item { EmptyState(AppIcons.doc, "No documents yet", "Pick Document, Whiteboard or Book, add pages, then save a PDF.", actionLabel = "Scan pages", onAction = { state.push(Screen.Pdf) }) }
                    docs.groupBy { it.folder }.forEach { (folder, list) ->
                        item(key = "f-$folder") { Text(folder.uppercase(), style = AppType.ov, color = t.muted, modifier = Modifier.padding(top = 10.dp)) }
                        items(list, key = { it.id }) { d ->
                            ListRow(d.title, meta = "${d.pageCount} page${if (d.pageCount == 1) "" else "s"} · ${d.mode.lowercase().replaceFirstChar { it.uppercase() }} · ${if (d.searchable) "Searchable" else "Image only"}",
                                leading = { ModuleTile(ModuleColor.DOCUMENT, AppIcons.doc, size = 40.dp) }, onClick = { state.push(Screen.DocumentDetail(d.id)) })
                        }
                    }
                }
                LibTab.SCANS -> {
                    if (scans.isEmpty()) item { EmptyState(AppIcons.scan, "No saved scans", "Results you save from Tools appear here.", actionLabel = "Open Tools", onAction = { state.tab = com.receiptscan.app.Tab.TOOLS }) }
                    items(scans, key = { it.id }) { s ->
                        val info = Modules.info(s.capabilityId)
                        ListRow(s.title, meta = "${info?.title ?: s.capabilityId} · ${DisplayDates.relative(Instant.ofEpochMilli(s.createdAt).atZone(graph.zone()).toLocalDate(), today)}",
                            leading = { if (info != null) ModuleTile(info.color, info.icon, size = 40.dp) }, onClick = { state.push(Screen.ScanDetail(s.id)) })
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${selected.size} receipt${if (selected.size == 1) "" else "s"}?") },
        text = { Text(receipts.filter { it.id in selected }.joinToString { it.merchant ?: "Receipt" } + ". Their expenses stop counting. You can undo for a few seconds.") },
        confirmButton = {
            TextButton({
                confirmDelete = false
                val ids = selected.toList(); selected.clear()
                scope.launch {
                    val tokens = mutableListOf<DeleteToken>()
                    ids.forEach { tokens += graph.ledger.deleteReceipt(it) }
                    state.undoable("Deleted ${ids.size} receipt${if (ids.size == 1) "" else "s"}") { tokens.forEach { graph.ledger.undoDelete(it) } }
                }
            }) { Text("Delete") }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun BillStatusBadge(status: InvoiceStatus, due: LocalDate?, today: LocalDate) = when {
    status == InvoiceStatus.PAID -> StatusBadge(BadgeKind.OK, "Paid")
    status == InvoiceStatus.PARTIALLY_PAID -> StatusBadge(BadgeKind.INFO, "Part paid")
    status == InvoiceStatus.VOID -> StatusBadge(BadgeKind.NEUTRAL, "Void")
    due != null && due.isBefore(today) -> StatusBadge(BadgeKind.ERR, "Overdue")
    due != null && !due.isAfter(today.plusDays(3)) -> StatusBadge(BadgeKind.WARN, "Due soon")
    else -> StatusBadge(BadgeKind.NEUTRAL, "Not paid")
}
