package com.receiptscan.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.app.ui.BillDetailScreen
import com.receiptscan.app.ui.BudgetsScreen
import com.receiptscan.app.ui.CommandHost
import com.receiptscan.app.ui.CompareScreen
import com.receiptscan.app.ui.DocumentDetailScreen
import com.receiptscan.app.ui.ExpenseDetailScreen
import com.receiptscan.app.ui.ExpenseFormScreen
import com.receiptscan.app.ui.ExportSheet
import com.receiptscan.app.ui.FoodLogScreen
import com.receiptscan.app.ui.OnboardingScreen
import com.receiptscan.app.ui.PdfScreen
import com.receiptscan.app.ui.ReceiptDetailScreen
import com.receiptscan.app.ui.ReceiptEditScreen
import com.receiptscan.app.ui.ReceiptSavedScreen
import com.receiptscan.app.ui.ReminderSheet
import com.receiptscan.app.ui.RemindersScreen
import com.receiptscan.app.ui.ScanDetailScreen
import com.receiptscan.app.ui.ScannerScreen
import com.receiptscan.app.ui.SearchScreen
import com.receiptscan.app.ui.SettingsPageScreen
import com.receiptscan.app.ui.SettingsScreen
import com.receiptscan.app.ui.ShoppingScreen
import com.receiptscan.app.ui.SplitScreen
import com.receiptscan.app.ui.TabsScreen
import com.receiptscan.app.ui.WarrantyScreen
import com.receiptscan.design.ReceiptScanTheme
import com.receiptscan.design.Skin
import com.receiptscan.design.ThemeMode
import com.receiptscan.design.skin

class MainActivity : ComponentActivity() {
    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as ReceiptScanApp).graph
        handleIntent(intent)
        setContent {
            val settings by graph.settings.collectAsState()
            ReceiptScanTheme(
                skin = runCatching { Skin.valueOf(settings.skin) }.getOrDefault(Skin.SIMPLE),
                mode = runCatching { ThemeMode.valueOf(settings.themeMode) }.getOrDefault(ThemeMode.SYSTEM),
                reduceMotion = settings.reduceMotion,
                biggerAmounts = settings.biggerAmounts,
            ) { AppRoot(graph, state) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Notification taps carry a record id; open whichever record it belongs to. */
    private fun handleIntent(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_RECORD) ?: return
        state.pendingRecord = id
    }

    companion object { const val EXTRA_RECORD = "record_id" }
}

@Composable
fun AppRoot(graph: AppGraph, state: AppState) {
    val t = skin
    val settings by graph.settings.collectAsState()
    LaunchedEffect(state.pendingRecord) {
        val id = state.pendingRecord ?: return@LaunchedEffect
        state.pendingRecord = null
        openRecord(graph, state, id)
    }
    BackHandler(enabled = state.stack.size > 1 || state.tab != Tab.HOME) { if (!state.pop()) state.tab = Tab.HOME }
    Box(Modifier.fillMaxSize()) {
        val top = state.top
        AnimatedContent(top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { s ->
            when (s) {
                Screen.Tabs -> if (!settings.onboarded) OnboardingScreen(graph, state) else TabsScreen(graph, state)
                is Screen.Scanner -> ScannerScreen(graph, state, s.id, s.source)
                Screen.Pdf -> PdfScreen(graph, state)
                is Screen.ReceiptSaved -> ReceiptSavedScreen(graph, state, s)
                is Screen.ReceiptDetail -> ReceiptDetailScreen(graph, state, s.id)
                is Screen.ReceiptEdit -> ReceiptEditScreen(graph, state, s.id)
                is Screen.ExpenseDetail -> ExpenseDetailScreen(graph, state, s.id)
                is Screen.ExpenseForm -> ExpenseFormScreen(graph, state, s.id, s.attachReceiptId)
                is Screen.BillDetail -> BillDetailScreen(graph, state, s.id)
                is Screen.ScanDetail -> ScanDetailScreen(graph, state, s.id)
                is Screen.DocumentDetail -> DocumentDetailScreen(graph, state, s.id)
                is Screen.Split -> SplitScreen(graph, state, s.receiptId)
                is Screen.Compare -> CompareScreen(graph, state, s.receiptId)
                is Screen.Warranty -> WarrantyScreen(graph, state, s)
                Screen.Search -> SearchScreen(graph, state)
                Screen.Settings -> SettingsScreen(graph, state)
                is Screen.SettingsPage -> SettingsPageScreen(graph, state, s.page)
                Screen.Reminders -> RemindersScreen(graph, state)
                Screen.Shopping -> ShoppingScreen(graph, state)
                Screen.FoodLog -> FoodLogScreen(graph, state)
                Screen.Budgets -> BudgetsScreen(graph, state)
                Screen.Onboarding -> OnboardingScreen(graph, state)
            }
        }
        SnackbarHost(state.snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (top == Screen.Tabs) 100.dp else 16.dp)) { d ->
            Snackbar(d, containerColor = t.snack, contentColor = t.snackText, actionColor = t.snackAction)
        }
        if (state.commandOpen) CommandHost(graph, state)
        state.reminderRequest?.let { ReminderSheet(graph, state, it) }
        state.exportRequest?.let { ExportSheet(graph, state, it) }
    }
}

/** Finds which kind of record an id belongs to (receipt, bill, expense, scan) and opens it. */
suspend fun openRecord(graph: AppGraph, state: AppState, id: String) {
    when {
        graph.ledgerStore.receipt(id) != null -> state.push(Screen.ReceiptDetail(id))
        graph.ledgerStore.invoice(id) != null -> state.push(Screen.BillDetail(id))
        graph.ledgerStore.expense(id) != null -> state.push(Screen.ExpenseDetail(id))
        graph.records.scan(id) != null -> state.push(Screen.ScanDetail(id))
        graph.records.warranty(id) != null -> state.push(Screen.Warranty(id = id))
        else -> state.push(Screen.Reminders)
    }
}
