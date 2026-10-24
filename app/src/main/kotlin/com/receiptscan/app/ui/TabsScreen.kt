package com.receiptscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.receiptscan.app.AppGraph
import kotlinx.coroutines.launch
import com.receiptscan.app.AppState
import com.receiptscan.app.Modules
import com.receiptscan.app.Screen
import com.receiptscan.app.Tab
import com.receiptscan.capture.rememberDocumentPicker
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIconButton
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonSize
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ConfirmedBadge
import com.receiptscan.design.FloatingNavBar
import com.receiptscan.design.ListRow
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.NavItem
import com.receiptscan.design.NeedsReviewBadge
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.CommandPill
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.feature.documents.DocumentsFeature
import com.receiptscan.money.totalsByCurrency
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.time.DisplayDates
import com.receiptscan.time.LocalDateRange
import java.time.LocalDate

/** Tab roots with the floating nav. Settings opens from the app bar on every tab (handover section 2). */
@Composable
fun TabsScreen(graph: AppGraph, state: AppState) {
    ScreenSurface {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                AppTopBar(if (state.tab == Tab.HOME) "Receipt & Scan" else state.tab.label, brand = state.tab == Tab.HOME, actions = {
                    AppIconButton(AppIcons.search, "Search", { state.push(Screen.Search) })
                    AppIconButton(AppIcons.settings, "Settings", { state.push(Screen.Settings) })
                })
                Box(Modifier.weight(1f)) {
                    when (state.tab) {
                        Tab.HOME -> HomeTab(graph, state)
                        Tab.LIBRARY -> LibraryTab(graph, state)
                        Tab.TOOLS -> ToolsTab(graph, state)
                        Tab.INSIGHTS -> InsightsTab(graph, state)
                    }
                }
            }
            FloatingNavBar(
                Tab.entries.map { NavItem(it.label, when (it) { Tab.HOME -> AppIcons.home; Tab.LIBRARY -> AppIcons.library; Tab.TOOLS -> AppIcons.tools; Tab.INSIGHTS -> AppIcons.insights }) },
                state.tab.ordinal, { state.tab = Tab.entries[it] }, Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

fun openTool(state: AppState, id: String, graph: AppGraph, source: ScanSource = ScanSource.Camera) {
    graph.scope.launch { graph.prefs.touchTool(id) }
    if (id == DocumentsFeature.ID.value) state.push(Screen.Pdf) else state.push(Screen.Scanner(id, source))
}


@Composable
fun HomeTab(graph: AppGraph, state: AppState) {
    val t = skin
    val settings by graph.settings.collectAsState()
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    val month = LocalDateRange(today.withDayOfMonth(1), today.plusDays(1))
    val receipts by remember { graph.ledgerStore.observeReceipts(ReceiptFilter()) }.collectAsState(null)
    val monthEntries by remember(month) { graph.ledgerStore.observeExpenses(ExpenseFilter(range = month)) }.collectAsState(emptyList())
    val reminders by remember { graph.records.observeOpenReminders() }.collectAsState(emptyList())
    val importPicker = rememberDocumentPicker(arrayOf("image/*", "application/pdf")) { uri ->
        val type = graph.context.contentResolver.getType(uri).orEmpty()
        state.push(Screen.Scanner("receipt", if (type == "application/pdf") ScanSource.Pdf(uri.toString()) else ScanSource.Image(uri.toString())))
    }
    val list = receipts
    val needsReview = list.orEmpty().filter { it.status == ReceiptStatus.NEEDS_REVIEW }
    val firstUse = list != null && list.isEmpty() && monthEntries.isEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Hero
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (firstUse) "Welcome" else "Scan a receipt", style = AppType.h1, color = t.text)
                Text(if (firstUse) "Turn paper receipts into tidy expenses. Everything stays on this phone." else "Items, tax and totals, checked before they count.", style = AppType.b2, color = t.muted)
                if (firstUse) {
                    listOf("1. Scan or import a receipt", "2. Check what we read", "3. Confirm — it counts in your totals").forEach { Text(it, style = AppType.b2, color = t.text) }
                }
                AppButton("Scan receipt", { openTool(state, "receipt", graph) }, fill = true, icon = AppIcons.scan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Import", { importPicker() }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.import, fill = true)
                    AppButton("Add manually", { state.push(Screen.ExpenseForm()) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.plus, fill = true)
                }
            }
        }
        // Command surface with mic (brief 8.1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommandPill("Ask or add an expense…", { state.openCommands(false) }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            AppIconButton(AppIcons.mic, "Ask by voice", { state.openCommands(true) }, surface = true)
        }
        if (!firstUse) {
            val totals = monthEntries.map { it.signedAmount }.totalsByCurrency().values.toList()
            AppCard(Modifier.fillMaxWidth(), onClick = { state.tab = Tab.INSIGHTS }) {
                Column {
                    Text("This month · confirmed", style = AppType.lab, color = t.muted)
                    if (totals.isEmpty()) Text("Nothing confirmed yet", style = AppType.b1, color = t.text)
                    totals.forEach { Text(fmt(it), style = AppType.d1, color = t.text) }
                    Text("${monthEntries.size} expense${if (monthEntries.size == 1) "" else "s"} · receipts, voice and manual, counted once", style = AppType.cap, color = t.muted)
                }
            }
        }
        if (needsReview.isNotEmpty()) {
            Banner(BadgeKind.WARN, "${needsReview.size} receipt${if (needsReview.size == 1) "" else "s"} need review", "They aren't counted until you confirm them.", "Review", { state.push(Screen.ReceiptEdit(needsReview.first().id)) })
        }
        // Quick tools: 4 recent + All tools
        Section("Quick tools")
        val recent = (settings.recentTools + listOf("invoice", "qr", "text", "product")).distinct().filter { it != "receipt" && Modules.info(it) != null }.take(4)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            recent.forEach { id ->
                val info = Modules.info(id)!!
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { openTool(state, id, graph) }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ModuleTile(info.color, info.icon, size = 52.dp)
                    Text(info.title, style = AppType.cap, color = t.text, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { state.tab = Tab.TOOLS }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(t.surface2), contentAlignment = Alignment.Center) { Icon(AppIcons.grid, null, tint = t.text) }
                Text("All tools", style = AppType.cap, color = t.text, maxLines = 1)
            }
        }
        if (reminders.isNotEmpty()) {
            Section("Coming up") { AppButton("All", { state.push(Screen.Reminders) }, variant = ButtonVariant.GHOST, size = ButtonSize.SMALL) }
            reminders.take(3).forEach { r ->
                ListRow(r.title, meta = DisplayDates.format(java.time.Instant.ofEpochMilli(r.dueAt).atZone(graph.zone()).toLocalDate()) + " · " + r.body.take(40),
                    leading = { Icon(AppIcons.bell, null, tint = t.muted) }, onClick = { r.recordId?.let { state.pendingRecord = it } ?: state.push(Screen.Reminders) })
            }
        }
        Section("Recent receipts") { if (list.orEmpty().isNotEmpty()) AppButton("Library", { state.tab = Tab.LIBRARY }, variant = ButtonVariant.GHOST, size = ButtonSize.SMALL) }
        when {
            list == null -> repeat(3) { com.receiptscan.design.SkeletonBlock(Modifier.fillMaxWidth().height(56.dp)) }
            list.isEmpty() -> Text("Your scanned receipts appear here.", style = AppType.b2, color = t.muted)
            else -> list.take(5).forEach { r -> ReceiptRow(r, today) { state.push(if (r.status == ReceiptStatus.NEEDS_REVIEW) Screen.ReceiptEdit(r.id) else Screen.ReceiptDetail(r.id)) } }
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
fun ReceiptRow(r: com.receiptscan.expenses.Receipt, today: LocalDate, onClick: () -> Unit) {
    val t = skin
    ListRow(
        r.merchant ?: "Receipt",
        meta = listOfNotNull(r.purchaseDate?.let { DisplayDates.relative(it, today) }, "${r.items.size} item${if (r.items.size == 1) "" else "s"}").joinToString(" · "),
        leading = { ModuleTile(com.receiptscan.design.ModuleColor.RECEIPT, AppIcons.receipt, size = 40.dp) },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtShort(r.total), style = AppType.num, color = t.text)
                if (r.status == ReceiptStatus.NEEDS_REVIEW) NeedsReviewBadge() else ConfirmedBadge()
            }
        },
        onClick = onClick,
    )
}

