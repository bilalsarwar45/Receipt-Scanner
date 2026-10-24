package com.receiptscan.feature.commands

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.receiptscan.commands.ClarifyOption
import com.receiptscan.commands.Command
import com.receiptscan.commands.CommandContext
import com.receiptscan.commands.CommandExecutor
import com.receiptscan.commands.CommandOutcome
import com.receiptscan.commands.CommandParser
import com.receiptscan.commands.InputMode
import com.receiptscan.commands.ParseResult
import com.receiptscan.commands.QuickAddPolicy
import com.receiptscan.commands.RecentAdd
import com.receiptscan.commands.SelectedRecord
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
import com.receiptscan.design.FieldState
import com.receiptscan.design.ListRow
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.skin
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.LedgerValidationException
import com.receiptscan.feature.voice.LevelBars
import com.receiptscan.feature.voice.MicButton
import com.receiptscan.feature.voice.VoiceState
import com.receiptscan.feature.voice.rememberVoiceController
import com.receiptscan.money.MoneyFormat
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.MoneyField
import com.receiptscan.scanner.ui.editText
import com.receiptscan.scanner.ui.parseMoney
import com.receiptscan.time.DisplayDates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Settings the host passes in; all are read from the person's preferences. */
data class CommandSettings(
    val languageTag: String = "en-US",
    val allowSystemSpeech: Boolean = false,
    val quickAdd: Boolean = false,
    val showIntro: Boolean = true,
    val micAskedBefore: Boolean = false,
)

/** Navigation and side effects the sheet asks the host to perform. */
interface CommandCallbacks {
    fun openExpense(id: String) {}
    fun openReceipt(id: String) {}
    fun remind(recordId: String, label: String, date: LocalDate?) {}
    fun onMicAsked() {}
    fun onIntroSeen() {}
    fun onChanged() {}
}

val EXAMPLES = listOf("Bring me my last 30 days expenses", "Add 300 rupees fuel expense for today")

/**
 * The voice and typed command surface (brief 8.1): example chips, push-to-talk, editable transcript,
 * typed alternative, one clarification at a time, an editable add preview and real query results.
 */
@Composable
fun CommandSheet(
    parser: CommandParser,
    executor: CommandExecutor,
    clock: Clock,
    zone: ZoneId,
    settings: CommandSettings,
    callbacks: CommandCallbacks,
    onDismiss: () -> Unit,
    startWithVoice: Boolean = false,
    initialText: String = "",
    selectedRecord: SelectedRecord? = null,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        CommandPanel(parser, executor, clock, zone, settings, callbacks, startWithVoice, initialText, selectedRecord, Modifier.navigationBarsPadding().imePadding())
    }
}

