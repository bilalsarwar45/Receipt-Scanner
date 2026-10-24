package com.receiptscan.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExpense(e: ExpenseRow)
    @Upsert suspend fun upsertExpense(e: ExpenseRow)
    @Query("SELECT * FROM expenses WHERE id = :id") suspend fun expense(id: String): ExpenseRow?
    @Query("SELECT * FROM expenses WHERE effectiveDate >= :fromDay AND effectiveDate < :toDay ORDER BY effectiveDate DESC, recordedAt DESC")
    suspend fun expensesBetween(fromDay: Long, toDay: Long): List<ExpenseRow>
    @Query("SELECT * FROM expenses WHERE effectiveDate >= :fromDay AND effectiveDate < :toDay ORDER BY effectiveDate DESC, recordedAt DESC")
    fun observeExpensesBetween(fromDay: Long, toDay: Long): Flow<List<ExpenseRow>>

    @Query("DELETE FROM expense_allocations WHERE expenseId = :expenseId") suspend fun deleteAllocations(expenseId: String)
    @Insert suspend fun insertAllocations(rows: List<AllocationRow>)
    @Query("SELECT * FROM expense_allocations WHERE expenseId IN (:ids)") suspend fun allocations(ids: List<String>): List<AllocationRow>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLink(l: LinkRow)
    @Query("DELETE FROM expense_links WHERE kind = :kind AND targetId = :targetId AND expenseId = :expenseId") suspend fun deleteLink(expenseId: String, kind: String, targetId: String)
    @Query("SELECT * FROM expense_links WHERE expenseId = :expenseId") suspend fun links(expenseId: String): List<LinkRow>
    @Query("SELECT * FROM expense_links WHERE kind = :kind AND targetId = :targetId") suspend fun linkForTarget(kind: String, targetId: String): LinkRow?

    @Insert suspend fun insertRevision(r: RevisionRow)
    @Query("SELECT * FROM expense_revisions WHERE expenseId = :expenseId ORDER BY version, rowId") suspend fun revisions(expenseId: String): List<RevisionRow>

    @Upsert suspend fun upsertReceipt(r: ReceiptRow)
    @Query("SELECT * FROM receipts WHERE id = :id") suspend fun receipt(id: String): ReceiptRow?
    @Query("SELECT * FROM receipts WHERE status IN (:statuses) ORDER BY purchaseDate DESC, scannedAt DESC")
    suspend fun receipts(statuses: List<String>): List<ReceiptRow>
    @Query("SELECT * FROM receipts WHERE status IN (:statuses) ORDER BY purchaseDate DESC, scannedAt DESC")
    fun observeReceipts(statuses: List<String>): Flow<List<ReceiptRow>>
    @Query("DELETE FROM receipts WHERE id = :id") suspend fun deleteReceipt(id: String)

    @Query("DELETE FROM receipt_items WHERE receiptId = :receiptId") suspend fun deleteItems(receiptId: String)
    @Insert suspend fun insertItems(rows: List<ReceiptItemRow>)
    @Query("SELECT * FROM receipt_items WHERE receiptId IN (:ids) ORDER BY position") suspend fun items(ids: List<String>): List<ReceiptItemRow>
    @Query("SELECT * FROM receipt_items ORDER BY receiptId, position") fun observeAllItems(): Flow<List<ReceiptItemRow>>

    @Query("DELETE FROM receipt_fts WHERE receiptId = :receiptId") suspend fun deleteFts(receiptId: String)
    @Insert suspend fun insertFts(row: ReceiptFts)
    @Query("SELECT receiptId FROM receipt_fts WHERE receipt_fts MATCH :query") suspend fun searchReceiptIds(query: String): List<String>

    @Upsert suspend fun upsertInvoice(i: InvoiceRow)
    @Query("SELECT * FROM invoices WHERE id = :id") suspend fun invoice(id: String): InvoiceRow?
    @Query("SELECT * FROM invoices ORDER BY dueDate IS NULL, dueDate") suspend fun invoices(): List<InvoiceRow>
    @Query("SELECT * FROM invoices ORDER BY dueDate IS NULL, dueDate") fun observeInvoices(): Flow<List<InvoiceRow>>
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPayment(p: PaymentRow)
    @Query("SELECT * FROM payments WHERE id = :id") suspend fun payment(id: String): PaymentRow?
    @Query("SELECT * FROM payments WHERE invoiceId = :invoiceId ORDER BY paidDate") suspend fun payments(invoiceId: String): List<PaymentRow>

    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertCommand(c: CommandRow)
    @Upsert suspend fun upsertCommand(c: CommandRow)
    @Query("SELECT * FROM command_executions WHERE executionKey = :key") suspend fun command(key: String): CommandRow?
    @Query("SELECT * FROM command_executions ORDER BY executedAt DESC LIMIT :limit") suspend fun recentCommands(limit: Int): List<CommandRow>
}

