package com.receiptscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppState
import com.receiptscan.app.ReminderChannel
import com.receiptscan.app.ReminderRequest
import com.receiptscan.app.Screen
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.FieldState
import com.receiptscan.design.ListRow
import com.receiptscan.design.SkeletonBlock
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.expenses.AddResult
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseEdit
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.ExpenseKind
import com.receiptscan.expenses.ExpenseRevision
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.ExpenseSourceLink
import com.receiptscan.expenses.ExpenseState
import com.receiptscan.expenses.InvoiceBalance
import com.receiptscan.expenses.LedgerValidationException
import com.receiptscan.expenses.LinkKind
import com.receiptscan.expenses.NewExpense
import com.receiptscan.money.MoneyFormat
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.MoneyField
import com.receiptscan.scanner.ui.editText
import com.receiptscan.scanner.ui.parseMoney
import com.receiptscan.time.DisplayDates
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private val ALL_STATES = ExpenseFilter(states = ExpenseState.entries.toSet())

/** E-Detail: one expense from any source, with where it came from and its change history. */
@Composable
fun ExpenseDetailScreen(graph: AppGraph, state: AppState, id: String) {
    val t = skin
    val scope = rememberCoroutineScope()
    val all by remember { graph.ledgerStore.observeExpenses(ALL_STATES) }.collectAsState(null)
    val e = all?.firstOrNull { it.id == id }
    val links by produceState(emptyList<ExpenseSourceLink>(), e) { value = graph.ledgerStore.links(id) }
    val revisions by produceState(emptyList<ExpenseRevision>(), e) { value = graph.ledgerStore.revisions(id) }
    var refund by remember { mutableStateOf(false) }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    if (all == null) { Page("Expense", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    if (e == null) { LaunchedEffect(Unit) { state.pop() }; return }
    val voided = e.state == ExpenseState.VOIDED

    Page(e.merchant ?: Categories.name(e.categoryId), { state.pop() }, actions = {
        if (!voided) AppIconButton(AppIcons.edit, "Edit", { state.push(Screen.ExpenseForm(id = id)) })
    }) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(e.signedAmount), style = AppType.d1, color = if (voided) t.muted else t.text, modifier = Modifier.weight(1f))
                    when {
                        voided -> StatusBadge(BadgeKind.NEUTRAL, "Not counted")
                        e.kind == ExpenseKind.REFUND -> StatusBadge(BadgeKind.INFO, "Refund")
                        else -> StatusBadge(BadgeKind.OK, "Counted")
                    }
                }
                Text("${Categories.name(e.categoryId)} · ${DisplayDates.relative(e.effectiveDate, today)}", style = AppType.b2, color = t.muted)
                Text("Source: ${e.source.label}", style = AppType.cap, color = t.muted)
                e.note?.let { Text(it, style = AppType.b2, color = t.text) }
            }
        }
        if (voided) Banner(BadgeKind.INFO, "Removed from totals", e.voidReason?.let { "Reason: $it" }, "Restore") { scope.launch { graph.ledger.restoreExpense(id); state.toast("Restored") } }
        links.forEach { l ->
            when (l.kind) {
                LinkKind.RECEIPT -> ListRow("Receipt attached", meta = "Proof of this expense", leading = { Icon(AppIcons.receipt, null, tint = t.muted) },
                    trailing = { Icon(AppIcons.chev, null, tint = t.muted) }, onClick = { state.push(Screen.ReceiptDetail(l.targetId)) })
                LinkKind.BILL_PAYMENT -> ListRow("Bill payment", meta = "Paid towards a bill", leading = { Icon(AppIcons.bill, null, tint = t.muted) },
                    trailing = { Icon(AppIcons.chev, null, tint = t.muted) }, onClick = {
                        scope.launch { graph.ledgerStore.payment(l.targetId)?.let { state.push(Screen.BillDetail(it.invoiceId)) } }
                    })
            }
        }
        if (!voided && links.none { it.kind == LinkKind.RECEIPT } && e.kind != ExpenseKind.REFUND)
            AppButton("Attach a receipt", { state.push(Screen.Scanner("receipt")) }, variant = ButtonVariant.TONAL, icon = AppIcons.receipt)
        if (!voided) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (e.kind != ExpenseKind.REFUND) AppButton("Record refund", { refund = true }, Modifier.weight(1f), variant = ButtonVariant.TONAL, fill = true)
            AppButton("Remove", {
                scope.launch {
                    graph.ledger.voidExpense(id, "Removed by you")
                    state.pop()
                    state.undoable("Expense removed from totals") { graph.ledger.restoreExpense(id) }
                }
            }, Modifier.weight(1f), variant = ButtonVariant.DANGER_TONAL, fill = true,
                enabled = links.isEmpty(), disabledReason = "Delete the attached receipt or payment instead")
        }
        if (revisions.isNotEmpty()) {
            Section("History")
            revisions.sortedByDescending { it.version }.forEach { r ->
                ListRow(r.change, meta = DisplayDates.format(Instant.ofEpochMilli(r.changedAt.toEpochMilli()).atZone(graph.zone()).toLocalDate()), compact = true)
            }
        }
    }
    if (refund) RefundDialog(graph, state, e) { refund = false }
}

