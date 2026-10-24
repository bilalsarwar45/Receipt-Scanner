package com.receiptscan.scanner.receipt

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.skin
import com.receiptscan.money.Money
import com.receiptscan.money.sum
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.receipt.engine.RowRole
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.MoneyField
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.SourcePhoto
import com.receiptscan.scanner.ui.display
import com.receiptscan.scanner.ui.editText
import com.receiptscan.scanner.ui.parseMoney
import kotlinx.serialization.json.Json
import java.time.LocalDate

private class ItemDraft(
    description: String,
    val quantity: String?,
    val unit: String?,
    val unitPriceMinor: Long?,
    total: String,
    val categoryId: String?,
    val uncertain: Boolean,
    val reasons: List<String>,
    val raw: String?,
) {
    var description by mutableStateOf(description)
    var total by mutableStateOf(total)
    val originalDescription = description
    val originalTotal = total
    val edited get() = description != originalDescription || total != originalTotal
}

/**
 * R-Review: summary banner, editable fields, items with uncertain lines highlighted, and the
 * reconciliation with its formula. Save draft keeps it out of totals; Confirm & save needs merchant, date and total.
 */
@Composable
fun ReceiptReview(result: ReceiptScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
    val t = skin
    val x = result.extraction
    val cur = x.currency.value
    var merchant by remember(result) { mutableStateOf(x.merchant?.value.orEmpty()) }
    var date by remember(result) { mutableStateOf<LocalDate?>(x.date?.value) }
    var totalText by remember(result) { mutableStateOf(x.total?.value?.editText().orEmpty()) }
    var category by remember(result) { mutableStateOf(x.suggestedCategory?.value) }
    var forceValidate by remember { mutableStateOf(false) }
    val items = remember(result) {
        mutableStateListOf<ItemDraft>().apply {
            x.items.forEach { i ->
                add(ItemDraft(i.description.value, i.quantity?.value?.toPlainString(), i.unit, i.unitPrice?.value?.minor, i.lineTotal.value.editText(), i.suggestedCategory, i.uncertain, i.uncertainReasons, i.description.rawText))
            }
        }
    }
    val total = parseMoney(totalText, cur)
    val itemMoney = items.mapNotNull { parseMoney(it.total, cur, allowZero = true) }
    val itemsSum = if (items.isEmpty()) null else itemMoney.sum(cur)
    val r = x.reconciliation
    val base = r.subtotalPrinted ?: itemsSum
    val computed = base?.let { it + r.taxesAdded + r.service - r.discounts + r.rounding }
    val diff = if (computed != null && total != null) total - computed else null
    val matches = diff?.let { kotlin.math.abs(it.minor) <= 1 }
    val formula = buildString {
        val sp = r.subtotalPrinted
        append(if (sp != null) "Subtotal ${sp.display()}" else "Items ${itemsSum?.display() ?: "—"}")
        if (r.taxesAdded.minor != 0L) append(" + tax ${r.taxesAdded.display()}")
        if (r.service.minor != 0L) append(" + service ${r.service.display()}")
        if (r.discounts.minor != 0L) append(" − discount ${r.discounts.display()}")
        if (r.rounding.minor != 0L) append(" ± rounding ${r.rounding.display()}")
        append(" = ${computed?.display() ?: "—"}")
    }
    val merchantErr = forceValidate && merchant.isBlank()
    val dateErr = forceValidate && date == null
    val uncertainCount = items.count { it.uncertain && !it.edited }

    fun payload(queue: Boolean): String {
        val taxes = x.adjustments.filter { it.role == RowRole.TAX && !it.included }.map { it.amount.value }
        val discounts = x.adjustments.filter { it.role == RowRole.DISCOUNT }.map { it.amount.value.abs() }
        val p = ReceiptDraftPayload(
            merchant = merchant.trim().ifEmpty { null }, date = date?.toString(), time = x.time?.value?.toString(), currency = cur.code,
            subtotalMinor = r.subtotalPrinted?.minor, taxMinor = taxes.takeIf { it.isNotEmpty() }?.sum(cur)?.minor,
            discountMinor = discounts.takeIf { it.isNotEmpty() }?.sum(cur)?.minor, totalMinor = total?.minor,
            categoryId = category, reference = x.reference?.value, paymentMethod = x.paymentMethod?.value,
            items = items.mapNotNull { d ->
                val m = parseMoney(d.total, cur, allowZero = true) ?: return@mapNotNull null
                ReceiptItemPayload(d.description.trim(), d.quantity, d.unit, d.unitPriceMinor, m.minor, d.categoryId, d.edited, d.uncertain && !d.edited, d.raw)
            },
            rawText = x.rawText, sourceImageIds = result.sourceIds,
            reviewReasons = x.issues.map { it.message } + (if (matches == false) listOf("Totals don't match") else emptyList()),
            ocrEngine = result.ocrEngine, merchantEdited = merchant != x.merchant?.value.orEmpty(),
            categoryEdited = category != x.suggestedCategory?.value, queueAnother = queue,
        )
        return Json.encodeToString(ReceiptDraftPayload.serializer(), p)
    }

    ResultScaffold(
        ReceiptScanner.info, "Review", onBack,
        bottom = {
            if (host.capabilities.canSave) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Save draft", { onAction(ResultAction.Custom(ReceiptScanner.ACTION_DRAFT, payload(result.queueAnother))) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, fill = true)
                AppButton("Confirm & save", {
                    forceValidate = true
                    if (merchant.isNotBlank() && date != null && total != null && total.minor > 0) onAction(ResultAction.Custom(ReceiptScanner.ACTION_CONFIRM, payload(result.queueAnother)))
                }, Modifier.weight(1f), fill = true)
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Copy", { onAction(ResultAction.Copy(summaryText(merchant, date, items.map { it.description to it.total }, total))) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.copy, fill = true)
                AppButton("Share", { onAction(ResultAction.Share(summaryText(merchant, date, items.map { it.description to it.total }, total), merchant.ifBlank { null })) }, Modifier.weight(1f), icon = AppIcons.share, fill = true)
            }
        },
    ) {
        when {
            x.issues.isEmpty() && uncertainCount == 0 && matches != false -> Banner(BadgeKind.OK, "Looks right", "Check the details, then confirm.")
            else -> Banner(BadgeKind.WARN, "Check ${x.issues.size + uncertainCount} thing${if (x.issues.size + uncertainCount == 1) "" else "s"}", (x.issues.map { it.message } + (if (uncertainCount > 0) listOf("$uncertainCount line${if (uncertainCount == 1) " is" else "s are"} hard to read") else emptyList())).joinToString(" · "))
        }
        SourcePhoto(result.pages.firstOrNull(), label = if (result.pages.size > 1) "View ${result.pages.size} photos" else "View photo")
        AppTextField(merchant, { merchant = it.take(60) }, "Merchant", state = if (merchantErr) FieldState.ERROR else FieldState.DEFAULT, helper = if (merchantErr) "Add a merchant name" else null,
            badge = { SourceBadge(if (merchant != x.merchant?.value) ValueSource.EDITED else ValueSource.FROM_PHOTO) })
        Spacer(Modifier.height(8.dp))
        DateField("Date", date, { date = it }, state = if (dateErr) FieldState.ERROR else if (x.dateAlternatives.isNotEmpty() && date == x.date?.value) FieldState.WARN else FieldState.DEFAULT,
            helper = when { dateErr -> "Add the purchase date"; x.dateAlternatives.isNotEmpty() && date == x.date?.value -> "Could also be ${x.dateAlternatives.joinToString { it.toString() }}"; else -> null },
            badge = { SourceBadge(if (date != x.date?.value) ValueSource.EDITED else ValueSource.FROM_PHOTO) })
        if (x.dateAlternatives.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOfNotNull(x.date?.value) + x.dateAlternatives).distinct().forEach { d -> AppChip(d.toString(), { date = d }, selected = date == d) }
        }
        Spacer(Modifier.height(8.dp))
        MoneyField(totalText, { totalText = it }, "Total", cur, forceValidate = forceValidate,
            warn = if (matches == false && diff != null) "Doesn't match the items by ${diff.abs().display()}" else null,
            badge = { SourceBadge(if (totalText != x.total?.value?.editText()) ValueSource.EDITED else ValueSource.FROM_PHOTO) })
        Spacer(Modifier.height(12.dp))
        Text("Category", style = AppType.lab, color = t.muted)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ordered = ReceiptCategories.all.sortedBy { if (it.first == category) 0 else 1 }
            ordered.forEach { (id, name) -> AppChip(name, { category = id }, selected = id == category) }
        }
        if (category != null && category == x.suggestedCategory?.value) Text("Suggested from the merchant and items", style = AppType.cap, color = t.muted)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Items (${items.size})", style = AppType.h2, color = t.text, modifier = Modifier.weight(1f))
            AppButton("Add item", { items += ItemDraft("", null, null, null, "", null, false, emptyList(), null) }, variant = ButtonVariant.GHOST, icon = AppIcons.plus)
        }
        items.forEachIndexed { idx, d ->
            val warn = d.uncertain && !d.edited
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppTextField(d.description, { d.description = it }, "Item", Modifier.weight(1f), state = if (warn) FieldState.WARN else FieldState.DEFAULT,
                            helper = if (warn) (d.reasons.firstOrNull() ?: "Hard to read · check price") else d.quantity?.let { q -> "Qty $q${d.unit?.let { " $it" } ?: ""}" })
                        Spacer(Modifier.width(8.dp))
                        AppIconButton(AppIcons.close, "Remove item", { items.removeAt(idx) })
                    }
                    MoneyField(d.total, { d.total = it }, "Price", cur, allowZero = true, warn = if (warn) "Hard to read · check price" else null,
                        badge = { SourceBadge(if (d.edited || d.raw == null) ValueSource.EDITED else ValueSource.FROM_PHOTO) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (x.adjustments.isNotEmpty()) {
            Text("Taxes and adjustments", style = AppType.h2, color = t.text)
            x.adjustments.forEach { a ->
                Row(Modifier.fillMaxWidth()) {
                    Text(a.label + (if (a.included) " (included)" else "") + (a.percent?.let { " ${it.toPlainString()}%" } ?: ""), style = AppType.b2, color = t.text, modifier = Modifier.weight(1f))
                    Text(a.amount.value.display(), style = AppType.num, color = t.text)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Total check", style = AppType.lab, color = t.muted, modifier = Modifier.weight(1f)); SourceBadge(ValueSource.CALCULATED)
                }
                Text(formula, style = AppType.mono, color = t.text)
                when (matches) {
                    true -> Text("Matches the printed total ${total?.display()}", style = AppType.b2, color = t.ok)
                    false -> Text("Printed total ${total?.display()} differs by ${diff?.abs()?.display()}. Check items, tax or discounts.", style = AppType.b2, color = t.warn)
                    null -> Text("Add the total and item prices to check the arithmetic.", style = AppType.b2, color = t.muted)
                }
                if (r.taxesIncluded.minor != 0L) Text("Includes ${r.taxesIncluded.display()} tax already in prices", style = AppType.cap, color = t.muted)
                x.tendered?.let { tv -> Text("Paid ${tv.value.display()}${x.change?.let { " · change ${it.value.display()}" } ?: ""} (not counted as spending)", style = AppType.cap, color = t.muted) }
            }
        }
        if (!host.capabilities.canSave) Banner(BadgeKind.INFO, "This app doesn't save receipts", "Copy or share the details instead.")
    }
}

private fun summaryText(merchant: String, date: LocalDate?, items: List<Pair<String, String>>, total: Money?) = buildString {
    appendLine(listOfNotNull(merchant.ifBlank { null }, date?.toString()).joinToString(" · "))
    items.forEach { (d, p) -> appendLine("$d  $p") }
    total?.let { append("Total ${it.display()}") }
}