@Composable
fun CommandPanel(
    parser: CommandParser,
    executor: CommandExecutor,
    clock: Clock,
    zone: ZoneId,
    settings: CommandSettings,
    callbacks: CommandCallbacks,
    startWithVoice: Boolean = false,
    initialText: String = "",
    selectedRecord: SelectedRecord? = null,
    modifier: Modifier = Modifier,
) {
    val t = skin
    val scope = rememberCoroutineScope()
    val today = LocalDate.now(clock.withZone(zone))
    var text by rememberSaveable { mutableStateOf(initialText) }
    var mode by rememberSaveable { mutableStateOf(InputMode.TYPED) }
    var fromChip by rememberSaveable { mutableStateOf(false) }
    // One execution key per interpreted command; reused on retry/rotation so a save can't happen twice.
    var executionKey by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var result by remember { mutableStateOf<ParseResult?>(null) }
    var draft by remember { mutableStateOf<Command.AddExpense?>(null) }
    var outcome by remember { mutableStateOf<CommandOutcome?>(null) }
    var recentAdd by remember { mutableStateOf<RecentAdd?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceController(settings.languageTag, settings.allowSystemSpeech, settings.micAskedBefore, callbacks::onMicAsked)

    fun context() = CommandContext(draft = draft, recentAdd = recentAdd, selectedRecord = selectedRecord)

    fun run(command: Command) {
        busy = true; error = null
        scope.launch {
            try {
                val o = executor.execute(command, executionKey, context())
                outcome = o
                when (o) {
                    is CommandOutcome.Added -> {
                        draft = null
                        recentAdd = RecentAdd(o.executionKey, o.expense.id, o.confirmation(today), o.expense.amount)
                        callbacks.onChanged()
                    }
                    is CommandOutcome.DraftUpdated -> { draft = o.draft; outcome = null }
                    is CommandOutcome.Edited, is CommandOutcome.Undone -> { callbacks.onChanged(); if (o is CommandOutcome.Undone) recentAdd = null }
                    is CommandOutcome.ReminderRequested -> callbacks.remind(o.recordId, o.label, o.date)
                    else -> {}
                }
            } catch (e: CancellationException) { throw e } catch (e: LedgerValidationException) {
                error = e.errors.joinToString { it.message }
            } catch (e: Exception) { error = "Couldn't do that: ${e.message}" }
            busy = false
        }
    }

    fun interpret(input: String, m: InputMode, chip: Boolean) {
        text = input; mode = m; fromChip = chip
        outcome = null; error = null
        executionKey = UUID.randomUUID().toString()
        val r = parser.parse(input, m, context())
        result = r
        if (r is ParseResult.Ready) {
            when (val c = r.command) {
                is Command.AddExpense -> {
                    draft = c
                    if (settings.quickAdd && QuickAddPolicy.evaluate(r, input, chip).eligible) run(c)
                }
                else -> run(c) // Queries are read-only; edits and undo are bound to visible context.
            }
        }
    }

    LaunchedEffect(voice.state) {
        val s = voice.state
        if (s is VoiceState.Done) { interpret(s.text, InputMode.VOICE, false); voice.reset() }
    }
    LaunchedEffect(Unit) { if (startWithVoice) voice.start(); if (settings.showIntro) callbacks.onIntroSeen() }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Ask or add", style = AppType.h1, color = t.text)
        if (settings.showIntro) Text("Ask about expenses or add one by voice.", style = AppType.b2, color = t.muted)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { ex -> AppChip(ex, { interpret(ex, InputMode.TYPED, true) }, style = ChipStyle.TONAL) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                if (voice.isListening && voice.partial.isNotEmpty()) voice.partial else text,
                { text = it; mode = InputMode.TYPED; fromChip = false },
                if (mode == InputMode.VOICE) "We heard (edit if needed)" else "Type a command",
                Modifier.weight(1f), placeholder = "e.g. How much on groceries last month?",
                imeAction = ImeAction.Done, onDone = { if (text.isNotBlank()) interpret(text, mode, false) },
                trailing = { AppIconButton(AppIcons.check, "Run command", { if (text.isNotBlank()) interpret(text, mode, false) }, enabled = text.isNotBlank()) },
            )
            Spacer(Modifier.padding(start = 12.dp))
            MicButton(voice)
        }
        VoiceStatus(voice.state, voice.providerLabel, voice.onDevice, onRetry = { voice.start() }, onDownload = { voice.download() })
        if (voice.isListening) LevelBars(voice, Modifier.padding(vertical = 6.dp))
        error?.let { Banner(BadgeKind.ERR, "Not saved", it) }
        Spacer(Modifier.height(8.dp))

        when (val r = result) {
            null -> {}
            is ParseResult.Unsupported -> Banner(BadgeKind.INFO, "I can't do that yet", r.message + " Try: “Show my fuel expenses this month” or “Add 1,500 rupees groceries for yesterday”.")
            is ParseResult.NeedsClarification -> Clarify(r) { opt -> result = parser.resolveClarification(r, opt.resolved, mode).also { nr -> if (nr is ParseResult.Ready) (nr.command as? Command.AddExpense)?.let { draft = it } } }
            is ParseResult.Ready -> {}
        }
        val d = draft
        if (d != null && outcome !is CommandOutcome.Added) AddPreview(d, today, busy, onChange = { draft = it }, onAdd = { run(it) }, onCancel = { draft = null; result = null })
        when (val o = outcome) {
            null -> {}
            is CommandOutcome.Added -> AddedCard(o, today, onUndo = { run(Command.UndoRecent(o.executionKey, o.confirmation(today))) }, onOpen = { callbacks.openExpense(o.expense.id) })
            is CommandOutcome.QueryAnswer -> QueryResults(o, today) { callbacks.openExpense(it.id) }
            is CommandOutcome.ReceiptsFound -> ReceiptHits(o) { callbacks.openReceipt(it) }
            is CommandOutcome.Edited -> Banner(BadgeKind.OK, "Changed", "${MoneyFormat.formatShort(o.expense.amount)} · ${Categories.name(o.expense.categoryId)} · ${DisplayDates.relative(o.expense.effectiveDate, today)}")
            is CommandOutcome.Undone -> Banner(BadgeKind.OK, "Undone", "Removed ${MoneyFormat.formatShort(o.expense.amount)} · ${Categories.name(o.expense.categoryId)} from your totals.")
            is CommandOutcome.Refused -> Banner(BadgeKind.WARN, "Nothing changed", o.message)
            is CommandOutcome.ReminderRequested -> Banner(BadgeKind.INFO, "Set the reminder", "Pick a date for ${o.label}.")
            is CommandOutcome.DraftUpdated -> {}
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun VoiceStatus(state: VoiceState, provider: String?, onDevice: Boolean, onRetry: () -> Unit, onDownload: () -> Unit) {
    val t = skin
    when (state) {
        VoiceState.Idle, is VoiceState.Done -> {}
        VoiceState.Checking -> Text("Getting the microphone ready…", style = AppType.cap, color = t.muted)
        VoiceState.Listening -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Listening… tap the mic when you're done", style = AppType.cap, color = t.text, modifier = Modifier.weight(1f))
            StatusBadge(if (onDevice) BadgeKind.OK else BadgeKind.WARN, if (onDevice) "On device" else "Uses internet")
        }
        VoiceState.Transcribing -> Text("Microphone off · finishing the transcript…", style = AppType.cap, color = t.muted)
        is VoiceState.Error -> Banner(BadgeKind.WARN, state.error.message, provider, "Try again", onRetry)
        is VoiceState.Unavailable -> Banner(BadgeKind.INFO, "Voice isn't available", state.reason + " Typing works the same way.", if (state.canDownload) "Download" else null, if (state.canDownload) onDownload else null)
        VoiceState.NeedsPermission -> Banner(BadgeKind.INFO, "Microphone is off", "Allow microphone access in Settings to use voice, or type instead.")
    }
}

@Composable
private fun Clarify(r: ParseResult.NeedsClarification, onPick: (ClarifyOption) -> Unit) {
    val t = skin
    AppCard(Modifier.fillMaxWidth()) {
        Column {
            Text(r.question, style = AppType.h2, color = t.text)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.options.forEach { o -> AppChip(o.label, { onPick(o) }) }
            }
        }
    }
}

