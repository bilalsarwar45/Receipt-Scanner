package com.receiptscan.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import com.receiptscan.app.AppGraph
import com.receiptscan.app.AppState
import com.receiptscan.app.ExportRequest
import com.receiptscan.app.Screen
import com.receiptscan.app.SettingsPageId
import com.receiptscan.app.mb
import com.receiptscan.data.local.MerchantRuleRow
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ListRow
import com.receiptscan.design.ScreenSurface
import com.receiptscan.design.Skin
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.ThemeMode
import com.receiptscan.design.skin
import com.receiptscan.expenses.Categories
import com.receiptscan.feature.documents.DocumentsFeature
import com.receiptscan.money.Currencies
import com.receiptscan.packs.PackState
import com.receiptscan.scanner.ui.Banner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** S-Settings index. */
@Composable
fun SettingsScreen(graph: AppGraph, state: AppState) {
    val t = skin
    val icons = mapOf(
        SettingsPageId.APPEARANCE to AppIcons.palette, SettingsPageId.LANGUAGE to AppIcons.globe, SettingsPageId.PACKS to AppIcons.download,
        SettingsPageId.RECEIPTS to AppIcons.receipt, SettingsPageId.VOICE to AppIcons.mic, SettingsPageId.TIPS to AppIcons.help,
        SettingsPageId.NOTIFICATIONS to AppIcons.bell, SettingsPageId.DATA to AppIcons.shield, SettingsPageId.ABOUT to AppIcons.info,
    )
    val meta = mapOf(
        SettingsPageId.APPEARANCE to "Skin, light or dark, motion, amount size",
        SettingsPageId.LANGUAGE to "Currency, date order, translation language",
        SettingsPageId.PACKS to "Downloaded models and data, Wi‑Fi only",
        SettingsPageId.RECEIPTS to "Photos, enhance, auto-capture, learned stores",
        SettingsPageId.VOICE to "Speech recognition and quick add",
        SettingsPageId.TIPS to "First-use tips in each tool",
        SettingsPageId.NOTIFICATIONS to "Reminders and budget alerts",
        SettingsPageId.DATA to "Storage, export, delete everything",
        SettingsPageId.ABOUT to "Version, models, licences, signing key",
    )
    Page("Settings", { state.pop() }) {
        SettingsPageId.entries.forEach { p ->
            ListRow(p.title, meta = meta[p], leading = { Icon(icons.getValue(p), null, tint = t.muted) }, trailing = { Icon(AppIcons.chev, null, tint = t.muted) },
                onClick = { state.push(Screen.SettingsPage(p)) })
        }
        Section("More")
        ListRow("Budgets", meta = "Monthly limits", leading = { Icon(AppIcons.insights, null, tint = t.muted) }, onClick = { state.push(Screen.Budgets) })
        ListRow("Reminders", meta = "Everything scheduled", leading = { Icon(AppIcons.bell, null, tint = t.muted) }, onClick = { state.push(Screen.Reminders) })
        ListRow("Show the welcome again", leading = { Icon(AppIcons.sparkle, null, tint = t.muted) }, onClick = { state.push(Screen.Onboarding) })
    }
}

@Composable
private fun Toggle(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = skin
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.b1, color = t.text)
            body?.let { Text(it, style = AppType.cap, color = t.muted) }
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    val t = skin
    Text(label, style = AppType.lab, color = t.muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, name) -> AppChip(name, { onPick(id) }, selected = selected == id) }
    }
}

