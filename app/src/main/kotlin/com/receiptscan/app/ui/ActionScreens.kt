package com.receiptscan.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.receiptscan.actions.Comparison
import com.receiptscan.actions.PackSize
import com.receiptscan.actions.PriceCompare
import com.receiptscan.actions.PricePoint
import com.receiptscan.actions.SplitBill
import com.receiptscan.actions.SplitLine
import com.receiptscan.actions.SplitMode
import com.receiptscan.actions.SplitResult
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppState
import com.receiptscan.app.Screen
import com.receiptscan.data.local.BudgetRow
import com.receiptscan.data.local.ReminderRow
import com.receiptscan.data.local.ShoppingItemRow
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppProgress
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.EmptyState
import com.receiptscan.design.ListRow
import com.receiptscan.design.SkeletonBlock
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.Receipt
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.insights.Budget
import com.receiptscan.insights.BudgetStatus
import com.receiptscan.insights.Budgets
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat
import com.receiptscan.ocr.model.TextNormalizer
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.MoneyField
import com.receiptscan.scanner.ui.editText
import com.receiptscan.scanner.ui.parseMoney
import com.receiptscan.time.DisplayDates
import com.receiptscan.time.LocalDateRange
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private enum class SplitHow(val label: String) { EQUAL("Equally"), ITEMS("By item"), CUSTOM("Custom amounts") }

