package com.receiptscan.data.local

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Clock
import java.util.UUID

/** Host persistence for non-ledger records: other scans, documents, reminders, warranties, budgets and rules. */
class RecordsRepository(private val db: AppDatabase, private val clock: Clock) {
    private val dao = db.recordsDao()

    fun newId(): String = UUID.randomUUID().toString()

    // ---- other scans (module envelopes)
    suspend fun saveScan(
        capabilityId: String,
        payloadVersion: Int,
        title: String,
        summary: String,
        payloadJson: String,
        searchText: String,
        sourceIds: List<String> = emptyList(),
        id: String = newId(),
    ): ScanRow = db.withTransaction {
        val row = ScanRow(id, capabilityId, payloadVersion, title.take(120), summary.take(300), payloadJson, sourceIds.toJson(), clock.millis(), searchText)
        dao.upsertScan(row)
        dao.deleteScanFts(id)
        dao.insertScanFts(ScanFts(id, title, searchText))
        row
    }

    fun observeScans(): Flow<List<ScanRow>> = dao.observeScans()
    suspend fun scan(id: String) = dao.scan(id)
    suspend fun deleteScan(id: String) = db.withTransaction { dao.deleteScanFts(id); dao.deleteScan(id) }
    suspend fun searchScans(text: String): Set<String> = ftsQuery(text)?.let { dao.searchScanIds(it).toSet() } ?: emptySet()

    // ---- documents
    suspend fun saveDocument(row: DocumentRow) = dao.upsertDocument(row)
    fun observeDocuments() = dao.observeDocuments()
    suspend fun document(id: String) = dao.document(id)
    suspend fun deleteDocument(id: String) = dao.deleteDocument(id)

    // ---- reminders
    suspend fun saveReminder(r: ReminderRow) = dao.upsertReminder(r)
    suspend fun reminder(id: String) = dao.reminder(id)
    fun observeOpenReminders() = dao.observeOpenReminders()
    suspend fun remindersFor(recordId: String) = dao.remindersFor(recordId)
    suspend fun deleteReminder(id: String) = dao.deleteReminder(id)
    suspend fun markReminderDone(id: String) { dao.reminder(id)?.let { dao.upsertReminder(it.copy(done = true)) } }

    // ---- warranties
    suspend fun saveWarranty(w: WarrantyRow) = dao.upsertWarranty(w)
    fun observeWarranties() = dao.observeWarranties()
    suspend fun warrantiesForReceipt(receiptId: String) = dao.warrantiesForReceipt(receiptId)
    suspend fun warranty(id: String) = dao.warranty(id)

    // ---- budgets
    suspend fun setBudget(b: BudgetRow) = dao.upsertBudget(b)
    suspend fun removeBudget(categoryId: String) = dao.deleteBudget(categoryId)
    fun observeBudgets() = dao.observeBudgets()

    // ---- learned merchant rules
    suspend fun learnMerchant(merchantKey: String, displayName: String?, categoryId: String?) =
        dao.upsertRule(MerchantRuleRow(merchantKey, displayName, categoryId, clock.millis()))
    suspend fun rules() = dao.rules()

    // ---- shopping list
    suspend fun saveShopping(s: ShoppingItemRow) = dao.upsertShopping(s)
    fun observeShopping() = dao.observeShopping()
    suspend fun clearDoneShopping() = dao.clearDoneShopping()

    // ---- food log
    suspend fun logFood(f: FoodLogRow) = dao.insertFood(f)
    fun observeFood(epochDay: Long) = dao.observeFood(epochDay)
    suspend fun deleteFood(id: String) = dao.deleteFood(id)

    /** Settings → Data & privacy → Delete all data. */
    suspend fun deleteEverything() = withContext(Dispatchers.IO) { db.clearAllTables() }
}
