package com.receiptscan.scanner.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.receiptscan.barcode.BarcodeReader
import com.receiptscan.barcode.CodeSet
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTopBar
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.EmptyState
import com.receiptscan.design.ListRow
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.ModuleTile
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.ValueLine
import com.receiptscan.design.skin
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.FailReason
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.qr.engine.QrPayload
import com.receiptscan.scanner.qr.engine.QrPayloadParser
import com.receiptscan.scanner.qr.engine.WifiSecurity
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.CaptureScreen
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.wifi.engine.NetworkList
import com.receiptscan.scanner.wifi.engine.WifiNetworks
import com.receiptscan.scanner.wifi.engine.WifiScanEntry
import kotlinx.coroutines.launch

sealed interface WifiScan {
    data class FromQr(val payload: QrPayload.Wifi) : WifiScan
    data object Nearby : WifiScan
}

/** Hands Wi-Fi credentials to Android. The app never stores the password. */
object WifiConnector {
    /** Android 11+: system "Save network?" dialog. Android 10: network suggestion. Older: open Wi-Fi settings. */
    fun connect(context: Context, wifi: QrPayload.Wifi): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val b = WifiNetworkSuggestion.Builder().setSsid(wifi.ssid)
            when (wifi.security) {
                WifiSecurity.WPA -> wifi.password?.let { b.setWpa2Passphrase(it) }
                WifiSecurity.WPA3 -> wifi.password?.let { b.setWpa3Passphrase(it) }
                else -> Unit
            }
            if (wifi.hidden) b.setIsHiddenSsid(true)
            val suggestion = runCatching { b.build() }.getOrNull()
            if (suggestion != null && Build.VERSION.SDK_INT >= 30) {
                val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "Android will ask you to save this network."
            }
            if (suggestion != null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val status = wm.addNetworkSuggestions(listOf(suggestion))
                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) return "Suggested to Android. It connects when the network is in range."
            }
        }
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened Wi-Fi settings. Pick ${wifi.ssid} and enter the password."
    }
}