@Composable
fun SettingsPageScreen(graph: AppGraph, state: AppState, page: SettingsPageId) {
    val s by graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val p = graph.prefs
    fun set(block: suspend () -> Unit) { scope.launch { block() } }
    Page(page.title, { state.pop() }) {
        when (page) {
            SettingsPageId.APPEARANCE -> {
                Choice("Skin", Skin.entries.map { it.name to it.label }, s.skin) { v -> set { p.setSkin(v) } }
                Choice("Theme", ThemeMode.entries.map { it.name to it.label }, s.themeMode) { v -> set { p.setTheme(v) } }
                Toggle("Reduce motion", "Fewer animations and no scan-line sweep", s.reduceMotion) { v -> set { p.setReduceMotion(v) } }
                Toggle("Bigger amounts", "Larger numbers on totals and lists", s.biggerAmounts) { v -> set { p.setBiggerAmounts(v) } }
            }
            SettingsPageId.LANGUAGE -> {
                Choice("Default currency", Currencies.all.map { it.code to it.code }, s.currency) { v -> set { p.setCurrency(v) } }
                Text("Used for new manual and voice expenses. Receipts keep the currency printed on them; different currencies are never added together.", style = AppType.cap, color = skin.muted)
                Choice("Printed dates like 03/04", listOf("DMY" to "Day first (3 April)", "MDY" to "Month first (March 4)"), s.dateOrder) { v -> set { p.setDateOrder(v) } }
                Choice("Translate into", listOf("en" to "English", "ur" to "Urdu", "ar" to "Arabic", "hi" to "Hindi", "es" to "Spanish", "fr" to "French", "de" to "German", "zh" to "Chinese", "tr" to "Turkish"), s.translateTarget) { v -> set { p.setTranslateTarget(v) } }
                Text("Time zone: ${graph.zone().id} (from the phone)", style = AppType.cap, color = skin.muted)
            }
            SettingsPageId.PACKS -> PacksPage(graph, state)
            SettingsPageId.RECEIPTS -> ReceiptsPage(graph)
            SettingsPageId.VOICE -> {
                Toggle("Quick add", "Simple voice expenses with a clear amount and category are saved right away, with Undo. Otherwise you always see a preview first.", s.voiceQuickAdd) { v -> set { p.setVoiceQuickAdd(v) } }
                Toggle("Allow the system speech service", "Only if on-device recognition isn't available. Your phone's speech service may send audio to its provider.", s.allowSystemSpeech) { v -> set { p.setAllowSystemSpeech(v) } }
                Banner(BadgeKind.INFO, "How voice works", "Speech is turned into text on this phone using Android's on-device recognizer (Android 12+). You can always edit the text before anything is saved, or type instead.")
                AppButton("Show the voice intro again", { set { p.setShowTips(true) } }, variant = ButtonVariant.GHOST)
            }
            SettingsPageId.TIPS -> {
                Toggle("Show tips", "A short how-to the first time you open each tool", s.showTips) { v -> set { p.setShowTips(v) } }
                if (s.dismissedTips.isNotEmpty()) AppButton("Show all tips again (${s.dismissedTips.size} hidden)", { set { p.setShowTips(true) } }, variant = ButtonVariant.TONAL)
            }
            SettingsPageId.NOTIFICATIONS -> NotificationsPage(graph)
            SettingsPageId.DATA -> DataPage(graph, state)
            SettingsPageId.ABOUT -> AboutPage(graph)
        }
    }
}

