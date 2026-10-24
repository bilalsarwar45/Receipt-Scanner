package com.receiptscan.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.receiptscan.data.local.RecordsRepository
import com.receiptscan.data.local.ReminderRow
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The five notification channels from handover section 8 (Y-Notifications). */
enum class ReminderChannel(val id: String, val title: String, val description: String, val importance: Int) {
    BILLS("bills", "Bill due dates", "Reminders before a bill is due", NotificationManager.IMPORTANCE_DEFAULT),
    WARRANTY("warranty", "Warranty and returns", "Return windows and warranty end dates", NotificationManager.IMPORTANCE_DEFAULT),
    EXPIRY("expiry", "Expiry dates", "Food and medicine expiry reminders", NotificationManager.IMPORTANCE_DEFAULT),
    RECEIPT("receipt", "Receipt reminders", "Reminders you set on a receipt", NotificationManager.IMPORTANCE_DEFAULT),
    DRAFT("draft", "Background tasks", "Exports and feature packs ready", NotificationManager.IMPORTANCE_LOW);

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: RECEIPT }
}

/** Stores reminders and schedules one WorkManager job per reminder. Notifications need no exact alarms. */
class ReminderScheduler(private val context: Context, private val records: RecordsRepository, private val clock: Clock) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        ReminderChannel.entries.forEach { c -> nm.createNotificationChannel(NotificationChannel(c.id, c.title, c.importance).apply { description = c.description }) }
    }

    fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    suspend fun schedule(title: String, body: String, at: LocalDateTime, zone: ZoneId, channel: String, recordId: String?, id: String = UUID.randomUUID().toString()): ReminderRow {
        val due = at.atZone(zone).toInstant().toEpochMilli()
        val row = ReminderRow(id, ReminderChannel.of(channel).id, recordId, title.take(120), body.take(300), due, false, clock.millis())
        records.saveReminder(row)
        enqueue(row)
        return row
    }

    fun enqueue(row: ReminderRow) {
        val delay = Duration.ofMillis(row.dueAt - clock.millis()).coerceAtLeast(Duration.ZERO)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_ID to row.id))
            .addTag("reminder")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("reminder-${row.id}", ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun cancel(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork("reminder-$id")
        records.deleteReminder(id)
    }

    suspend fun cancelFor(recordId: String) { records.remindersFor(recordId).forEach { cancel(it.id) } }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ReceiptScanApp).graph
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val row = graph.records.reminder(id) ?: return Result.success()
        if (row.done) return Result.success()
        if (graph.reminders.notificationsAllowed()) {
            val open = PendingIntent.getActivity(
                applicationContext, id.hashCode(),
                Intent(applicationContext, MainActivity::class.java).putExtra(MainActivity.EXTRA_RECORD, row.recordId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(applicationContext, row.channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(row.title)
                .setContentText(row.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(row.body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(applicationContext).notify(id.hashCode(), n) } catch (_: SecurityException) {}
        }
        graph.records.markReminderDone(id)
        return Result.success()
    }

    companion object { const val KEY_ID = "reminder_id" }
}