@Composable
fun ToolsTab(graph: AppGraph, state: AppState) {
    val t = skin
    val settings by graph.settings.collectAsState()
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.receiptscan.design.AppTextField(query, { query = it }, "Search 14 tools", leading = { Icon(AppIcons.search, null) })
        val recent = settings.recentTools.filter { Modules.info(it) != null }.take(5)
        if (recent.isNotEmpty() && query.isBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recent.forEach { id -> AppChip(Modules.info(id)!!.title, { openTool(state, id, graph) }, icon = Modules.info(id)!!.icon) }
        }
        Modules.groups.forEach { (group, ids) ->
            val shown = ids.mapNotNull { Modules.info(it) }.filter { query.isBlank() || it.title.contains(query, true) || it.subtitle.contains(query, true) }
            if (shown.isNotEmpty()) {
                Text(group.uppercase(), style = AppType.ov, color = t.muted)
                FlowRow(maxItemsInEachRow = 2, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    shown.forEach { info ->
                        AppCard(Modifier.weight(1f), onClick = { openTool(state, info.id.value, graph) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                ModuleTile(info.color, info.icon)
                                Text(info.title, style = AppType.h2, color = t.text)
                                Text(info.subtitle, style = AppType.cap, color = t.muted, maxLines = 2)
                                if (info.requiredPacks.isNotEmpty()) StatusBadge(BadgeKind.NEUTRAL, "Feature pack", icon = AppIcons.download)
                            }
                        }
                    }
                    if (shown.size % 2 == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Text("More", style = AppType.ov, color = t.muted)
        ListRow("Food log", meta = "Today's calorie estimates", leading = { Icon(AppIcons.food, null, tint = t.muted) }, onClick = { state.push(Screen.FoodLog) })
        ListRow("Shopping list", meta = "Items to buy again", leading = { Icon(AppIcons.tag, null, tint = t.muted) }, onClick = { state.push(Screen.Shopping) })
        ListRow("Reminders", meta = "Bills, warranties, expiry", leading = { Icon(AppIcons.bell, null, tint = t.muted) }, onClick = { state.push(Screen.Reminders) })
        Spacer(Modifier.height(110.dp))
    }
}
