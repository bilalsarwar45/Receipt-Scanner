package com.receiptscan.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExpenseRow::class, AllocationRow::class, LinkRow::class, RevisionRow::class,
        ReceiptRow::class, ReceiptItemRow::class, ReceiptFts::class,
        InvoiceRow::class, PaymentRow::class, CommandRow::class,
        ScanRow::class, ScanFts::class, DocumentRow::class, ReminderRow::class, WarrantyRow::class,
        BudgetRow::class, MerchantRuleRow::class, ShoppingItemRow::class, FoodLogRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun recordsDao(): RecordsDao

    companion object {
        const val NAME = "receiptscan.db"

        fun create(context: Context, inMemory: Boolean = false): AppDatabase {
            val builder = if (inMemory) {
                Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            } else {
                Room.databaseBuilder(context, AppDatabase::class.java, NAME)
            }
            return builder.build()
        }
    }
}