/** A-Split: share a receipt between people. Every share adds up to the receipt total exactly. */
@Composable
fun SplitScreen(graph: AppGraph, state: AppState, receiptId: String) {
    val r by produceState<Receipt?>(null, receiptId) { value = graph.ledgerStore.receipt(receiptId) }
    val rc = r ?: run { Page("Split", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    SplitForm(state, rc)
}

@Composable
private fun SplitForm(state: AppState, r: Receipt) {
    val t = skin
    val context = LocalContext.current
    val cur = r.currency
    val zero = Money.zero(cur)
    val people = remember { mutableStateListOf("Me", "Friend") }
    var newPerson by remember { mutableStateOf("") }
    var how by rememberSaveable { mutableStateOf(if (r.items.isEmpty()) SplitHow.EQUAL else SplitHow.ITEMS) }
    var tax by remember { mutableStateOf(r.tax?.editText().orEmpty()) }
    var service by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("") }
    var discount by remember { mutableStateOf(r.discount?.editText().orEmpty()) }
    val assignments = remember { mutableStateMapOf<String, Set<String>>() }
    val custom = remember { mutableStateMapOf<String, String>() }
    // Lines: the items, or one line with the pre-tax total when the receipt lists none.
    val lines = remember(r) {
        if (r.items.isNotEmpty()) r.items.sortedBy { it.position }.map { SplitLine(it.id, it.description, it.lineTotal) }
        else listOf(SplitLine("total", "Receipt", (r.total ?: zero) - (r.tax ?: zero) + (r.discount ?: zero)))
    }
    val mode = when (how) {
        SplitHow.EQUAL -> SplitMode.Equally
        SplitHow.ITEMS -> SplitMode.ByItem(lines.associate { l -> l.id to (assignments[l.id] ?: people.toSet()) })
        SplitHow.CUSTOM -> SplitMode.Custom(people.associateWith { p -> parseMoney(custom[p].orEmpty(), cur, true) ?: zero })
    }
    val result = if (people.isEmpty()) SplitResult.Invalid("Add at least one person") else runCatching {
        SplitBill.split(people.toList(), lines, parseMoney(tax, cur, true) ?: zero, parseMoney(service, cur, true) ?: zero, parseMoney(discount, cur, true) ?: zero, parseMoney(tip, cur, true) ?: zero, mode)
    }.getOrElse { SplitResult.Invalid(it.message ?: "Can't split this") }

    Page("Split ${r.merchant ?: "receipt"}", { state.pop() }, bottom = {
        AppButton("Share split", { (result as? SplitResult.Ok)?.let { share(context, "${r.merchant ?: "Receipt"} · ${r.purchaseDate?.let { d -> DisplayDates.format(d) } ?: ""}\n" + it.summary(), "Split") } },
            fill = true, icon = AppIcons.share, enabled = result is SplitResult.Ok, disabledReason = (result as? SplitResult.Invalid)?.message)
    }) {
        Text("Receipt total ${fmt(r.total)}. Tax, service and discount are shared in proportion to each person's items.", style = AppType.cap, color = t.muted)
        Section("People")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            people.forEach { p -> AppChip("$p  ✕", { people.remove(p); custom.remove(p) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(newPerson, { newPerson = it }, "Add person", Modifier.weight(1f), onDone = { if (newPerson.isNotBlank() && newPerson.trim() !in people) { people.add(newPerson.trim()); newPerson = "" } })
            AppIconButton(AppIcons.plus, "Add person", { if (newPerson.isNotBlank() && newPerson.trim() !in people) { people.add(newPerson.trim()); newPerson = "" } }, surface = true)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SplitHow.entries.forEach { h -> AppChip(h.label, { how = h }, selected = how == h, enabled = h != SplitHow.ITEMS || r.items.isNotEmpty()) }
        }
        when (how) {
            SplitHow.ITEMS -> lines.forEach { l ->
                val who = assignments[l.id] ?: people.toSet()
                AppCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Text(l.label, style = AppType.b1, color = t.text, modifier = Modifier.weight(1f)); Text(MoneyFormat.format(l.amount, false), style = AppType.num, color = t.text) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            people.forEach { p -> AppChip(p, { assignments[l.id] = if (p in who) who - p else who + p }, selected = p in who) }
                        }
                        if (who.none { it in people }) Text("Pick who shares this item", style = AppType.cap, color = t.warn)
                    }
                }
            }
            SplitHow.CUSTOM -> people.forEach { p -> MoneyField(custom[p].orEmpty(), { custom[p] = it }, p, cur, allowZero = true) }
            SplitHow.EQUAL -> {}
        }
        Section("Extras")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField(tax, { tax = it }, "Tax", cur, Modifier.weight(1f), allowZero = true)
            MoneyField(service, { service = it }, "Service", cur, Modifier.weight(1f), allowZero = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField(tip, { tip = it }, "Tip", cur, Modifier.weight(1f), allowZero = true)
            MoneyField(discount, { discount = it }, "Discount", cur, Modifier.weight(1f), allowZero = true)
        }
        Section("Shares")
        when (result) {
            is SplitResult.Invalid -> Banner(BadgeKind.WARN, "Can't split yet", result.message)
            is SplitResult.Ok -> {
                result.shares.forEach { s ->
                    ListRow(s.person, meta = "Items ${MoneyFormat.format(s.items, false)} · tax ${MoneyFormat.format(s.tax, false)}" + (if (!s.tip.isZero) " · tip ${MoneyFormat.format(s.tip, false)}" else "") + (if (!s.discount.isZero) " · −${MoneyFormat.format(s.discount, false)}" else ""),
                        trailing = { Text(MoneyFormat.format(s.total), style = AppType.h2, color = t.text) })
                }
                Text("Adds up to ${MoneyFormat.format(result.total)}", style = AppType.cap, color = t.muted)
                if (r.total != null && result.total != r.total) Text("This differs from the receipt total ${fmt(r.total)} because of the extras you changed.", style = AppType.cap, color = t.warn)
            }
        }
    }
}

