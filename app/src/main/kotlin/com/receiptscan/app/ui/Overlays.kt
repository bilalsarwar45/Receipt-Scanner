package com.receiptscan.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppState
import com.receiptscan.app.ExportRequest
import com.receiptscan.app.ReminderChannel
import com.receiptscan.app.ReminderRequest
import com.receiptscan.app.Screen
import com.receiptscan.actions.CsvExport
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.skin
import com.receiptscan.expenses.ExpenseFilter
import com.receiptscan.feature.commands.CommandCallbacks
import com.receiptscan.feature.commands.CommandSettings
import com.receiptscan.feature.commands.CommandSheet
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.TimeDialog
import com.receiptscan.time.DisplayDates
import com.receiptscan.time.LocalDateRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Voice and typed commands (brief 8.1), wired to the app's ledger and navigation. */
@Composable
fun CommandHost(graph: AppGraph, state: AppState) {
    val settings by graph.settings.collectAsState()
    val callbacks = remember {
        object : CommandCallbacks {
            override fun openExpense(id: String) { state.commandOpen = false; state.push(Screen.ExpenseDetail(id)) }
            override fun openReceipt(id: String) { state.commandOpen = false; state.push(Screen.ReceiptDetail(id)) }
            override fun remind(recordId: String, label: String, date: LocalDate?) {
                state.commandOpen = false
                val at = (date ?: LocalDate.now(graph.clock.withZone(graph.zone())).plusDays(1)).atTime(9, 0)
                state.reminderRequest = ReminderRequest(label, "Reminder from Receipt & Scan", at, ReminderChannel.RECEIPT.id, recordId)
            }
            override fun onMicAsked() { graph.scope.launch { graph.prefs.setMicAsked() } }
            override fun onIntroSeen() { graph.scope.launch { graph.prefs.dismissTip("commands") } }
        }
    }
    CommandSheet(
        parser = graph.parser,
        executor = graph.executor,
        clock = graph.clock,
        zone = graph.zone(),
        settings = CommandSettings(
            languageTag = Locale.getDefault().toLanguageTag(),
            allowSystemSpeech = settings.allowSystemSpeech,
            quickAdd = settings.voiceQuickAdd,
            showIntro = settings.showTips && "commands" !in settings.dismissedTips,
            micAskedBefore = settings.micAsked,
        ),
        callbacks = callbacks,
        onDismiss = { state.commandOpen = false },
        startWithVoice = state.commandVoice,
        initialText = state.commandText,
    )
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

/** A-Reminder: title, day and time. Notification permission is asked here, when it is needed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheet(graph: AppGraph, state: AppState, req: ReminderRequest) {
    val t = skin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.collectAsState()
    var title by remember { mutableStateOf(req.title) }
    var date by remember { mutableStateOf(req.defaultAt.toLocalDate()) }
    var time by remember { mutableStateOf(req.defaultAt.toLocalTime()) }
    var pickTime by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(graph.reminders.notificationsAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok
        scope.launch { graph.prefs.setNotificationsAsked() }
    }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))
    val past = LocalDateTime.of(date, time).isBefore(LocalDateTime.now(graph.clock.withZone(graph.zone())))

    fun save() {
        scope.launch {
            graph.reminders.schedule(title.trim(), req.body, LocalDateTime.of(date, time), graph.zone(), req.channel, req.recordId)
            state.reminderRequest = null
            state.toast("Reminder set for ${DisplayDates.relative(date, today)}, ${time.format(timeFmt)}" + if (!allowed) " · notifications are off" else "")
        }
    }

    ModalBottomSheet(onDismissRequest = { state.reminderRequest = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Remind me", style = AppType.h1, color = t.text)
            Text(ReminderChannel.of(req.channel).title, style = AppType.cap, color = t.muted)
            AppTextField(title, { title = it }, "What to remind you about", state = if (title.isBlank()) com.receiptscan.design.FieldState.ERROR else com.receiptscan.design.FieldState.DEFAULT, helper = if (title.isBlank()) "Add a short title" else null)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip("Tomorrow", { date = today.plusDays(1) }, selected = date == today.plusDays(1))
                AppChip("In 3 days", { date = today.plusDays(3) }, selected = date == today.plusDays(3))
                AppChip("Next week", { date = today.plusWeeks(1) }, selected = date == today.plusWeeks(1))
                if (req.defaultAt.toLocalDate() !in listOf(today.plusDays(1), today.plusDays(3), today.plusWeeks(1)))
                    AppChip("Suggested", { date = req.defaultAt.toLocalDate() }, selected = date == req.defaultAt.toLocalDate())
            }
            DateField("Day", date, { date = it })
            AppButton("Time · ${time.format(timeFmt)}", { pickTime = true }, variant = ButtonVariant.OUTLINE, icon = AppIcons.clock, fill = true)
            if (past) Banner(BadgeKind.WARN, "That time has passed", "Pick a later day or time.")
            if (!allowed) {
                if (Build.VERSION.SDK_INT >= 33 && !settings.notificationsAsked) {
                    Banner(BadgeKind.INFO, "Allow notifications?", "So the reminder can appear on time. You can still save it without.", "Allow") {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    Banner(BadgeKind.WARN, "Notifications are off", "The reminder is saved in the app but won't pop up.", "Open settings") {
                        context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            AppButton("Set reminder", ::save, fill = true, enabled = title.isNotBlank() && !past, disabledReason = if (past) "Pick a time in the future" else "Add a title", icon = AppIcons.bell)
            AppButton("Cancel", { state.reminderRequest = null }, variant = ButtonVariant.GHOST, fill = true)
        }
    }
    if (pickTime) TimeDialog(time, { pickTime = false }) { time = it; pickTime = false }
}

private enum class ExportRange(val label: String) { MONTH("This month"), DAYS30("Last 30 days"), YEAR("This year"), ALL("Everything") }

/** A-Export: CSV of one receipt's items or of confirmed expenses, shared through the system sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(graph: AppGraph, state: AppState, req: ExportRequest) {
    val t = skin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(ExportRange.MONTH) }
    var busy by remember { mutableStateOf(false) }
    val today = LocalDate.now(graph.clock.withZone(graph.zone()))

    fun shareCsv(name: String, csv: String) {
        scope.launch {
            busy = true
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    File(dir, name).apply { writeText(csv) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(Intent.createChooser(send, "Export CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                state.exportRequest = null
            } catch (e: Exception) {
                state.toast("Couldn't export: ${e.message}")
            } finally { busy = false }
        }
    }

    ModalBottomSheet(onDismissRequest = { state.exportRequest = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(req.title, style = AppType.h1, color = t.text)
            if (req.receiptId != null) {
                Text("This receipt's items, quantities and line totals as a spreadsheet file.", style = AppType.b2, color = t.muted)
                AppButton("Export receipt items (CSV)", {
                    scope.launch {
                        val r = graph.ledgerStore.receipt(req.receiptId) ?: return@launch
                        shareCsv("receipt-${(r.merchant ?: "receipt").replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()}-${r.purchaseDate ?: today}.csv", CsvExport.receiptItems(r))
                    }
                }, fill = true, icon = AppIcons.download, enabled = !busy)
                Text("Or export expenses", style = AppType.lab, color = t.muted)
            } else {
                Text("Confirmed expenses only: receipts, voice, manual entries and bill payments, each counted once.", style = AppType.b2, color = t.muted)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportRange.entries.forEach { r -> AppChip(r.label, { range = r }, selected = range == r) }
            }
            AppButton("Export expenses (CSV)", {
                scope.launch {
                    val dr = when (range) {
                        ExportRange.MONTH -> LocalDateRange(today.withDayOfMonth(1), today.plusDays(1))
                        ExportRange.DAYS30 -> LocalDateRange(today.minusDays(29), today.plusDays(1))
                        ExportRange.YEAR -> LocalDateRange(today.withDayOfYear(1), today.plusDays(1))
                        ExportRange.ALL -> null
                    }
                    val entries = graph.ledgerStore.expenses(ExpenseFilter(range = dr)).sortedBy { it.effectiveDate }
                    if (entries.isEmpty()) { state.toast("No confirmed expenses in that period"); return@launch }
                    shareCsv("expenses-${range.name.lowercase()}-$today.csv", CsvExport.expenses(entries))
                }
            }, fill = true, variant = if (req.receiptId != null) ButtonVariant.TONAL else ButtonVariant.PRIMARY, icon = AppIcons.download, enabled = !busy)
            AppButton("Cancel", { state.exportRequest = null }, variant = ButtonVariant.GHOST, fill = true)
        }
    }
}
