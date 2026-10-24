package com.receiptscan.sample

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.receiptscan.capture.SessionTempStorage
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.ReceiptScanTheme
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.skin
import com.receiptscan.scanner.contracts.HostCapabilities
import com.receiptscan.scanner.plant.PlantScanner
import com.receiptscan.scanner.qr.QrScanner
import com.receiptscan.scanner.text.TextScanner
import com.receiptscan.scanner.ui.BasicScannerHost
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerFlow
import com.receiptscan.scanner.ui.ScannerModule

/**
 * A minimal second host. It embeds three scanners through their public [ScannerModule] API with a
 * [BasicScannerHost]: no Library, no ledger, no persistence. Results can be copied, shared or opened.
 */
class SampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ReceiptScanTheme { SampleHome() } }
    }
}

private val modules: List<ScannerModule<*>> = listOf(QrScanner, TextScanner, PlantScanner)

@Composable
private fun SampleHome() {
    val t = skin
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val module = modules.firstOrNull { it.id.value == open }
    if (module != null) {
        val temp = remember(open) { SessionTempStorage(context) }
        DisposableEffect(open) { onDispose { temp.clear() } }
        // This host cannot save; modules hide their Save buttons and offer Copy and Share instead.
        val host = remember(open) { BasicScannerHost(temp, HostCapabilities(canSave = false, canSetReminders = false)) }
        BackHandler { open = null }
        @Suppress("UNCHECKED_CAST")
        ScannerFlow(module as ScannerModule<Any>, ScanInput(), host, onAction = { a -> handle(context, a) { open = null } }, onClose = { open = null })
        return
    }
    ScreenSurface {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            AppTopBar("Scanner host sample")
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This app embeds scanner modules on their own, without the Receipt & Scan app or its expense ledger.", style = AppType.b2, color = t.muted)
                modules.forEach { m ->
                    AppCard(Modifier.fillMaxWidth(), onClick = { open = m.id.value }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ModuleTile(m.info.color, m.info.icon)
                            Column(Modifier.weight(1f)) {
                                Text(m.info.title, style = AppType.h2, color = t.text)
                                Text(m.info.subtitle, style = AppType.cap, color = t.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun handle(context: android.content.Context, a: ResultAction, close: () -> Unit) {
    when (a) {
        is ResultAction.Copy -> {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("scan", a.text))
            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        }
        is ResultAction.Share -> context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, a.text), "Share"))
        is ResultAction.OpenLink -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.url))) }
        ResultAction.Done -> close()
        is ResultAction.Save, is ResultAction.Remind, is ResultAction.HandOff, is ResultAction.Custom ->
            Toast.makeText(context, "Not available in this sample host", Toast.LENGTH_SHORT).show()
        ResultAction.ScanAgain -> {}
    }
}