/** A-Compare: price change per item against your earlier confirmed receipts. Shows the formula. */
@Composable
fun CompareScreen(graph: AppGraph, state: AppState, receiptId: String) {
    val t = skin
    val data by produceState<Pair<Receipt, List<Pair<String, Comparison>>>?>(null, receiptId) {
        val r = graph.ledgerStore.receipt(receiptId) ?: return@produceState
        val history = graph.ledgerStore.receipts(ReceiptFilter(statuses = setOf(ReceiptStatus.CONFIRMED))).filter { it.purchaseDate != null }
        fun point(rc: Receipt, i: com.receiptscan.expenses.ReceiptItem) =
            PricePoint(rc.id, rc.merchant, rc.purchaseDate!!, i.description, i.unitPrice ?: i.lineTotal, PackSize.from(i.description))
        val rows = r.items.filter { r.purchaseDate != null }.map { item ->
            val key = TextNormalizer.key(item.description)
            val points = history.flatMap { rc -> rc.items.filter { TextNormalizer.key(it.description) == key && it.lineTotal.currency == item.lineTotal.currency }.map { point(rc, it) } }
                .filter { !it.date.isAfter(r.purchaseDate!!) }
                .let { pts -> if (pts.none { it.receiptId == r.id }) pts + point(r, item) else pts }
            item.description to runCatching { PriceCompare.compare(points) }.getOrElse { Comparison.NotComparable(it.message ?: "Not comparable") }
        }
        value = r to rows
    }
    val d = data ?: run { Page("Compare prices", { state.pop() }) { SkeletonBlock(Modifier.fillMaxWidth().height(120.dp)) }; return }
    val (r, rows) = d
    Page("Compare prices", { state.pop() }) {
        Text("${r.merchant ?: "Receipt"} · ${r.purchaseDate?.let { DisplayDates.format(it) } ?: "no date"}. Compared with the same item on your earlier confirmed receipts.", style = AppType.cap, color = t.muted)
        if (r.purchaseDate == null) Banner(BadgeKind.WARN, "This receipt has no date", "Add the date to compare prices over time.")
        if (rows.isEmpty() && r.purchaseDate != null) EmptyState(AppIcons.trend, "No items to compare", "This receipt has no item lines.")
        rows.forEach { (name, c) ->
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = AppType.b1, color = t.text)
                    when (c) {
                        is Comparison.Change -> {
                            val up = c.percent.signum() > 0
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusBadge(if (up) BadgeKind.WARN else if (c.percent.signum() < 0) BadgeKind.OK else BadgeKind.NEUTRAL,
                                    if (c.percent.signum() == 0) "Same price" else "${if (up) "Up" else "Down"} ${c.percent.abs().toPlainString()}%")
                                Text("${MoneyFormat.format(c.latestPerUnit)} ${c.unitLabel}", style = AppType.num, color = t.text)
                            }
                            Text("Before: ${MoneyFormat.format(c.previousPerUnit)} ${c.unitLabel} at ${c.previous.merchant ?: "—"} on ${DisplayDates.format(c.previous.date)}", style = AppType.cap, color = t.muted)
                            Text(c.formula, style = AppType.cap, color = t.muted)
                        }
                        is Comparison.NoHistory -> Text("First time on your receipts", style = AppType.cap, color = t.muted)
                        is Comparison.NotComparable -> Text(c.reason, style = AppType.cap, color = t.muted)
                    }
                }
            }
        }
    }
}

/** Shopping list built from receipts or typed. */
@Composable
fun ShoppingScreen(graph: AppGraph, state: AppState) {
    val t = skin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by remember { graph.records.observeShopping() }.collectAsState(null)
    var text by remember { mutableStateOf("") }
    fun add() {
        val name = text.trim(); if (name.isEmpty()) return
        text = ""
        scope.launch { graph.records.saveShopping(ShoppingItemRow(UUID.randomUUID().toString(), name, null, null, false, graph.clock.millis())) }
    }
    Page("Shopping list", { state.pop() }, actions = {
        val open = items.orEmpty().filter { !it.done }
        if (open.isNotEmpty()) AppIconButton(AppIcons.share, "Share list", { share(context, open.joinToString("\n") { "• ${it.name}${it.quantity?.let { q -> " × $q" } ?: ""}" }, "Shopping list") })
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppTextField(text, { text = it }, "Add an item", Modifier.weight(1f), onDone = ::add)
            AppIconButton(AppIcons.plus, "Add", ::add, surface = true)
        }
        val list = items
        when {
            list == null -> SkeletonBlock(Modifier.fillMaxWidth().height(56.dp))
            list.isEmpty() -> EmptyState(AppIcons.basket, "Nothing to buy", "Add items here, or open a receipt and choose Shopping list to buy things again.")
            else -> {
                list.sortedBy { it.done }.forEach { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(s.done, { c -> scope.launch { graph.records.saveShopping(s.copy(done = c)) } })
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = AppType.b1.copy(textDecoration = if (s.done) TextDecoration.LineThrough else null), color = if (s.done) t.muted else t.text)
                            if (s.fromReceiptId != null) Text("From a receipt", style = AppType.cap, color = t.muted)
                        }
                    }
                }
                if (list.any { it.done }) AppButton("Clear ticked items", { scope.launch { graph.records.clearDoneShopping() } }, variant = ButtonVariant.GHOST, icon = AppIcons.trash)
            }
        }
    }
}