/** Wi-Fi (S-WifiChoice, S-WifiList): Wi-Fi QR codes and nearby networks with honest staleness. */
object WifiScanner : ScannerModule<WifiScan> {
    val ID = CapabilityId("wifi")
    override val info = ModuleInfo(ID, "Wi-Fi", "Wi-Fi QR codes and nearby networks", ModuleColor.WIFI, AppIcons.wifi,
        listOf("Passwords go to Android's Wi-Fi settings, never stored", "Avoid open networks for banking"))

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<WifiScan>) -> Unit, onCancel: () -> Unit) {
        val src = input.source
        if (src is ScanSource.Text) {
            LaunchedEffect(src) {
                val p = QrPayloadParser.parse(src.text)
                onResult(if (p is QrPayload.Wifi) ScanOutcome.Success(WifiScan.FromQr(p)) else ScanOutcome.Failed(FailReason.UNSUPPORTED, "That code isn't a Wi-Fi code."))
            }
            return
        }
        var mode by remember { mutableStateOf<String?>(null) }
        when (mode) {
            null -> ChoiceScreen(onQr = { mode = "qr" }, onNearby = { onResult(ScanOutcome.Success(WifiScan.Nearby)) }, onCancel = onCancel)
            else -> {
                val reader = remember { BarcodeReader(CodeSet.QR_ONLY) }
                val scope = rememberCoroutineScope()
                var done by remember { mutableStateOf(false) }
                DisposableEffect(Unit) { onDispose { reader.close() } }
                fun handle(raw: String) {
                    if (done) return
                    val p = QrPayloadParser.parse(raw)
                    if (p is QrPayload.Wifi) { done = true; onResult(ScanOutcome.Success(WifiScan.FromQr(p))) }
                }
                CaptureScreen(info, host, input,
                    onCaptured = { b -> scope.launch {
                        val codes = runCatching { reader.read(b.first()) }.getOrDefault(emptyList())
                        val wifi = codes.map { it.raw }.firstOrNull { it.uppercase().startsWith("WIFI:") }
                        if (wifi != null) handle(wifi) else onResult(ScanOutcome.Failed(FailReason.UNSUPPORTED, "No Wi-Fi QR code found in that photo."))
                    } },
                    onCancel = { mode = null }, noun = "code", measureGuidance = false, hint = "Point at a Wi-Fi QR code",
                    analyzer = { proxy -> reader.read(proxy) { codes -> codes.firstOrNull { it.raw.uppercase().startsWith("WIFI:") }?.let { handle(it.raw) } } },
                )
            }
        }
    }

    @Composable
    private fun ChoiceScreen(onQr: () -> Unit, onNearby: () -> Unit, onCancel: () -> Unit) {
        androidx.activity.compose.BackHandler(onBack = onCancel)
        ScreenSurface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                AppTopBar("Wi-Fi", onBack = onCancel)
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppCard(Modifier.fillMaxWidth(), onClick = onQr) {
                        ListRow("Scan Wi-Fi QR", meta = "Join a network from a printed code", leading = { ModuleTile(ModuleColor.QR, AppIcons.qr) })
                    }
                    AppCard(Modifier.fillMaxWidth(), onClick = onNearby) {
                        ListRow("Nearby networks", meta = "See networks around you and how they're secured", leading = { ModuleTile(ModuleColor.WIFI, AppIcons.signal) })
                    }
                }
            }
        }
    }

    @Composable
    override fun Result(result: WifiScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        when (result) {
            is WifiScan.FromQr -> QrResult(result.payload, onAction, onBack)
            WifiScan.Nearby -> NearbyList(host, onBack)
        }
    }

    @Composable
    private fun QrResult(p: QrPayload.Wifi, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val context = LocalContext.current
        var revealed by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf<String?>(null) }
        val t = skin
        ResultScaffold(info, "Wi-Fi network", onBack, bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (p.password != null) AppButton("Copy password", { onAction(ResultAction.Copy(p.password.orEmpty())) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, fill = true)
                AppButton("Connect", { status = runCatching { WifiConnector.connect(context, p) }.getOrElse { "Couldn't open Wi-Fi settings: ${it.message}" } }, Modifier.weight(1f), icon = AppIcons.wifi, fill = true)
            }
        }) {
            ModuleHeader(info, p.ssid, p.security.label)
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    ValueLine("Network name", p.ssid, ValueSource.FROM_PHOTO)
                    ValueLine("Security", p.security.label, ValueSource.FROM_PHOTO)
                    if (p.password != null) ValueLine("Password", if (revealed) p.password.orEmpty() else p.maskedPassword.orEmpty(), ValueSource.FROM_PHOTO, onClick = { revealed = !revealed })
                    if (p.password != null) Text(if (revealed) "Tap to hide" else "Tap to show", style = AppType.cap, color = t.muted)
                }
            }
            if (p.security == WifiSecurity.OPEN) Banner(BadgeKind.WARN, "Open network", "Anyone nearby can see what you send. Avoid banking and passwords on it.")
            if (p.security == WifiSecurity.WEP) Banner(BadgeKind.WARN, "Outdated security (WEP)", "WEP is easy to break. Prefer another network.")
            status?.let { Banner(BadgeKind.INFO, it) }
            Text("The password goes to Android's Wi-Fi settings. This app doesn't store it.", style = AppType.cap, color = t.muted, modifier = Modifier.padding(top = 8.dp))
        }
    }

    private fun scanPermission(): String = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    @SuppressLint("MissingPermission")
    private fun read(context: Context): NetworkList {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        val entries = wm.scanResults.map { r ->
            @Suppress("DEPRECATION")
            val ssid = if (Build.VERSION.SDK_INT >= 33) (r.wifiSsid?.toString()?.trim('"') ?: "") else r.SSID
            // ScanResult.timestamp is microseconds since boot; convert to wall-clock millis.
            val wall = nowWall - (nowElapsed - r.timestamp / 1000)
            WifiScanEntry(ssid, r.BSSID ?: "", r.level, r.frequency, r.capabilities ?: "", wall)
        }
        @Suppress("DEPRECATION")
        val connected = wm.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        return WifiNetworks.build(entries, connected, nowWall)
    }

    @Composable
    private fun NearbyList(host: ScannerHost, onBack: () -> Unit) {
        val context = LocalContext.current
        val t = skin
        var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, scanPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
        var list by remember { mutableStateOf<NetworkList?>(null) }
        var throttled by remember { mutableStateOf(false) }
        var tick by remember { mutableLongStateOf(0L) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        DisposableEffect(granted) {
            if (!granted) return@DisposableEffect onDispose { }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { list = runCatching { read(context) }.getOrNull() }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
        LaunchedEffect(granted, tick) {
            if (!granted) return@LaunchedEffect
            list = runCatching { read(context) }.getOrNull()
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            throttled = !runCatching { wm.startScan() }.getOrDefault(false)
        }
        ResultScaffold(info, "Nearby networks", onBack, actions = {
            if (granted) com.receiptscan.design.AppIconButton(AppIcons.refresh, "Refresh", { tick++ })
        }) {
            if (!granted) {
                EmptyState(AppIcons.wifi, "Allow nearby Wi-Fi", if (Build.VERSION.SDK_INT >= 33) "Android needs permission to list Wi-Fi networks. It isn't used for location." else "Android 12 and older require location permission to list Wi-Fi networks. We don't use your location.",
                    actionLabel = "Allow", onAction = { launcher.launch(scanPermission()) })
                return@ResultScaffold
            }
            val l = list
            if (l == null || l.networks.isEmpty()) {
                EmptyState(AppIcons.wifiOff, "No Wi-Fi networks", "Wi-Fi may be off, or no networks are in range. Turn on Wi-Fi and refresh.", actionLabel = "Refresh", onAction = { tick++ })
                return@ResultScaffold
            }
            if (l.stale || throttled) Banner(BadgeKind.INFO, "Results may be out of date", "Android limits how often apps can scan. " + (l.lastScanMillis?.let { "Last scan ${((System.currentTimeMillis() - it) / 1000)} s ago." } ?: ""))
            l.networks.forEach { n ->
                ListRow(
                    n.displayName,
                    meta = "${n.security.label} · ${n.band.label} · ${n.accessPoints} access point${if (n.accessPoints == 1) "" else "s"}",
                    leading = { ModuleTile(ModuleColor.WIFI, if (n.security.isOpen) AppIcons.wifi else AppIcons.lock, size = 36.dp) },
                    trailing = {
                        if (n.connected) StatusBadge(BadgeKind.OK, "Connected")
                        Text(n.signal.label, style = AppType.cap, color = t.muted)
                    },
                )
                n.warning?.let { Text(it, style = AppType.cap, color = t.warn, modifier = Modifier.padding(start = 48.dp, bottom = 8.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            Text("Signal is shown as text from the strength Android reports. Connecting is done in Android settings.", style = AppType.cap, color = t.muted)
        }
    }
}
