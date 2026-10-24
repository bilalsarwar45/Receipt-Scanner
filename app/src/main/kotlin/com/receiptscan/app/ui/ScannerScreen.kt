package com.receiptscan.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppScannerHost
import com.receiptscan.app.AppState
import com.receiptscan.app.Modules
import com.receiptscan.app.ReminderRequest
import com.receiptscan.app.Screen
import com.receiptscan.app.Tab
import com.receiptscan.app.newTempStorage
import com.receiptscan.app.toInvoice
import com.receiptscan.app.toReceipt
import com.receiptscan.data.local.FoodLogRow
import com.receiptscan.expenses.AmountChoice
import com.receiptscan.expenses.Categories
import com.receiptscan.expenses.ConfirmResult
import com.receiptscan.expenses.ExpenseEntry
import com.receiptscan.expenses.ExpenseSource
import com.receiptscan.expenses.LedgerValidationException
import com.receiptscan.expenses.Receipt
import com.receiptscan.money.MoneyFormat
import com.receiptscan.ocr.model.TextNormalizer
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.food.FoodPayload
import com.receiptscan.scanner.food.FoodScanner
import com.receiptscan.scanner.invoice.BillPayload
import com.receiptscan.scanner.invoice.BillScanner
import com.receiptscan.scanner.product.ProductScanner
import com.receiptscan.scanner.receipt.ReceiptDraftPayload
import com.receiptscan.scanner.receipt.ReceiptScanner
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerFlow
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.time.DisplayDates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

/** Hosts one embeddable scanner and carries out the actions its result screen asks for. */
@Composable
fun ScannerScreen(graph: AppGraph, state: AppState, id: String, source: ScanSource) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val temp = remember { newTempStorage(graph) }
    val host = remember { AppScannerHost(graph, temp) }
    DisposableEffect(Unit) { onDispose { temp.clear() } }
    var duplicate by remember { mutableStateOf<Pair<Receipt, List<ExpenseEntry>>?>(null) }
    val settings = graph.current
    val input = ScanInput(source = source, currency = graph.currency(), zone = graph.zone(), showEducation = settings.showTips && id !in settings.dismissedTips)
    val module = Modules.byId(id)
    if (module == null) { state.pop(); return }

    fun close() { state.pop() }
    val onAction: (ResultAction) -> Unit = { a -> scope.launch { handle(graph, state, context, a, id, onDuplicate = { duplicate = it }) } }

    @Suppress("UNCHECKED_CAST")
    ScannerFlow(module as ScannerModule<Any>, input, host, onAction, ::close)

    duplicate?.let { (receipt, matches) ->
        DuplicateDialog(receipt, matches,
            onViewExisting = { duplicate = null; scope.launch { graph.ledger.saveReceiptDraft(receipt); state.replace(Screen.ExpenseDetail(matches.first().id)) } },
            onKeepBoth = { duplicate = null; scope.launch { confirm(graph, state, receipt, null) } },
            onAttach = { e -> duplicate = null; scope.launch { confirm(graph, state, receipt, e.id) } },
            onDiscard = { duplicate = null; state.pop() },
        )
    }
}