/** Food log: calorie estimates from the Calories tool, per day. Estimates only. */
@Composable
fun FoodLogScreen(graph: AppGraph, state: AppState) {
    val t = skin
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    var day by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val entries by remember(day) { graph.records.observeFood(day) }.collectAsState(emptyList())
    val date = LocalDate.ofEpochDay(day)
    Page("Food log", { state.pop() }, bottom = {
        AppButton("Estimate a meal", { openTool(state, "food", graph) }, fill = true, icon = AppIcons.food)
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconButton(AppIcons.back, "Previous day", { day -= 1 })
            Text(DisplayDates.relative(date, today), style = AppType.h2, color = t.text, modifier = Modifier.weight(1f))
            AppIconButton(AppIcons.chev, "Next day", { day += 1 }, enabled = date.isBefore(today))
        }
        val kcal = entries.sumOf { it.kcal }
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Text("≈ ${kcal.toInt()} kcal", style = AppType.d1, color = t.text)
                val p = entries.mapNotNull { it.proteinG }.sum(); val c = entries.mapNotNull { it.carbsG }.sum(); val f = entries.mapNotNull { it.fatG }.sum()
                if (entries.isNotEmpty()) Text("Protein ≈ ${p.toInt()} g · Carbs ≈ ${c.toInt()} g · Fat ≈ ${f.toInt()} g", style = AppType.b2, color = t.muted)
                Text("Estimates from USDA reference values and your portion sizes. Not medical advice.", style = AppType.cap, color = t.muted)
            }
        }
        if (entries.isEmpty()) EmptyState(AppIcons.food, "Nothing logged", "Use Estimate a meal, then Add to food log.")
        entries.forEach { e ->
            ListRow(e.name, meta = "${e.grams.toInt()} g", trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("≈ ${e.kcal.toInt()} kcal", style = AppType.num, color = t.text)
                    AppIconButton(AppIcons.trash, "Remove", {
                        scope.launch {
                            graph.records.deleteFood(e.id)
                            state.undoable("Removed ${e.name}") { graph.records.logFood(e) }
                        }
                    })
                }
            })
        }
    }
}

