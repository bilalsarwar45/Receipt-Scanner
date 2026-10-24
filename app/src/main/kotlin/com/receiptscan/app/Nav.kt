package com.receiptscan.app

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.receiptscan.scanner.contracts.ScanSource

enum class Tab(val label: String) { HOME("Home"), LIBRARY("Library"), TOOLS("Tools"), INSIGHTS("Insights") }

/** Destinations. Names follow the handover screen IDs where one exists. */
sealed interface Screen {
    data object Tabs : Screen
    data class Scanner(val id: String, val source: ScanSource = ScanSource.Camera) : Screen
    data object Pdf : Screen
    data class ReceiptSaved(val receiptId: String, val summary: String, val queueAnother: Boolean) : Screen
    data class ReceiptDetail(val id: String) : Screen
    data class ReceiptEdit(val id: String) : Screen
    data class ExpenseDetail(val id: String) : Screen
    data class ExpenseForm(val id: String? = null, val attachReceiptId: String? = null) : Screen
    data class BillDetail(val id: String) : Screen
    data class ScanDetail(val id: String) : Screen
    data class DocumentDetail(val id: String) : Screen
    data class Split(val receiptId: String) : Screen
    data class Compare(val receiptId: String) : Screen
    data class Warranty(val id: String? = null, val receiptId: String? = null, val prefillJson: String? = null) : Screen
    data object Search : Screen
    data object Settings : Screen
    data class SettingsPage(val page: SettingsPageId) : Screen
    data object Reminders : Screen
    data object Shopping : Screen
    data object FoodLog : Screen
    data object Budgets : Screen
    data object Onboarding : Screen
}

enum class SettingsPageId(val title: String) {
    APPEARANCE("Appearance"), LANGUAGE("Language & currency"), PACKS("Feature packs"), RECEIPTS("Receipt defaults"),
    VOICE("Voice & commands"), TIPS("Tips"), NOTIFICATIONS("Notifications"), DATA("Data & privacy"), ABOUT("About"),
}

/** Back stack and transient UI state; kept in a ViewModel so rotation doesn't reset navigation. */
class AppState : ViewModel() {
    val stack = mutableStateListOf<Screen>(Screen.Tabs)
    var tab by mutableStateOf(Tab.HOME)
    val snackbar = SnackbarHostState()
    var commandOpen by mutableStateOf(false)
    var commandVoice by mutableStateOf(false)
    var commandText by mutableStateOf("")
    var reminderRequest by mutableStateOf<ReminderRequest?>(null)
    var exportRequest by mutableStateOf<ExportRequest?>(null)
    var pendingRecord by mutableStateOf<String?>(null)

    val top: Screen get() = stack.last()

    fun push(s: Screen) { stack.add(s) }
    fun replace(s: Screen) { if (stack.size > 1) stack.removeAt(stack.lastIndex); stack.add(s) }
    fun pop(): Boolean { if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }; return false }
    fun home(tab: Tab = Tab.HOME) { while (stack.size > 1) stack.removeAt(stack.lastIndex); this.tab = tab }
    fun openCommands(voice: Boolean, text: String = "") { commandVoice = voice; commandText = text; commandOpen = true }

    suspend fun undoable(message: String, onUndo: suspend () -> Unit) {
        val r = snackbar.showSnackbar(message, actionLabel = "Undo", duration = SnackbarDuration.Long, withDismissAction = true)
        if (r == SnackbarResult.ActionPerformed) onUndo()
    }

    suspend fun toast(message: String) { snackbar.showSnackbar(message, duration = SnackbarDuration.Short) }
}

/** A-Reminder sheet input. */
data class ReminderRequest(val title: String, val body: String, val defaultAt: java.time.LocalDateTime, val channel: String, val recordId: String?)

/** A-Export sheet input. */
data class ExportRequest(val receiptId: String? = null, val title: String = "Export")
