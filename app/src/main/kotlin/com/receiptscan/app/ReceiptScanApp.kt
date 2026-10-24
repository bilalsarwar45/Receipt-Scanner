package com.receiptscan.app

import android.app.Application
import com.receiptscan.capture.SessionTempStorage

class ReceiptScanApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.reminders.createChannels()
        // Scan sessions left behind by a crash or process death hold only temporary images.
        SessionTempStorage.sweep(this)
        graph.scope.launchMaintenance(graph)
    }
}