private suspend fun handle(graph: AppGraph, state: AppState, context: Context, a: ResultAction, moduleId: String, onDuplicate: (Pair<Receipt, List<ExpenseEntry>>) -> Unit) {
    when (a) {
        is ResultAction.Save -> {
            val e = a.envelope
            val row = graph.records.saveScan(e.capabilityId.value, e.payloadVersion, e.title, e.summary, e.payloadJson, "${e.title}\n${e.summary}\n${e.payloadJson}", e.sourceIds)
            // The Warranty tool also opens the warranty form; keep that screen and save the scan quietly.
            if (moduleId == ProductScanner.ID.value) return
            state.pop()
            val r = state.snackbar.showSnackbar("Saved to Library", actionLabel = "View")
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) state.push(Screen.ScanDetail(row.id))
        }
        is ResultAction.Copy -> { copy(context, a.text); state.toast("Copied") }
        is ResultAction.Share -> share(context, a.text, a.subject)
        is ResultAction.OpenLink -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { state.toast("No app can open this link") }
        is ResultAction.Remind -> state.reminderRequest = ReminderRequest(a.title, a.body, a.at, a.channel, a.recordHint)
        is ResultAction.HandOff -> state.replace(Screen.Scanner(a.target.value, a.source))
        ResultAction.ScanAgain -> {}
        ResultAction.Done -> state.pop()
        is ResultAction.Custom -> when (a.id) {
            ReceiptScanner.ACTION_CONFIRM, ReceiptScanner.ACTION_DRAFT -> {
                val p = json.decodeFromString(ReceiptDraftPayload.serializer(), a.payload)
                learn(graph, p)
                val receipt = p.toReceipt(UUID.randomUUID().toString(), graph.clock.instant())
                if (a.id == ReceiptScanner.ACTION_DRAFT) {
                    graph.ledger.saveReceiptDraft(receipt)
                    if (p.queueAnother) state.replace(Screen.Scanner(ReceiptScanner.ID.value)) else state.pop()
                    state.toast("Saved as Needs review · not counted yet")
                } else {
                    val dups = graph.ledger.possibleDuplicates(receipt)
                    if (dups.isEmpty()) confirm(graph, state, receipt, null, p.queueAnother) else onDuplicate(receipt to dups)
                }
            }
            BillScanner.ACTION_SAVE, BillScanner.ACTION_PAID -> {
                val p = json.decodeFromString(BillPayload.serializer(), a.payload)
                val inv = graph.ledger.saveInvoice(p.toInvoice(UUID.randomUUID().toString(), graph.clock.instant()))
                if (a.id == BillScanner.ACTION_PAID) {
                    graph.ledger.recordPayment(inv.id, inv.amountDue, LocalDate.now(graph.clock.withZone(graph.zone())), "bill-paid-${inv.id}")
                    state.replace(Screen.BillDetail(inv.id)); state.toast("Bill saved and payment recorded")
                } else {
                    state.replace(Screen.BillDetail(inv.id)); state.toast("Bill saved · not counted until you record a payment")
                }
            }
            ProductScanner.ACTION_WARRANTY -> state.replace(Screen.Warranty(prefillJson = a.payload))
            FoodScanner.ACTION_LOG -> {
                val p = json.decodeFromString(FoodPayload.serializer(), a.payload)
                val today = LocalDate.now(graph.clock.withZone(graph.zone())).toEpochDay()
                p.items.forEach { i -> graph.records.logFood(FoodLogRow(UUID.randomUUID().toString(), today, i.name, i.grams, i.kcal ?: 0.0, i.proteinG, i.carbsG, i.fatG, graph.clock.millis())) }
                state.pop(); state.toast("Added to today's food log (estimates)")
            }
            else -> state.toast("This action isn't available here")
        }
    }
}

/** Learns merchant names and categories the person corrected, for future scans. Never rewrites history. */
private suspend fun learn(graph: AppGraph, p: ReceiptDraftPayload) {
    val merchant = p.merchant ?: return
    if (p.merchantEdited && p.merchantRaw != null) graph.records.learnMerchant(TextNormalizer.key(p.merchantRaw!!), merchant, if (p.categoryEdited) p.categoryId else null)
    if (p.categoryEdited && p.categoryId != null) graph.records.learnMerchant(TextNormalizer.key(merchant), null, p.categoryId)
    graph.refreshLearnedRules()
}

suspend fun confirm(graph: AppGraph, state: AppState, receipt: Receipt, attachTo: String?, queueAnother: Boolean = false) {
    try {
        val r = when (val first = graph.ledger.confirmReceipt(receipt, attachTo)) {
            is ConfirmResult.NeedsAmountChoice -> graph.ledger.confirmReceipt(receipt, attachTo, AmountChoice.USE_RECEIPT_TOTAL)
            else -> first
        }
        if (r is ConfirmResult.Confirmed) {
            val summary = "${MoneyFormat.format(r.expense.amount)} · ${r.receipt.merchant} · ${Categories.name(r.expense.categoryId)}"
            state.replace(Screen.ReceiptSaved(r.receipt.id, summary, queueAnother))
        }
    } catch (e: LedgerValidationException) {
        state.toast(e.errors.joinToString { it.message })
    }
}

@Composable
private fun DuplicateDialog(receipt: Receipt, matches: List<ExpenseEntry>, onViewExisting: () -> Unit, onKeepBoth: () -> Unit, onAttach: (ExpenseEntry) -> Unit, onDiscard: () -> Unit) {
    val m = matches.first()
    val attachable = matches.firstOrNull { it.source != ExpenseSource.RECEIPT }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Possible duplicate") },
        text = {
            Text(
                "You already have ${MoneyFormat.format(m.amount)}${m.merchant?.let { " at $it" } ?: ""} on ${DisplayDates.format(m.effectiveDate)} (${m.source.label}). " +
                    if (attachable != null) "Attach this receipt to it so it's counted once." else "Saving both would count this purchase twice.",
            )
        },
        confirmButton = {
            if (attachable != null) TextButton({ onAttach(attachable) }) { Text("Attach to it") }
            else TextButton(onViewExisting) { Text("View existing") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onDiscard) { Text("Discard this scan") }
                TextButton(onKeepBoth) { Text("Keep both") }
            }
        },
    )
}

fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Receipt & Scan", text))
}

fun share(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) } }
    context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

fun CoroutineScope.launchSafely(state: AppState, block: suspend () -> Unit) = launch {
    try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { state.toast(e.message ?: "Something went wrong") }
}
