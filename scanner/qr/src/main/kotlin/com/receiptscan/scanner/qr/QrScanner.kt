package com.receiptscan.scanner.qr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.receiptscan.barcode.BarcodeReader
import com.receiptscan.barcode.CodeSet
import com.receiptscan.barcode.DecodedBarcode
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.StatusBadge
import com.receiptscan.design.ValueLine
import com.receiptscan.design.skin
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.EnvelopeEncodable
import com.receiptscan.scanner.contracts.FailReason
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.qr.engine.CodeFormat
import com.receiptscan.scanner.qr.engine.QrPayload
import com.receiptscan.scanner.qr.engine.QrPayloadParser
import com.receiptscan.scanner.qr.engine.RepeatGuard
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.CaptureScreen
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Result payload owned by the QR module. Version 1. */
@Serializable
data class QrScanPayload(val raw: String, val format: String)

data class QrResult(val raw: String, val format: CodeFormat, val payload: QrPayload) : EnvelopeEncodable {
    override fun toEnvelope(): ScanEnvelope = ScanEnvelope(
        capabilityId = QrScanner.ID,
        payloadVersion = 1,
        title = title(),
        summary = raw.take(200),
        payloadJson = Json.encodeToString(QrScanPayload.serializer(), QrScanPayload(raw, format.name)),
    )

    fun title(): String = when (payload) {
        is QrPayload.Link -> "Link"
        is QrPayload.Wifi -> "Wi-Fi: ${payload.ssid}"
        is QrPayload.Email -> "Email address"
        is QrPayload.Phone -> "Phone number"
        is QrPayload.Sms -> "Text message"
        is QrPayload.Geo -> "Location"
        is QrPayload.Contact -> payload.name ?: "Contact"
        is QrPayload.Event -> payload.summary ?: "Event"
        is QrPayload.ProductCode -> "Product code ${payload.code}"
        is QrPayload.Text -> "Text"
    }

    companion object {
        fun decode(json: String): QrResult {
            val p = Json.decodeFromString(QrScanPayload.serializer(), json)
            val f = runCatching { CodeFormat.valueOf(p.format) }.getOrDefault(CodeFormat.UNKNOWN)
            return QrResult(p.raw, f, QrPayloadParser.parse(p.raw, f))
        }
    }
}

fun DecodedBarcode.codeFormat(): CodeFormat = when (format) {
    "QR" -> CodeFormat.QR_CODE
    "EAN-13" -> CodeFormat.EAN_13
    "EAN-8" -> CodeFormat.EAN_8
    "UPC-A" -> CodeFormat.UPC_A
    "UPC-E" -> CodeFormat.UPC_E
    "Code 128" -> CodeFormat.CODE_128
    "Code 39" -> CodeFormat.CODE_39
    "Code 93" -> CodeFormat.CODE_93
    "ITF" -> CodeFormat.ITF
    "Codabar" -> CodeFormat.CODABAR
    "Data Matrix" -> CodeFormat.DATA_MATRIX
    "PDF417" -> CodeFormat.PDF417
    "Aztec" -> CodeFormat.AZTEC
    else -> CodeFormat.UNKNOWN
}

/** QR and barcode scanner (S-QR). Links are never opened automatically. */
object QrScanner : ScannerModule<QrResult> {
    val ID = CapabilityId("qr")

    override val info = ModuleInfo(
        id = ID, title = "QR code", subtitle = "QR codes and barcodes", color = ModuleColor.QR, icon = AppIcons.qr,
        tips = listOf("We never open links automatically", "Check the address first"),
    )