@Composable
private fun PacksPage(graph: AppGraph, state: AppState) {
    val t = skin
    val s by graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    Toggle("Download on Wi‑Fi only", "Feature packs can be tens of MB", s.wifiOnlyDownloads) { v -> scope.launch { graph.prefs.setWifiOnly(v) } }
    Text("Used on this phone: ${mb(graph.packs.sizeOnDisk())}", style = AppType.cap, color = t.muted)
    graph.packs.catalog.packs.forEach { spec ->
        val st by remember(spec.id, tick) { graph.packs.observe(spec.id) }.collectAsState(graph.packs.state(spec.id))
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.purpose, style = AppType.b1, color = t.text, modifier = Modifier.weight(1f))
                    when {
                        spec.platformManaged -> StatusBadge(BadgeKind.NEUTRAL, "Managed by Android")
                        st is PackState.Ready -> StatusBadge(BadgeKind.OK, "Ready")
                        st is PackState.Downloading || st == PackState.Verifying -> StatusBadge(BadgeKind.INFO, "Downloading")
                        st is PackState.Failed -> StatusBadge(BadgeKind.ERR, "Failed")
                        else -> StatusBadge(BadgeKind.NEUTRAL, "Not downloaded")
                    }
                }
                Text("${spec.provider} · v${spec.version} · ${mb(spec.sizeBytes)} · ${spec.license}", style = AppType.cap, color = t.muted)
                if (!spec.platformManaged) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (st) {
                        is PackState.Ready -> AppButton("Delete", { graph.packs.delete(spec.id); tick++ }, variant = ButtonVariant.DANGER_TONAL)
                        is PackState.Downloading, PackState.Verifying -> AppButton("Cancel", { graph.packs.cancel(spec.id) }, variant = ButtonVariant.GHOST)
                        is PackState.Unavailable -> {}
                        else -> AppButton("Download", { graph.packs.download(spec.id, s.wifiOnlyDownloads) }, variant = ButtonVariant.TONAL, icon = AppIcons.download)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptsPage(graph: AppGraph) {
    val t = skin
    val s by graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val rules by produceState(emptyList<MerchantRuleRow>(), version) { value = graph.records.rules() }
    val p = graph.prefs
    Toggle("Keep receipt photos", "Stored privately on this phone so you can check them later", s.keepPhotos) { v -> scope.launch { p.setKeepPhotos(v) } }
    Choice("Delete photos after", listOf("0" to "Never", "30" to "30 days", "90" to "90 days", "365" to "1 year"), s.autoDeleteDays.toString()) { v -> scope.launch { p.setAutoDeleteDays(v.toInt()) } }
    Toggle("Enhance by default", "Flatten shadows and boost contrast before reading", s.enhanceByDefault) { v -> scope.launch { p.setEnhance(v) } }
    Toggle("Auto-capture", "Take the photo when the receipt is steady and in focus", s.autoCapture) { v -> scope.launch { p.setAutoCapture(v) } }
    Choice("Default category for manual expenses", Categories.builtIn.map { it.id to it.name }, s.defaultCategory) { v -> scope.launch { p.setDefaultCategory(v) } }
    Section("Learned from your edits")
    if (rules.isEmpty()) Text("When you correct a store name or category, it's remembered here for next time. Past receipts are never changed.", style = AppType.cap, color = t.muted)
    rules.forEach { r ->
        ListRow(r.displayName ?: r.merchantKey, meta = listOfNotNull(if (r.displayName != null) "Read as \"${r.merchantKey}\"" else null, r.categoryId?.let { "→ ${Categories.name(it)}" }).joinToString(" · "), compact = true)
    }
}

@Composable
private fun NotificationsPage(graph: AppGraph) {
    val t = skin
    val context = LocalContext.current
    val s by graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var allowed by remember { mutableStateOf(graph.reminders.notificationsAllowed()) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it; scope.launch { graph.prefs.setNotificationsAsked() } }
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", style = AppType.b1, color = t.text, modifier = Modifier.weight(1f))
            StatusBadge(if (allowed) BadgeKind.OK else BadgeKind.WARN, if (allowed) "Allowed" else "Off")
        }
    }
    if (!allowed) {
        if (Build.VERSION.SDK_INT >= 33 && !s.notificationsAsked) AppButton("Allow notifications", { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, fill = true)
        else AppButton("Open system settings", {
            context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, fill = true, variant = ButtonVariant.TONAL)
    }
    Toggle("Budget alerts", "Once when a budget reaches its warning level and once when it's exceeded", s.budgetAlerts) { v -> scope.launch { graph.prefs.setBudgetAlerts(v) } }
    Section("Channels")
    com.receiptscan.app.ReminderChannel.entries.forEach { c -> ListRow(c.title, meta = c.description, compact = true) }
    Text("Turn individual channels on or off in Android's notification settings.", style = AppType.cap, color = t.muted)
}

@Composable
private fun DataPage(graph: AppGraph, state: AppState) {
    val t = skin
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    val sizes by produceState(Triple(0L, 0L, 0L)) {
        value = withContext(Dispatchers.IO) {
            Triple(graph.images.totalBytes(), DocumentsFeature.outputDir(context).walk().filter { it.isFile }.sumOf { it.length() }, graph.packs.sizeOnDisk())
        }
    }
    Banner(BadgeKind.INFO, "Everything stays on this phone", "No account, no cloud sync, no backup to Google. Uninstalling the app deletes your data.")
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row { Text("Receipt photos", style = AppType.b2, color = t.muted, modifier = Modifier.weight(1f)); Text(mb(sizes.first), style = AppType.num, color = t.text) }
            Row { Text("PDF documents", style = AppType.b2, color = t.muted, modifier = Modifier.weight(1f)); Text(mb(sizes.second), style = AppType.num, color = t.text) }
            Row { Text("Feature packs", style = AppType.b2, color = t.muted, modifier = Modifier.weight(1f)); Text(mb(sizes.third), style = AppType.num, color = t.text) }
        }
    }
    AppButton("Export expenses (CSV)", { state.exportRequest = ExportRequest(title = "Export expenses") }, fill = true, variant = ButtonVariant.TONAL, icon = AppIcons.download)
    AppButton("Delete everything", { confirm = true; typed = "" }, fill = true, variant = ButtonVariant.DANGER_TONAL, icon = AppIcons.trash)
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete everything?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("All receipts, expenses, bills, documents, photos, reminders and settings are removed from this phone. This can't be undone. Feature packs are kept.")
                AppTextField(typed, { typed = it }, "Type DELETE to confirm")
            }
        },
        confirmButton = {
            TextButton({
                confirm = false
                scope.launch {
                    WorkManager.getInstance(context).cancelAllWorkByTag("reminder")
                    graph.records.deleteEverything()
                    withContext(Dispatchers.IO) {
                        graph.images.deleteAll()
                        DocumentsFeature.outputDir(context).deleteRecursively()
                        context.cacheDir.resolve("exports").deleteRecursively()
                    }
                    graph.prefs.clear()
                    graph.refreshLearnedRules()
                    state.home()
                    state.toast("Everything was deleted")
                }
            }, enabled = typed.trim() == "DELETE") { Text("Delete everything") }
        },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

/** SHA-256 of the certificate this APK is signed with, so people can verify which key signed their copy. */
private fun signingSha256(context: Context): String? = runCatching {
    val pm = context.packageManager
    val certs = if (Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
    }
    val bytes = certs?.firstOrNull()?.toByteArray() ?: return null
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
}.getOrNull()