@Composable
private fun AddPreview(d: Command.AddExpense, today: LocalDate, busy: Boolean, onChange: (Command.AddExpense) -> Unit, onAdd: (Command.AddExpense) -> Unit, onCancel: () -> Unit) {
    val t = skin
    var amountText by remember(d.amount) { mutableStateOf(d.amount.editText()) }
    var note by remember(d) { mutableStateOf(d.note.orEmpty()) }
    var force by remember { mutableStateOf(false) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("New expense", style = AppType.h2, color = t.text, modifier = Modifier.weight(1f))
                StatusBadge(BadgeKind.NEUTRAL, if (d.source == ExpenseSource.VOICE) "Voice" else "Typed")
            }
            MoneyField(amountText, { amountText = it }, "Amount", d.amount.currency, forceValidate = force,
                warn = if (d.currencyAssumed) "Currency wasn't said; using ${d.amount.currency.code}" else null)
            Text("Category", style = AppType.lab, color = t.muted)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Categories.builtIn.sortedBy { if (it.id == d.categoryId) 0 else 1 }.forEach { c -> AppChip(c.name, { onChange(d.copy(categoryId = c.id)) }, selected = c.id == d.categoryId) }
            }
            DateField("Date", d.date, { onChange(d.copy(date = it, dateAssumed = false)) }, state = if (d.dateAssumed) FieldState.WARN else FieldState.DEFAULT,
                helper = if (d.dateAssumed) "No date was said; using today" else DisplayDates.relative(d.date, today))
            AppTextField(note, { note = it.take(200) }, "Note (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Cancel", onCancel, Modifier.weight(1f), variant = ButtonVariant.GHOST, fill = true)
                AppButton("Add expense", {
                    force = true
                    val m = parseMoney(amountText, d.amount.currency) ?: return@AppButton
                    onAdd(d.copy(amount = m, note = note.trim().ifEmpty { null }))
                }, Modifier.weight(1f), enabled = !busy, fill = true)
            }
        }
    }
}