/** Budgets: monthly limits per category or overall, measured against confirmed expenses. */
@Composable
fun BudgetsScreen(graph: AppGraph, state: AppState) {
    val t = skin
    val scope = rememberCoroutineScope()
    val budgets by remember { graph.records.observeBudgets() }.collectAsState(emptyList())
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    val month = remember(today) { LocalDateRange(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1)) }
    val entries by remember(month) { graph.ledgerStore.observeExpenses(ExpenseFilter(range = month)) }.collectAsState(emptyList())
    var category by rememberSaveable { mutableStateOf("all") }
    var amount by rememberSaveable { mutableStateOf("") }
    val cur = graph.currency()
    Page("Budgets", { state.pop() }) {
        Text("Monthly limits. Spending counts confirmed expenses in ${cur.code} only; refunds reduce it.", style = AppType.cap, color = t.muted)
        if (budgets.isEmpty()) EmptyState(AppIcons.insights, "No budgets yet", "Pick a category and a monthly amount below.")
        budgets.forEach { b ->
            val bc = com.receiptscan.app.currencyOf(b.currency)
            val spentMinor = entries.filter { it.amount.currency.code == b.currency && (b.categoryId == "all" || it.categoryId == b.categoryId) }.sumOf { it.signedAmount.minor }
            val st = Budgets.status(Budget(b.categoryId, b.categoryId.takeIf { it != "all" }, Money(b.amountMinor, bc)), Money(spentMinor, bc))
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (b.categoryId == "all") "Everything" else Categories.name(b.categoryId), style = AppType.h2, color = t.text, modifier = Modifier.weight(1f))
                        StatusBadge(when (st.state) { BudgetStatus.State.OVER -> BadgeKind.ERR; BudgetStatus.State.WARNING -> BadgeKind.WARN; else -> BadgeKind.OK }, "${st.percent}%")
                        AppIconButton(AppIcons.trash, "Remove budget", { scope.launch { graph.records.removeBudget(b.categoryId); state.undoable("Budget removed") { graph.records.setBudget(b) } } })
                    }
                    AppProgress((st.percent / 100f).coerceIn(0f, 1f))
                    Text("${MoneyFormat.format(st.spent)} spent · ${st.message}", style = AppType.cap, color = t.muted)
                }
            }
        }
        Section("Add or change a budget")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip("Everything", { category = "all" }, selected = category == "all")
            Categories.builtIn.forEach { c -> AppChip(c.name, { category = c.id }, selected = category == c.id) }
        }
        MoneyField(amount, { amount = it }, "Monthly limit", cur)
        AppButton("Save budget", {
            val m = parseMoney(amount, cur) ?: return@AppButton
            scope.launch { graph.records.setBudget(BudgetRow(category, m.minor, cur.code)); amount = ""; state.toast("Budget saved") }
        }, fill = true, enabled = parseMoney(amount, cur) != null, disabledReason = "Enter an amount")
    }
}

/** Reminders: open reminders for bills, warranties, expiry dates and receipts. */
@Composable
fun RemindersScreen(graph: AppGraph, state: AppState) {
    val t = skin
    val scope = rememberCoroutineScope()
    val reminders by remember { graph.records.observeOpenReminders() }.collectAsState(null)
    var allowed by remember { mutableStateOf(graph.reminders.notificationsAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; scope.launch { graph.prefs.setNotificationsAsked() } }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    Page("Reminders", { state.pop() }) {
        if (!allowed) Banner(BadgeKind.WARN, "Notifications are off", "Reminders are kept here but won't pop up.", if (Build.VERSION.SDK_INT >= 33) "Allow" else null) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val list = reminders
        when {
            list == null -> SkeletonBlock(Modifier.fillMaxWidth().height(56.dp))
            list.isEmpty() -> EmptyState(AppIcons.bell, "No reminders", "Set one from a bill, warranty, expiry date or receipt.")
            else -> list.sortedBy { it.dueAt }.forEach { r -> ReminderItem(graph, state, r, today) }
        }
    }
}

@Composable
private fun ReminderItem(graph: AppGraph, state: AppState, r: ReminderRow, today: LocalDate) {
    val t = skin
    val scope = rememberCoroutineScope()
    val due = Instant.ofEpochMilli(r.dueAt).atZone(graph.zone())
    ListRow(r.title, meta = "${DisplayDates.relative(due.toLocalDate(), today)} · ${"%d:%02d".format(due.hour, due.minute)} · ${com.receiptscan.app.ReminderChannel.of(r.channel).title}",
        leading = { Icon(AppIcons.bell, null, tint = if (due.toLocalDate().isBefore(today)) t.err else t.muted) },
        trailing = {
            AppIconButton(AppIcons.check, "Mark done", {
                scope.launch {
                    graph.reminders.cancel(r.id)
                    graph.records.saveReminder(r.copy(done = true))
                    state.undoable("Marked done") { graph.records.saveReminder(r); graph.reminders.enqueue(r) }
                }
            })
        },
        onClick = { r.recordId?.let { state.pendingRecord = it } })
}