@Dao
interface RecordsDao {
    @Upsert suspend fun upsertScan(s: ScanRow)
    @Query("SELECT * FROM scans WHERE id = :id") suspend fun scan(id: String): ScanRow?
    @Query("SELECT * FROM scans ORDER BY createdAt DESC") fun observeScans(): Flow<List<ScanRow>>
    @Query("DELETE FROM scans WHERE id = :id") suspend fun deleteScan(id: String)
    @Query("DELETE FROM scan_fts WHERE scanId = :id") suspend fun deleteScanFts(id: String)
    @Insert suspend fun insertScanFts(row: ScanFts)
    @Query("SELECT scanId FROM scan_fts WHERE scan_fts MATCH :query") suspend fun searchScanIds(query: String): List<String>

    @Upsert suspend fun upsertDocument(d: DocumentRow)
    @Query("SELECT * FROM documents ORDER BY createdAt DESC") fun observeDocuments(): Flow<List<DocumentRow>>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun document(id: String): DocumentRow?
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDocument(id: String)

    @Upsert suspend fun upsertReminder(r: ReminderRow)
    @Query("SELECT * FROM reminders WHERE id = :id") suspend fun reminder(id: String): ReminderRow?
    @Query("SELECT * FROM reminders WHERE done = 0 ORDER BY dueAt") fun observeOpenReminders(): Flow<List<ReminderRow>>
    @Query("SELECT * FROM reminders WHERE recordId = :recordId ORDER BY dueAt") suspend fun remindersFor(recordId: String): List<ReminderRow>
    @Query("DELETE FROM reminders WHERE id = :id") suspend fun deleteReminder(id: String)

    @Upsert suspend fun upsertWarranty(w: WarrantyRow)
    @Query("SELECT * FROM warranties ORDER BY createdAt DESC") fun observeWarranties(): Flow<List<WarrantyRow>>
    @Query("SELECT * FROM warranties WHERE receiptId = :receiptId") suspend fun warrantiesForReceipt(receiptId: String): List<WarrantyRow>
    @Query("SELECT * FROM warranties WHERE id = :id") suspend fun warranty(id: String): WarrantyRow?

    @Upsert suspend fun upsertBudget(b: BudgetRow)
    @Query("DELETE FROM budgets WHERE categoryId = :categoryId") suspend fun deleteBudget(categoryId: String)
    @Query("SELECT * FROM budgets") fun observeBudgets(): Flow<List<BudgetRow>>

    @Upsert suspend fun upsertRule(r: MerchantRuleRow)
    @Query("SELECT * FROM merchant_rules") suspend fun rules(): List<MerchantRuleRow>

    @Upsert suspend fun upsertShopping(s: ShoppingItemRow)
    @Query("SELECT * FROM shopping_items ORDER BY done, createdAt DESC") fun observeShopping(): Flow<List<ShoppingItemRow>>
    @Query("DELETE FROM shopping_items WHERE done = 1") suspend fun clearDoneShopping()

    @Insert suspend fun insertFood(f: FoodLogRow)
    @Query("SELECT * FROM food_log WHERE eatenOn = :day ORDER BY createdAt") fun observeFood(day: Long): Flow<List<FoodLogRow>>
    @Query("DELETE FROM food_log WHERE id = :id") suspend fun deleteFood(id: String)
}
