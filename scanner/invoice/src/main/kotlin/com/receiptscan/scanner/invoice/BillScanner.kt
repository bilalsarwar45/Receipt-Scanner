package com.receiptscan.scanner.invoice

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AmountText
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.ValueLine
import com.receiptscan.design.skin
import com.receiptscan.money.MoneyFormat
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.Value
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.invoice.engine.InvoiceExtraction
import com.receiptscan.scanner.invoice.engine.InvoiceParser
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.MoneyField
import com.receiptscan.scanner.ui.OcrScanFlow
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.ui.SourcePhoto
import com.receiptscan.scanner.ui.editText
import com.receiptscan.scanner.ui.parseMoney
import com.receiptscan.time.DateOrder
import com.receiptscan.time.DisplayDates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Bill the person confirmed. The host saves it with action [BillScanner.ACTION_SAVE]; a payment is recorded
 * only with [BillScanner.ACTION_PAID] — capturing a bill never creates an expense.
 */
@Serializable
data class BillPayload(
    val supplier: String,
    val invoiceNumber: String?,
    val accountNumber: String?,
    val issueDate: String?,
    val dueDate: String?,
    val amountDueMinor: Long,
    val currency: String,
    val amountAfterDueMinor: Long?,
    val billingPeriod: String?,
    val paidEvidence: String?,
    val needsReview: Boolean,
    val rawText: String,
    val sourceIds: List<String>,
    /** Sources of the key fields, for display after saving. */
    val sources: Map<String, String>,
)

data class BillScan(val extraction: InvoiceExtraction, val image: Bitmap?, val sourceIds: List<String>, val today: LocalDate)

object BillScanner : ScannerModule<BillScan> {
    val ID = CapabilityId("invoice")
    const val ACTION_SAVE = "bill.save"
    const val ACTION_PAID = "bill.paid"
    override val info = ModuleInfo(ID, "Bills", "Utility bills and invoices", ModuleColor.BILLS, AppIcons.bill,
        listOf("Include the amount due and due date", "We'll offer a reminder"))