    fun resultFor(code: DecodedBarcode): QrResult = QrResult(code.raw, code.codeFormat(), QrPayloadParser.parse(code.raw, code.codeFormat()))

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<QrResult>) -> Unit, onCancel: () -> Unit) {
        val src = input.source
        if (src is ScanSource.Text) {
            LaunchedEffect(src) { onResult(ScanOutcome.Success(QrResult(src.text, CodeFormat.QR_CODE, QrPayloadParser.parse(src.text)))) }
            return
        }
        val scope = rememberCoroutineScope()
        val reader = remember { BarcodeReader(CodeSet.ALL) }
        val guard = remember { RepeatGuard() }
        var delivered by remember { mutableStateOf(false) }
        val deliver by rememberUpdatedState<(QrResult) -> Unit> { r -> if (!delivered) { delivered = true; onResult(ScanOutcome.Success(r)) } }
        DisposableEffect(Unit) { onDispose { reader.close() } }
        var importMessage by remember { mutableStateOf<String?>(null) }
        CaptureScreen(
            info, host, input,
            onCaptured = { bitmaps ->
                // Imported photo or still capture: decode synchronously from the bitmap.
                scope.launch {
                    val first = runCatching { reader.read(bitmaps.first()) }.getOrDefault(emptyList()).firstOrNull()
                    if (first != null) deliver(resultFor(first))
                    else onResult(ScanOutcome.Failed(FailReason.UNSUPPORTED, "No QR code or barcode found in that photo."))
                }
            },
            onCancel = onCancel,
            noun = "code",
            measureGuidance = false,
            hint = importMessage ?: "Point at a QR code or barcode",
            analyzer = { proxy ->
                reader.read(proxy) { codes ->
                    val first = codes.firstOrNull() ?: return@read
                    if (guard.shouldReport(first.raw, System.currentTimeMillis())) deliver(resultFor(first))
                }
            },
        )
    }

    @Composable
    override fun Result(result: QrResult, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        var confirmUrl by remember { mutableStateOf<String?>(null) }
        var revealed by remember { mutableStateOf(false) }
        val t = skin
        ResultScaffold(
            info, "QR code", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppButton("Copy", { onAction(ResultAction.Copy(result.raw)) }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.copy, fill = true)
                    if (host.capabilities.canSave) AppButton("Save", { onAction(ResultAction.Save(result.toEnvelope())) }, Modifier.weight(1f), fill = true)
                }
            },
        ) {
            ModuleHeader(info, result.title(), "${formatLabel(result.format)} · read from the camera")
            Spacer(Modifier.height(8.dp))
            when (val p = result.payload) {
                is QrPayload.Link -> {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column {
                            ValueLine("Address", p.url, ValueSource.FROM_PHOTO)
                            Text("We haven't opened this link.", style = AppType.cap, color = t.muted)
                        }
                    }
                    if (p.safety.needsConfirmation) {
                        Banner(BadgeKind.WARN, "Check this address before opening", p.safety.warnings.joinToString(" · "))
                    } else {
                        Banner(BadgeKind.INFO, "Check the address first", "We can't tell whether a website is safe. Open it only if you expected this link.")
                    }
                    if (host.capabilities.canOpenLinks) {
                        AppButton("Open link", { if (p.safety.needsConfirmation) confirmUrl = p.url else onAction(ResultAction.OpenLink(p.url)) }, variant = ButtonVariant.OUTLINE, icon = AppIcons.external, fill = true)
                    }
                }
                is QrPayload.Wifi -> {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column {
                            ValueLine("Network", p.ssid, ValueSource.FROM_PHOTO)
                            ValueLine("Security", p.security.label, ValueSource.FROM_PHOTO)
                            if (p.password != null) ValueLine("Password", if (revealed) p.password.orEmpty() else p.maskedPassword.orEmpty(), ValueSource.FROM_PHOTO, onClick = { revealed = !revealed })
                            if (p.hidden) Text("Hidden network", style = AppType.cap, color = t.muted)
                        }
                    }
                    if (p.security.name == "OPEN") Banner(BadgeKind.WARN, "Open network", "Anyone nearby can see traffic. Avoid banking on open networks.")
                    AppButton("Connect with Android", { onAction(ResultAction.HandOff(CapabilityId("wifi"), ScanSource.Text(result.raw))) }, icon = AppIcons.wifi, variant = ButtonVariant.OUTLINE, fill = true)
                    Text("Passwords go to Android's Wi-Fi settings and are never stored by this app.", style = AppType.cap, color = t.muted, modifier = Modifier.padding(top = 6.dp))
                }
                is QrPayload.ProductCode -> {
                    AppCard(Modifier.fillMaxWidth()) {
                        Column {
                            ValueLine("Code", p.code, ValueSource.FROM_PHOTO)
                            Row { StatusBadge(if (p.checksumValid) BadgeKind.OK else BadgeKind.WARN, if (p.checksumValid) "Check digit valid" else "Check digit doesn't match") }
                        }
                    }
                    Banner(BadgeKind.INFO, "No product lookup", "A barcode identifies a product but doesn't contain its name or price. We don't look it up online.")
                    AppButton("Use for a warranty record", { onAction(ResultAction.HandOff(CapabilityId("product"), ScanSource.Text(p.code))) }, variant = ButtonVariant.OUTLINE, fill = true)
                }
                is QrPayload.Phone -> ContactLines(listOf("Phone" to p.number)) { onAction(ResultAction.OpenLink("tel:${p.number}")) }
                is QrPayload.Email -> ContactLines(listOfNotNull("Email" to p.address, p.subject?.let { "Subject" to it }, p.body?.let { "Message" to it })) { onAction(ResultAction.OpenLink("mailto:${p.address}")) }
                is QrPayload.Sms -> ContactLines(listOfNotNull("Number" to p.number, p.message?.let { "Message" to it })) { onAction(ResultAction.OpenLink("smsto:${p.number}")) }
                is QrPayload.Geo -> ContactLines(listOfNotNull("Coordinates" to "${p.latitude}, ${p.longitude}", p.query?.let { "Place" to it })) { onAction(ResultAction.OpenLink("geo:${p.latitude},${p.longitude}")) }
                is QrPayload.Contact -> ContactLines(listOfNotNull(p.name?.let { "Name" to it }, p.organization?.let { "Organisation" to it }) + p.phones.map { "Phone" to it } + p.emails.map { "Email" to it }, null)
                is QrPayload.Event -> ContactLines(listOfNotNull(p.summary?.let { "Event" to it }, p.start?.let { "Starts" to it }, p.end?.let { "Ends" to it }, p.location?.let { "Location" to it }), null)
                is QrPayload.Text -> {
                    AppCard(Modifier.fillMaxWidth()) { Text(p.raw, style = AppType.b1, color = t.text) }
                    AppButton("Share", { onAction(ResultAction.Share(p.raw)) }, variant = ButtonVariant.OUTLINE, icon = AppIcons.share, fill = true)
                }
            }
            Spacer(Modifier.height(12.dp))
            AppButton("Scan another", { onAction(ResultAction.ScanAgain) }, variant = ButtonVariant.GHOST, fill = true)
        }
        confirmUrl?.let { url ->
            AlertDialog(
                onDismissRequest = { confirmUrl = null },
                title = { Text("Open this link?") },
                text = { Text("$url\n\nNot secure and looks unusual.") },
                dismissButton = { TextButton({ confirmUrl = null }) { Text("Cancel") } },
                confirmButton = { TextButton({ confirmUrl = null; onAction(ResultAction.OpenLink(url)) }) { Text("Open anyway") } },
            )
        }
    }

    @Composable
    private fun ContactLines(lines: List<Pair<String, String>>, onOpen: (() -> Unit)?) {
        AppCard(Modifier.fillMaxWidth()) {
            Column { lines.forEach { (k, v) -> ValueLine(k, v, ValueSource.FROM_PHOTO) } }
        }
        if (onOpen != null) {
            Spacer(Modifier.height(8.dp))
            AppButton("Open", onOpen, variant = ButtonVariant.OUTLINE, icon = AppIcons.external, fill = true)
        }
    }

    private fun formatLabel(f: CodeFormat) = when (f) {
        CodeFormat.QR_CODE -> "QR code"
        CodeFormat.UNKNOWN -> "Code"
        else -> f.name.replace('_', '-')
    }
}

