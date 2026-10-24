package com.receiptscan.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration

/** Startup housekeeping: re-enqueue open reminders and apply the photo auto-delete setting. */
fun CoroutineScope.launchMaintenance(graph: AppGraph) = launch {
    delay(1500)
    runCatching { graph.records.observeOpenReminders().first().forEach { graph.reminders.enqueue(it) } }
    val days = graph.prefs.settings.first().autoDeleteDays
    if (days > 0) {
        val cutoff = System.currentTimeMillis() - Duration.ofDays(days.toLong()).toMillis()
        graph.context.filesDir.resolve("images").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