    var dateOrder: DateOrder = DateOrder.DMY

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<BillScan>) -> Unit, onCancel: () -> Unit) {
        OcrScanFlow(info, input, host, noun = "bill", allowPdf = true, onCancel = onCancel, onFailed = onResult, onDocument = { cap ->
            val today = LocalDate.now(host.clock.withZone(input.zone))
            val ex = InvoiceParser(today, input.currency, dateOrder).parse(cap.document)
            val scan = BillScan(ex, cap.images.firstOrNull(), cap.sourceIds, today)
            onResult(if (ex.issues.isEmpty()) ScanOutcome.Success(scan) else ScanOutcome.NeedsReview(scan, ex.issues))
        })
    }

    @Composable
    override fun Result(result: BillScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        val ex = result.extraction
        val currency = ex.currency.value
        var supplier by remember { mutableStateOf(ex.supplier?.value.orEmpty()) }
        var amount by remember { mutableStateOf(ex.amountDue?.value?.editText().orEmpty()) }
        var due by remember { mutableStateOf(ex.dueDate?.value) }
        var issue by remember { mutableStateOf(ex.issueDate?.value) }
        var number by remember { mutableStateOf(ex.invoiceNumber?.value.orEmpty()) }
        var validate by remember { mutableStateOf(false) }
        val money = parseMoney(amount, currency)
        val daysLeft = due?.let { ChronoUnit.DAYS.between(result.today, it) }
        fun src(v: Value<*>?, same: Boolean) = if (v != null && same) v.source else ValueSource.EDITED
        fun payload(): BillPayload? {
            val m = money ?: return null
            if (supplier.isBlank()) return null
            return BillPayload(
                supplier.trim(), number.ifBlank { null }, ex.accountNumber?.value, issue?.toString(), due?.toString(), m.minor, currency.code,
                ex.amountAfterDue?.value?.minor, ex.billingPeriod?.value, ex.paidEvidence?.value, needsReview = due == null, rawText = ex.rawText,
                sourceIds = result.sourceIds,
                sources = mapOf(
                    "supplier" to src(ex.supplier, ex.supplier?.value == supplier).name,
                    "amount" to src(ex.amountDue, ex.amountDue?.value == money).name,
                    "due" to src(ex.dueDate, ex.dueDate?.value == due).name,
                ),
            )
        }
        ResultScaffold(
            info, "Bill", onBack,
            bottom = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (host.capabilities.canSetReminders) AppButton("Remind", {
                            val d = due ?: return@AppButton
                            val p = payload() ?: run { validate = true; return@AppButton }
                            onAction(ResultAction.Custom(ACTION_SAVE, Json.encodeToString(BillPayload.serializer(), p)))
                            onAction(ResultAction.Remind("${p.supplier} bill due in 2 days", "${MoneyFormat.format(money!!)} due on ${DisplayDates.format(d)}.", d.minusDays(2).atTime(LocalTime.of(10, 0)), "bills", p.invoiceNumber))
                        }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.bell, enabled = due != null && (daysLeft ?: 0) >= 0, fill = true)
                        AppButton("Save bill", {
                            val p = payload()
                            if (p == null) validate = true else onAction(ResultAction.Custom(ACTION_SAVE, Json.encodeToString(BillPayload.serializer(), p)))
                        }, Modifier.weight(1f), fill = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    AppButton("Mark as paid", {
                        val p = payload()
                        if (p == null) validate = true else onAction(ResultAction.Custom(ACTION_PAID, Json.encodeToString(BillPayload.serializer(), p)))
                    }, variant = ButtonVariant.GHOST, fill = true)
                }
            },
        ) {
            ModuleHeader(info, supplier.ifBlank { "Bill" }, ex.billingPeriod?.value?.let { "Billing period $it" })
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Amount due", style = AppType.cap, color = t.muted)
                    AmountText(money?.let { MoneyFormat.format(it) } ?: "—", size = 30)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            daysLeft == null -> StatusBadge(BadgeKind.WARN, "No due date")
                            daysLeft < 0 -> StatusBadge(BadgeKind.ERR, "Overdue by ${-daysLeft} days")
                            daysLeft == 0L -> StatusBadge(BadgeKind.WARN, "Due today")
                            else -> StatusBadge(if (daysLeft <= 3) BadgeKind.WARN else BadgeKind.INFO, "Due in $daysLeft days", icon = AppIcons.clock)
                        }
                        StatusBadge(BadgeKind.NEUTRAL, "Not paid · not counted", icon = AppIcons.info)
                    }
                }
            }
            if (ex.paidEvidence != null) Banner(BadgeKind.INFO, "The bill says “${ex.paidEvidence!!.value}”", "We don't mark bills paid from the photo. Tap Mark as paid if you paid it.")
            ex.issues.forEach { Banner(BadgeKind.WARN, it.message) }
            SourcePhoto(result.image)
            Spacer(Modifier.height(8.dp))
            AppTextField(supplier, { supplier = it.take(60) }, "From", badge = { SourceBadge(src(ex.supplier, ex.supplier?.value == supplier)) },
                state = if (validate && supplier.isBlank()) com.receiptscan.design.FieldState.ERROR else com.receiptscan.design.FieldState.DEFAULT,
                helper = if (validate && supplier.isBlank()) "Add who the bill is from" else null)
            Spacer(Modifier.height(8.dp))
            MoneyField(amount, { amount = it }, "Amount due", currency, forceValidate = validate, badge = { SourceBadge(src(ex.amountDue, ex.amountDue?.value == money)) })
            Spacer(Modifier.height(8.dp))
            DateField("Due date", due, { due = it }, badge = { SourceBadge(src(ex.dueDate, ex.dueDate?.value == due)) })
            Spacer(Modifier.height(8.dp))
            DateField("Issue date", issue, { issue = it }, badge = { SourceBadge(src(ex.issueDate, ex.issueDate?.value == issue)) })
            Spacer(Modifier.height(8.dp))
            AppTextField(number, { number = it.take(40) }, "Bill number", badge = { SourceBadge(src(ex.invoiceNumber, ex.invoiceNumber?.value == number)) })
            ex.accountNumber?.let { ValueLine("Account / consumer number", it.value, it.source) }
            ex.amountAfterDue?.let { ValueLine("Amount after due date", MoneyFormat.format(it.value), it.source) }
            ex.taxes.forEach { (name, v) -> ValueLine(name, MoneyFormat.format(v.value), v.source) }
        }
    }
}