@Composable
private fun AddedCard(o: CommandOutcome.Added, today: LocalDate, onUndo: () -> Unit, onOpen: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.checkCircle, null, tint = skin.ok)
            Spacer(Modifier.padding(start = 8.dp))
            Text(if (o.alreadyExisted) "Already added · ${o.confirmation(today).removePrefix("Added ")}" else o.confirmation(today), style = AppType.b1, color = skin.text, modifier = Modifier.weight(1f))
            AppButton("Undo", onUndo, variant = ButtonVariant.GHOST)
        }
    }
}

@Composable
private fun QueryResults(o: CommandOutcome.QueryAnswer, today: LocalDate, onOpen: (ExpenseEntry) -> Unit) {
    val t = skin
    var sourceFilter by remember(o) { mutableStateOf<ExpenseSource?>(null) }
    val entries = o.result.entries.filter { sourceFilter == null || it.source == sourceFilter }
    Text("${o.label.replaceFirstChar { it.uppercase() }} · ${DisplayDates.formatRange(o.range)}", style = AppType.h2, color = t.text)
    if (o.result.isEmpty) {
        Banner(BadgeKind.INFO, "No expenses recorded", "Nothing matches ${o.label}. ${o.scopeNote}")
        return
    }
    AppCard(Modifier.fillMaxWidth()) {
        Column {
            Text("${o.result.count} record${if (o.result.count == 1) "" else "s"}", style = AppType.lab, color = t.muted)
            o.result.totals.forEach { ct -> Text(MoneyFormat.format(ct.total), style = AppType.d1, color = t.text) }
            o.result.byCategory.forEach { (_, cats) ->
                cats.take(5).forEach { c ->
                    Row(Modifier.fillMaxWidth()) { Text(Categories.name(c.categoryId), style = AppType.b2, color = t.text, modifier = Modifier.weight(1f)); Text(MoneyFormat.formatShort(c.total), style = AppType.num, color = t.text) }
                }
            }
            Text(o.scopeNote, style = AppType.cap, color = t.muted)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppChip("All", { sourceFilter = null }, selected = sourceFilter == null)
        o.result.bySource.forEach { (s, n) -> AppChip("${s.label} · $n", { sourceFilter = s }, selected = sourceFilter == s) }
    }
    entries.take(100).forEach { e ->
        ListRow(e.merchant ?: Categories.name(e.categoryId), meta = "${Categories.name(e.categoryId)} · ${DisplayDates.relative(e.effectiveDate, today)} · ${e.source.label}",
            trailing = { Text(MoneyFormat.formatShort(e.signedAmount), style = AppType.num, color = t.text) }, onClick = { onOpen(e) })
    }
    if (entries.size > 100) Text("Showing 100 of ${entries.size}. Open Insights for the full list.", style = AppType.cap, color = t.muted)
}

@Composable
private fun ReceiptHits(o: CommandOutcome.ReceiptsFound, onOpen: (String) -> Unit) {
    val t = skin
    Text("Receipts matching “${o.text}”${o.label?.let { " · $it" } ?: ""}", style = AppType.h2, color = t.text)
    if (o.hits.isEmpty()) { Banner(BadgeKind.INFO, "No receipts found", "Try a different word, or a wider date range."); return }
    o.hits.forEach { h ->
        ListRow(h.merchant ?: "Receipt", meta = listOfNotNull(h.date?.let { DisplayDates.format(it) }, h.snippet.take(60)).joinToString(" · "),
            trailing = { h.total?.let { Text(MoneyFormat.formatShort(it), style = AppType.num, color = t.text) } }, onClick = { onOpen(h.receiptId) })
    }
}