@Composable
private fun RefundDialog(graph: AppGraph, state: AppState, e: ExpenseEntry, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var amount by remember { mutableStateOf(e.amount.editText()) }
    val key = remember { "refund-" + UUID.randomUUID() }
    val m = parseMoney(amount, e.amount.currency)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record refund") },
        text = { Column { Text("Refunds reduce your totals. Up to ${MoneyFormat.format(e.amount)}."); MoneyField(amount, { amount = it }, "Refund amount", e.amount.currency, forceValidate = true) } },
        confirmButton = {
            TextButton({
                scope.launch {
                    try {
                        graph.ledger.recordRefund(m!!, LocalDate.now(graph.clock.withZone(graph.zone())), key, e.id)
                        onDismiss(); state.toast("Refund recorded")
                    } catch (x: LedgerValidationException) { state.toast(x.errors.joinToString { it.message }) }
                }
            }, enabled = m != null) { Text("Record") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** E-Form: add an expense by hand (source: manual) or edit an existing one. */
@Composable
fun ExpenseFormScreen(graph: AppGraph, state: AppState, id: String?, attachReceiptId: String?) {
    val existing by produceState<ExpenseEntry?>(null, id) { value = id?.let { graph.ledgerStore.expense(it) } }
    if (id != null && existing == null) { Page("Edit expense", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    ExpenseForm(graph, state, existing, attachReceiptId)
}

@Composable
private fun ExpenseForm(graph: AppGraph, state: AppState, e: ExpenseEntry?, attachReceiptId: String?) {
    val t = skin
    val scope = rememberCoroutineScope()
    val settings = graph.current
    val cur = e?.amount?.currency ?: graph.currency()
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    var amount by rememberSaveable { mutableStateOf(e?.amount?.editText().orEmpty()) }
    var merchant by rememberSaveable { mutableStateOf(e?.merchant.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(e?.note.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(e?.categoryId ?: settings.defaultCategory) }
    var date by remember { mutableStateOf(e?.effectiveDate ?: today) }
    var force by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val key = rememberSaveable { "manual-" + UUID.randomUUID() }
    var saving by remember { mutableIntStateOf(0) }

    fun save() {
        force = true
        val money = parseMoney(amount, cur) ?: return
        scope.launch {
            saving++
            try {
                if (e == null) {
                    val res = graph.ledger.addExpense(NewExpense(money, category, date, ExpenseSource.MANUAL, merchant.trim().ifBlank { null }, note.trim().ifBlank { null }), key, "ManualAdd")
                    if (attachReceiptId != null) {
                        graph.ledgerStore.receipt(attachReceiptId)?.let { confirm(graph, state, it, res.expense.id); return@launch }
                    }
                    state.pop()
                    if (res is AddResult.Added) state.undoable("Added ${MoneyFormat.format(money)} · ${Categories.name(category)}") { graph.ledger.undo(key) }
                } else {
                    graph.ledger.editExpense(e.id, ExpenseEdit(amount = money, categoryId = category, effectiveDate = date,
                        merchant = merchant.trim().ifBlank { null }, note = note.trim().ifBlank { null },
                        clearMerchant = merchant.isBlank(), clearNote = note.isBlank()))
                    state.pop(); state.toast("Saved")
                }
            } catch (x: LedgerValidationException) {
                errors = x.errors.associate { it.field to it.message }
            } finally { saving-- }
        }
    }

    Page(if (e == null) "Add expense" else "Edit expense", { state.pop() }, bottom = {
        AppButton(if (e == null) "Add expense" else "Save changes", ::save, fill = true, enabled = saving == 0)
    }) {
        if (e == null) Text("Counted once in your totals as \"Entered by you\". If you scan its receipt later, attach it instead of saving twice.", style = AppType.cap, color = t.muted)
        MoneyField(amount, { amount = it }, "Amount", cur, forceValidate = force, warn = errors["amount"])
        AppTextField(merchant, { merchant = it }, "Where (optional)", placeholder = "e.g. Shell, Imtiaz")
        DateField("Date", date, { date = it }, state = if (errors["date"] != null) FieldState.ERROR else FieldState.DEFAULT, helper = errors["date"] ?: if (date.isAfter(today)) "This date is in the future" else null)
        Section("Category")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Categories.builtIn.forEach { c -> AppChip(c.name, { category = c.id }, selected = category == c.id) }
        }
        errors["categoryId"]?.let { Text(it, style = AppType.cap, color = t.err) }
        AppTextField(note, { note = it }, "Note (optional)", singleLine = false, minLines = 2)
    }
}

/** B-Detail: a bill and its payments. A bill counts only through recorded payments, never twice. */
@Composable
fun BillDetailScreen(graph: AppGraph, state: AppState, id: String) {
    val t = skin
    val scope = rememberCoroutineScope()
    val bills by remember { graph.ledgerStore.observeInvoices() }.collectAsState(null)
    var version by remember { mutableIntStateOf(0) }
    val balance by produceState<InvoiceBalance?>(null, bills, version) { value = runCatching { graph.ledger.balance(id) }.getOrNull() }
    var pay by remember { mutableStateOf(false) }
    var voidId by remember { mutableStateOf<String?>(null) }
    var showText by remember { mutableStateOf(false) }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    if (bills == null) { Page("Bill", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    if (bills!!.none { it.id == id }) { LaunchedEffect(Unit) { state.pop() }; return }
    val b = balance ?: run { Page("Bill", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    val inv = b.invoice

    Page(inv.supplier, { state.pop() }) {
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(inv.amountDue), style = AppType.d1, color = t.text, modifier = Modifier.weight(1f))
                    BillStatusBadge(inv.status, inv.dueDate, today)
                }
                inv.dueDate?.let { Text("Due ${DisplayDates.relative(it, today)}", style = AppType.b1, color = if (it.isBefore(today) && b.remaining.minor > 0) t.err else t.text) }
                Text(listOfNotNull(inv.invoiceNumber?.let { "No. $it" }, inv.issueDate?.let { "Issued ${DisplayDates.format(it)}" }, Categories.name(inv.categoryId)).joinToString(" · "), style = AppType.cap, color = t.muted)
            }
        }
        if (inv.needsReview) Banner(BadgeKind.WARN, "Check the amount and due date", "Some values were unclear on the bill.")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text("Paid", style = AppType.b2, color = t.muted, modifier = Modifier.weight(1f)); Text(fmt(b.paid), style = AppType.num, color = t.text) }
                Row { Text("Left to pay", style = AppType.h2, color = t.text, modifier = Modifier.weight(1f)); Text(fmt(b.remaining), style = AppType.h2, color = t.text) }
                Text("Only payments count in your expenses, so a bill is never added twice.", style = AppType.cap, color = t.muted)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Record payment", { pay = true }, Modifier.weight(1f), fill = true, enabled = b.remaining.minor > 0, disabledReason = "Fully paid")
            AppButton("Remind me", {
                val at = (inv.dueDate?.minusDays(2)?.takeIf { it.isAfter(today) } ?: today.plusDays(1)).atTime(9, 0)
                state.reminderRequest = ReminderRequest("Pay ${inv.supplier}", "${fmt(b.remaining)} due ${inv.dueDate?.let { DisplayDates.format(it) } ?: "soon"}", at, ReminderChannel.BILLS.id, id)
            }, Modifier.weight(1f), variant = ButtonVariant.TONAL, fill = true, icon = AppIcons.bell)
        }
        if (b.payments.isNotEmpty()) {
            Section("Payments")
            b.payments.sortedByDescending { it.paidDate }.forEach { p ->
                ListRow(fmt(p.amount), meta = "${DisplayDates.relative(p.paidDate, today)}${p.method?.let { " · $it" } ?: ""}",
                    leading = { Icon(AppIcons.checkCircle, null, tint = t.ok) },
                    trailing = { AppIconButton(AppIcons.trash, "Remove payment", { voidId = p.id }) },
                    onClick = { state.push(Screen.ExpenseDetail(p.expenseId)) })
            }
        }
        if (inv.rawText.isNotBlank()) {
            AppButton(if (showText) "Hide recognised text" else "Show recognised text", { showText = !showText }, variant = ButtonVariant.GHOST)
            if (showText) Text(inv.rawText, style = AppType.cap, color = t.muted)
        }
    }
    if (pay) PaymentDialog(graph, state, b, onDismiss = { pay = false }) { version++ }
    voidId?.let { pid ->
        AlertDialog(
            onDismissRequest = { voidId = null },
            title = { Text("Remove this payment?") },
            text = { Text("Its expense stops counting and the bill shows as unpaid again.") },
            confirmButton = { TextButton({ voidId = null; scope.launch { graph.ledger.voidPayment(pid); version++; state.toast("Payment removed") } }) { Text("Remove") } },
            dismissButton = { TextButton({ voidId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PaymentDialog(graph: AppGraph, state: AppState, b: InvoiceBalance, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cur = b.invoice.amountDue.currency
    var amount by remember { mutableStateOf(b.remaining.editText()) }
    var date by remember { mutableStateOf(LocalDate.now(graph.clock.withZone(graph.zone()))) }
    var method by remember { mutableStateOf("") }
    val key = remember { "pay-" + UUID.randomUUID() }
    val m = parseMoney(amount, cur)
    val tooMuch = m != null && m > b.remaining
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record payment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(amount, { amount = it }, "Amount paid", cur, forceValidate = true, warn = if (tooMuch) "More than the ${MoneyFormat.format(b.remaining)} left" else null)
                DateField("Paid on", date, { date = it })
                AppTextField(method, { method = it }, "How (optional)", placeholder = "Cash, card, bank app")
            }
        },
        confirmButton = {
            TextButton({
                scope.launch {
                    try {
                        graph.ledger.recordPayment(b.invoice.id, m!!, date, key, method.trim().ifBlank { null })
                        onDismiss(); onDone(); state.toast("Payment recorded · counted once")
                    } catch (x: Exception) { state.toast(x.message ?: "Couldn't record the payment") }
                }
            }, enabled = m != null && !tooMuch) { Text("Record") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
