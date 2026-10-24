package com.receiptscan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppState
import com.receiptscan.app.Screen
import com.receiptscan.app.currencyOf
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppProgress
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.EmptyState
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.skin
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.expenses.ReceiptFilter
import com.receiptscan.expenses.ReceiptStatus
import com.receiptscan.insights.Budget
import com.receiptscan.insights.BudgetStatus
import com.receiptscan.insights.Budgets
import com.receiptscan.insights.Forecaster
import com.receiptscan.insights.InsightPeriod
import com.receiptscan.insights.InsightsCalculator
import com.receiptscan.money.Money
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.time.DisplayDates
import com.receiptscan.time.LocalDateRange
import java.time.LocalDate

@Composable
fun InsightsTab(graph: AppGraph, state: AppState) {
    val t = skin
    var period by rememberSaveable { mutableStateOf(InsightPeriod.MONTH) }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    val all by remember { graph.ledgerStore.observeExpenses(ExpenseFilter()) }.collectAsState(null)
    val receipts by remember { graph.ledgerStore.observeReceipts(ReceiptFilter(setOf(ReceiptStatus.NEEDS_REVIEW))) }.collectAsState(emptyList())
    val budgets by remember { graph.records.observeBudgets() }.collectAsState(emptyList())
    val range = InsightsCalculator.range(period, today)
    val summary by produceState<com.receiptscan.insights.InsightsSummary?>(null, all, period) {
        val entries = all ?: return@produceState
        val filter = ExpenseFilter(range = range)
        value = InsightsCalculator.summarize(graph.ledger.summarize(filter, entries.filter(filter::matches)), range, period, graph.currency().code)
    }
    val entries = all
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InsightPeriod.entries.forEach { p -> AppChip(p.name.lowercase().replaceFirstChar { it.uppercase() }, { period = p }, selected = period == p) }
        }
        if (receipts.isNotEmpty()) Banner(BadgeKind.WARN, "${receipts.size} receipt${if (receipts.size == 1) "" else "s"} not counted", "Review them to include them here.", "Review", { state.push(Screen.ReceiptEdit(receipts.first().id)) })
        val s = summary
        when {
            entries == null -> repeat(3) { com.receiptscan.design.SkeletonBlock(Modifier.fillMaxWidth(), height = 72.dp) }
            entries.isEmpty() -> EmptyState(AppIcons.insights, "No confirmed spending yet", "Confirm a receipt or add an expense by voice or by hand. Insights use confirmed records only.", actionLabel = "Scan receipt", onAction = { openTool(state, "receipt", graph) }, secondaryLabel = "Add expense", onSecondary = { state.push(Screen.ExpenseForm()) })
            s == null -> Banner(BadgeKind.INFO, "Nothing in this period", "No confirmed expenses ${DisplayDates.formatRange(range)}.")
            else -> {
                AppCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Confirmed spending", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f)); SourceBadge(ValueSource.CALCULATED) }
                        Text(fmt(s.total), style = AppType.d1, color = t.text)
                        Text("${s.count} record${if (s.count == 1) "" else "s"} · ${DisplayDates.formatRange(s.range)}", style = AppType.cap, color = t.muted)
                        s.otherCurrencies.forEach { Text("Also ${fmt(it)} (kept separate)", style = AppType.cap, color = t.muted) }
                        Text("Recorded expenses only, not your full bank spending.", style = AppType.cap, color = t.muted)
                    }
                }
                Section("By category")
                val max = s.categories.maxOfOrNull { it.total.minor }?.coerceAtLeast(1) ?: 1
                s.categories.take(8).forEach { c ->
                    Column(Modifier.fillMaxWidth()) {
                        Row { Text(Categories.name(c.categoryId), style = AppType.b2, color = t.text, modifier = Modifier.weight(1f)); Text(fmtShort(c.total), style = AppType.num, color = t.text) }
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(t.surface2)) {
                            Box(Modifier.fillMaxWidth((c.total.minor.toFloat() / max).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(t.primary))
                        }
                    }
                }
                Section("Over time")
                val bmax = s.buckets.maxOfOrNull { it.total.minor }?.coerceAtLeast(1) ?: 1
                Row(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Spending over time chart" }, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                    s.buckets.forEach { b ->
                        Box(Modifier.weight(1f).fillMaxHeight((b.total.minor.toFloat() / bmax).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (b.total.minor > 0) t.primary else t.surface2))
                    }
                }
                Row { Text(s.buckets.firstOrNull()?.label.orEmpty(), style = AppType.cap, color = t.muted, modifier = Modifier.weight(1f)); Text(s.buckets.lastOrNull()?.label.orEmpty(), style = AppType.cap, color = t.muted) }
                if (s.merchants.isNotEmpty()) {
                    Section("Top merchants")
                    s.merchants.forEach { m -> Row(Modifier.fillMaxWidth()) { Text("${m.merchant} · ${m.count}", style = AppType.b2, color = t.text, modifier = Modifier.weight(1f)); Text(fmtShort(m.total), style = AppType.num, color = t.text) } }
                }
                Section("By source")
                s.sources.forEach { src -> Row(Modifier.fillMaxWidth()) { Text("${src.source.label} · ${src.count}", style = AppType.b2, color = t.text, modifier = Modifier.weight(1f)); Text(fmtShort(src.total), style = AppType.num, color = t.text) } }
            }
        }
        if (entries != null && entries.isNotEmpty()) {
            val (months, unlocked) = InsightsCalculator.trendProgress(entries)
            Section("Trends")
            if (!unlocked) AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("$months of 2 months confirmed", style = AppType.b1, color = t.text)
                    AppProgress(months / 2f)
                    Text("Month-to-month comparisons and a month-end forecast unlock after two months of confirmed spending.", style = AppType.cap, color = t.muted)
                }
            } else {
                val cur = graph.currency()
                val same = entries.filter { it.amount.currency.code == cur.code }
                Forecaster.monthEnd(same, today, same.minOfOrNull { it.effectiveDate })?.let { f ->
                    AppCard(Modifier.fillMaxWidth()) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("Month-end forecast", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f)); SourceBadge(ValueSource.ESTIMATE) }
                            Text("≈ ${fmt(f.projected)}", style = AppType.h1, color = t.text)
                            Text(f.formula, style = AppType.mono, color = t.muted)
                            Text(f.note, style = AppType.cap, color = t.muted)
                        }
                    }
                } ?: Text("Not enough recent history for a forecast yet.", style = AppType.cap, color = t.muted)
            }
            Section("Budgets") { AppButton("Manage", { state.push(Screen.Budgets) }, variant = ButtonVariant.GHOST) }
            val month = LocalDateRange(today.withDayOfMonth(1), today.plusDays(1))
            if (budgets.isEmpty()) Text("Set a monthly limit for a category to see how you're doing.", style = AppType.cap, color = t.muted)
            budgets.forEach { b ->
                val cur = currencyOf(b.currency)
                val spent = entries.filter { it.effectiveDate in month && it.amount.currency.code == cur.code && (b.categoryId == "all" || it.categoryId == b.categoryId) }
                    .fold(Money.zero(cur)) { a, e -> a + e.signedAmount }
                val st = Budgets.status(Budget(b.categoryId, b.categoryId.takeIf { it != "all" }, Money(b.amountMinor, cur)), spent)
                Column(Modifier.fillMaxWidth()) {
                    Row { Text(if (b.categoryId == "all") "Everything" else Categories.name(b.categoryId), style = AppType.b1, color = t.text, modifier = Modifier.weight(1f)); Text("${st.percent}%", style = AppType.num, color = if (st.state == BudgetStatus.State.OVER) t.err else t.text) }
                    AppProgress((st.percent / 100f).coerceIn(0f, 1f))
                    Text(st.message, style = AppType.cap, color = if (st.state == BudgetStatus.State.UNDER) t.muted else t.warn)
                }
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}
