package com.receiptscan.app

import android.content.Context
import com.receiptscan.capture.ImageStore
import com.receiptscan.commands.CommandExecutor
import com.receiptscan.commands.CommandParser
import com.receiptscan.commands.StoreReceiptSearch
import com.receiptscan.data.local.AppDatabase
import com.receiptscan.data.local.AppPreferences
import com.receiptscan.data.local.RecordsRepository
import com.receiptscan.data.local.RoomLedgerStore
import com.receiptscan.data.local.Settings
import com.receiptscan.expenses.LedgerService
import com.receiptscan.money.Currencies
import com.receiptscan.money.CurrencySpec
import com.receiptscan.packs.android.PackManager
import com.receiptscan.scanner.expiry.ExpiryScanner
import com.receiptscan.scanner.receipt.ReceiptScanner
import com.receiptscan.scanner.translation.TranslationScanner
import com.receiptscan.time.DateOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId

/** Manual composition root. One instance per process, created by [ReceiptScanApp]. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock.systemDefaultZone()
    val db: AppDatabase = AppDatabase.create(context)
    val ledgerStore = RoomLedgerStore(db)
    val prefs = AppPreferences(context)
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    val current: Settings get() = _settings.value

    fun zone(): ZoneId = current.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
    fun currency(): CurrencySpec = Currencies.find(current.currency) ?: Currencies.PKR

    val ledger = LedgerService(ledgerStore, clock, ::zone)
    val records = RecordsRepository(db, clock)
    val images = ImageStore(context)
    val packs: PackManager = PackManager.get(context)
    val reminders = ReminderScheduler(context, records, clock)
    val parser = CommandParser(clock, ::zone, ::currency)
    val executor = CommandExecutor(ledger, StoreReceiptSearch(ledgerStore))

    init {
        scope.launch {
            prefs.settings.collect { s ->
                _settings.value = s
                applyModuleSettings(s)
            }
        }
        scope.launch { refreshLearnedRules() }
    }

    /** Pushes preferences into embeddable modules (they never read app storage themselves). */
    private fun applyModuleSettings(s: Settings) {
        val order = if (s.dateOrder == "MDY") DateOrder.MDY else DateOrder.DMY
        ReceiptScanner.dateOrder = order
        ReceiptScanner.enhanceByDefault = s.enhanceByDefault
        ReceiptScanner.autoCapture = s.autoCapture
        ExpiryScanner.dateOrder = order
        com.receiptscan.scanner.invoice.BillScanner.dateOrder = order
        TranslationScanner.preferredTarget = s.translateTarget
        TranslationScanner.wifiOnlyDownloads = s.wifiOnlyDownloads
    }

    /** Loads learned merchant names and categories into the receipt parser. */
    suspend fun refreshLearnedRules() {
        val rules = records.rules()
        ReceiptScanner.merchantAliases = rules.mapNotNull { r -> r.displayName?.let { r.merchantKey to it } }.toMap()
        ReceiptScanner.merchantRules = rules.mapNotNull { r -> r.categoryId?.let { r.merchantKey to it } }.toMap()
    }
}
