package com.receiptscan.scanner.expiry

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.FieldState
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.expiry.engine.DateKind
import com.receiptscan.scanner.expiry.engine.ExpiryParser
import com.receiptscan.scanner.expiry.engine.ExpiryResult
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.OcrScanFlow
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.ui.SourcePhoto
import com.receiptscan.time.DateOrder
import com.receiptscan.time.DisplayDates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

@Serializable
data class ExpiryPayload(val item: String, val kind: String, val date: String, val dateSource: String, val raw: String?)

data class ExpiryScan(val parsed: ExpiryResult, val image: Bitmap?, val sourceIds: List<String>, val today: LocalDate)

/** Expiry dates (S-Expiry). Ambiguous dates are offered as a choice; manufacturing dates never become expiry dates silently. */
object ExpiryScanner : ScannerModule<ExpiryScan> {
    val ID = CapabilityId("expiry")
    override val info = ModuleInfo(ID, "Expiry", "Expiry and best-before dates", ModuleColor.EXPIRY, AppIcons.expiry,
        listOf("Get close to the printed date", "If it can be read two ways, we'll ask"))

    var dateOrder: DateOrder = DateOrder.DMY

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<ExpiryScan>) -> Unit, onCancel: () -> Unit) {
        OcrScanFlow(info, input, host, noun = "date", onCancel = onCancel, onFailed = onResult, onDocument = { cap ->
            val today = LocalDate.now(host.clock.withZone(input.zone))
            val parsed = ExpiryParser.parse(cap.document, today, dateOrder)
            val scan = ExpiryScan(parsed, cap.images.firstOrNull(), cap.sourceIds, today)
            onResult(if (parsed.needsChoice) ScanOutcome.NeedsReview(scan, emptyList()) else ScanOutcome.Success(scan))
        })
    }

    @Composable
    override fun Result(result: ExpiryScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        val p = result.parsed
        val initial = p.primary ?: p.candidates.firstOrNull { it.kind in setOf(DateKind.EXPIRY, DateKind.BEST_BEFORE, DateKind.USE_BY) }
        var item by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf(initial?.kind?.takeIf { it != DateKind.UNKNOWN } ?: DateKind.BEST_BEFORE) }
        var date by remember { mutableStateOf(if (initial != null && initial.alternatives.isEmpty()) initial.date else null) }
        var source by remember { mutableStateOf(ValueSource.FROM_PHOTO) }
        val choices = initial?.let { listOf(it.date) + it.alternatives }?.distinct().orEmpty()
        val daysLeft = date?.let { ChronoUnit.DAYS.between(result.today, it) }
        val isExpiryKind = kind in setOf(DateKind.EXPIRY, DateKind.BEST_BEFORE, DateKind.USE_BY)
        ResultScaffold(
            info, "Expiry date", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (host.capabilities.canSetReminders) AppButton("Remind me", {
                        val d = date ?: return@AppButton
                        val at = d.minusDays(1).atTime(LocalTime.of(9, 0))
                        val name = item.ifBlank { "Item" }
                        onAction(ResultAction.Remind("$name is ${kind.label.lowercase()} tomorrow", "${kind.label} ${DisplayDates.format(d)}", at, "expiry"))
                    }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.bell, enabled = date != null && isExpiryKind && (daysLeft ?: 0) >= 1, fill = true)
                    if (host.capabilities.canSave) AppButton("Save", {
                        val d = date ?: return@AppButton
                        onAction(ResultAction.Save(ScanEnvelope(ID, 1, "${item.ifBlank { "Item" }} · ${kind.label} ${DisplayDates.format(d)}", p.message ?: kind.label,
                            Json.encodeToString(ExpiryPayload.serializer(), ExpiryPayload(item, kind.name, d.toString(), source.name, initial?.raw)), result.sourceIds)))
                    }, Modifier.weight(1f), enabled = date != null, disabledReason = if (date == null) "Pick the date first" else null, fill = true)
                }
            },
        ) {
            ModuleHeader(info, date?.let { "${kind.label} ${DisplayDates.format(it)}" } ?: "Check the date", daysLeft?.let {
                when { it < 0 -> "${-it} days ago"; it == 0L -> "Today"; it == 1L -> "Tomorrow"; else -> "In $it days" }
            })
            if (daysLeft != null && isExpiryKind) {
                Row { StatusBadge(if (daysLeft < 0) BadgeKind.ERR else if (daysLeft <= 2) BadgeKind.WARN else BadgeKind.OK, if (daysLeft < 0) "Past this date" else if (daysLeft <= 2) "Soon" else "OK") }
            }
            SourcePhoto(result.image)
            p.message?.let { Banner(if (p.candidates.isEmpty()) BadgeKind.ERR else BadgeKind.WARN, it) }
            if (choices.size > 1) {
                Text("Which date is it?", style = AppType.lab, color = t.text)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c -> AppChip(DisplayDates.format(c), { date = c; source = ValueSource.EDITED }, selected = date == c) }
                }
                Text("Printed as “${initial?.raw}”", style = AppType.cap, color = t.muted)
            }
            Spacer(Modifier.height(8.dp))
            Text("Kind of date", style = AppType.lab, color = t.text)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(DateKind.EXPIRY, DateKind.BEST_BEFORE, DateKind.USE_BY, DateKind.MANUFACTURED, DateKind.PACKED).forEach { k ->
                    AppChip(k.label, { kind = k }, selected = kind == k)
                }
            }
            if (!isExpiryKind) Banner(BadgeKind.INFO, "This isn't an expiry date", "Manufacturing and packing dates don't tell you when food expires. Reminders are only offered for expiry dates.")
            Spacer(Modifier.height(8.dp))
            DateField("Date", date, { date = it; source = ValueSource.EDITED }, badge = { SourceBadge(source) },
                state = if (date != null && date!!.isBefore(result.today.minusYears(5))) FieldState.WARN else FieldState.DEFAULT)
            Spacer(Modifier.height(8.dp))
            AppTextField(item, { item = it.take(60) }, "Item (optional)", placeholder = "Yogurt 500 g")
            if (p.candidates.size > 1) {
                Spacer(Modifier.height(8.dp))
                Text("All dates found: " + p.candidates.joinToString { "${it.kind.label} ${DisplayDates.format(it.date)}" }, style = AppType.cap, color = t.muted)
            }
            if (!host.capabilities.canSetReminders) Text("Reminders aren't available in this app.", style = AppType.cap, color = t.muted)
        }
    }
}