private const val TEST_KEY_SHA256 = "5C:B1:6D:D7:B7:02:90:18:C1:53:27:2F:35:35:DA:F0:F8:17:6B:26:39:8A:79:AD:03:2F:01:77:46:7C:2C:B8"

@Composable
private fun AboutPage(graph: AppGraph) {
    val t = skin
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    val sha = remember { signingSha256(context) }
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Receipt & Scan", style = AppType.h2, color = t.text)
            Text("Version $version", style = AppType.b2, color = t.muted)
            Text("Works offline. Text recognition, barcodes, translation, speech and models run on this phone.", style = AppType.cap, color = t.muted)
        }
    }
    Section("Signing key")
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (sha) {
                TEST_KEY_SHA256 -> StatusBadge(BadgeKind.WARN, "Test release key (sideload only)")
                null -> StatusBadge(BadgeKind.NEUTRAL, "Unknown")
                else -> StatusBadge(BadgeKind.OK, "Release key")
            }
            Text("SHA-256", style = AppType.lab, color = t.muted)
            Text(sha ?: "Couldn't read the certificate", style = AppType.cap.copy(fontFamily = FontFamily.Monospace), color = t.text)
            if (sha == TEST_KEY_SHA256) Text("This copy is signed with the project's test key. Updates from the Play Store version won't install over it.", style = AppType.cap, color = t.muted)
        }
    }
    Section("Models and data")
    listOf(
        "Google ML Kit Text Recognition v2 (Latin) — on-device OCR, Google ML Kit terms",
        "Google ML Kit Barcode Scanning — QR and barcodes, Google ML Kit terms",
        "Google ML Kit Translation and Language ID — downloaded language models",
        "Google ML Kit Document Scanner is not used; capture and crop are built in",
        "EfficientDet-Lite0 (MediaPipe) — object counting, Apache 2.0, downloaded feature pack",
        "AIY Vision Classifier Plants V1 — plant suggestions, Apache 2.0",
        "AIY Vision Classifier Food V1 — food suggestions, Apache 2.0",
        "USDA FoodData Central SR Legacy — nutrition reference values, public domain (CC0)",
        "Android on-device SpeechRecognizer — voice commands (Android 12+)",
    ).forEach { Text("• $it", style = AppType.cap, color = t.text) }
    Text("Exact versions, file hashes and sources are listed in docs/MODEL_AND_DATA_REGISTER.md in the project.", style = AppType.cap, color = t.muted)
}

/** First run: what the app does, how counting works, and two preferences. */
@Composable
fun OnboardingScreen(graph: AppGraph, state: AppState) {
    val t = skin
    val s by graph.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    val pages: List<Triple<ImageVector, String, String>> = listOf(
        Triple(AppIcons.receipt, "Receipts into expenses", "Scan or import a receipt. We read the store, items, tax and total, and you check them before anything counts."),
        Triple(AppIcons.checkCircle, "Counted once", "Receipts wait in Needs review until you confirm. Voice, manual entries and bill payments join the same list, so nothing is added twice."),
        Triple(AppIcons.shield, "Private by design", "Everything is read and stored on this phone. No account and no cloud. 14 tools: bills, PDFs, QR, text, translation and more."),
    )
    fun finish() {
        scope.launch {
            graph.prefs.setOnboarded()
            if (state.top == Screen.Onboarding) state.pop()
        }
    }
    ScreenSurface {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row { Spacer(Modifier.weight(1f)); if (step < 3) TextButton(::finish) { Text("Skip") } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                if (step < 3) {
                    val (icon, title, body) = pages[step]
                    com.receiptscan.design.ModuleTile(com.receiptscan.design.ModuleColor.RECEIPT, icon, size = 88.dp)
                    Text(title, style = AppType.h1, color = t.text)
                    Text(body, style = AppType.b1, color = t.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else {
                    Text("Two quick choices", style = AppType.h1, color = t.text)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Your currency", Currencies.all.map { it.code to it.code }, s.currency) { v -> scope.launch { graph.prefs.setCurrency(v) } }
                        Choice("Printed dates like 03/04 mean", listOf("DMY" to "3 April", "MDY" to "March 4"), s.dateOrder) { v -> scope.launch { graph.prefs.setDateOrder(v) } }
                        Text("You can change these in Settings.", style = AppType.cap, color = t.muted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
                repeat(4) { i -> Text(if (i == step) "●" else "○", color = if (i == step) t.primary else t.muted, modifier = Modifier.padding(4.dp)) }
            }
            AppButton(if (step < 3) "Next" else "Get started", { if (step < 3) step++ else finish() }, fill = true)
            if (step > 0) AppButton("Back", { step-- }, variant = ButtonVariant.GHOST, fill = true)
            Spacer(Modifier.height(8.dp))
        }
    }
}
