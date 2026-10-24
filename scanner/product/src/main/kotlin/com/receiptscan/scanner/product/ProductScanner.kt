package com.receiptscan.scanner.product

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.receiptscan.barcode.BarcodeReader
import com.receiptscan.barcode.CodeSet
import com.receiptscan.design.AppButton
import com.receiptscan.design.AppCard
import com.receiptscan.design.AppChip
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.BadgeKind
import com.receiptscan.design.ButtonVariant
import com.receiptscan.design.ModuleColor
import com.receiptscan.design.SourceBadge
import com.receiptscan.design.ValueLine
import com.receiptscan.design.skin
import com.receiptscan.scanner.contracts.CapabilityId
import com.receiptscan.scanner.contracts.ScanEnvelope
import com.receiptscan.scanner.contracts.ScanOutcome
import com.receiptscan.scanner.contracts.ScanSource
import com.receiptscan.scanner.contracts.Value
import com.receiptscan.scanner.contracts.ValueSource
import com.receiptscan.scanner.product.engine.ProductLabelParser
import com.receiptscan.scanner.product.engine.ProductLabelResult
import com.receiptscan.scanner.product.engine.WarrantyTerms
import com.receiptscan.scanner.ui.Banner
import com.receiptscan.scanner.ui.DateField
import com.receiptscan.scanner.ui.ModuleHeader
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.OcrScanFlow
import com.receiptscan.scanner.ui.ResultAction
import com.receiptscan.scanner.ui.ResultScaffold
import com.receiptscan.scanner.ui.ScanInput
import com.receiptscan.scanner.ui.ScannerHost
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.ui.SourcePhoto
import com.receiptscan.time.DisplayDates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.LocalTime
import java.time.Period

/** Warranty record the host can store and link to a receipt (action id "product.warranty"). */
@Serializable
data class WarrantyPayload(
    val productName: String,
    val model: String?,
    val serial: String?,
    val barcode: String?,
    val purchaseDate: String?,
    val returnBy: String?,
    val warrantyUntil: String?,
    val termsNote: String?,
    val termsSource: String,
)

data class ProductScan(val label: ProductLabelResult?, val barcode: String?, val image: Bitmap?, val sourceIds: List<String>)

/** Product and warranty (S-Product, A-Warranty). Warranty terms are never invented: they come from the label or the person. */
object ProductScanner : ScannerModule<ProductScan> {
    val ID = CapabilityId("product")
    const val ACTION_WARRANTY = "product.warranty"
    override val info = ModuleInfo(ID, "Warranty", "Product labels, serials and warranties", ModuleColor.PRODUCT, AppIcons.product,
        listOf("Scan the sticker with model and serial", "Link it to a receipt for warranty"))

    @Composable
    override fun Capture(input: ScanInput, host: ScannerHost, onResult: (ScanOutcome<ProductScan>) -> Unit, onCancel: () -> Unit) {
        val src = input.source
        if (src is ScanSource.Text) { LaunchedEffect(src) { onResult(ScanOutcome.Success(ProductScan(null, src.text, null, emptyList()))) }; return }
        OcrScanFlow(info, input, host, noun = "label", onCancel = onCancel, onFailed = onResult, onDocument = { cap ->
            val label = ProductLabelParser.parse(cap.document)
            onResult(ScanOutcome.Success(ProductScan(label, null, cap.images.firstOrNull(), cap.sourceIds)))
        })
    }

    @Composable
    override fun Result(result: ProductScan, input: ScanInput, host: ScannerHost, onAction: (ResultAction) -> Unit, onBack: () -> Unit) {
        val t = skin
        val l = result.label
        var barcodeFromImage by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(result.image) {
            val img = result.image ?: return@LaunchedEffect
            if (l?.barcode == null) barcodeFromImage = runCatching { BarcodeReader(CodeSet.PRODUCT).let { r -> r.read(img).also { r.close() } } }.getOrNull()?.firstOrNull { it.isProductCode || !it.isQr }?.raw
        }
        var name by remember { mutableStateOf(l?.brandOrName?.value.orEmpty()) }
        var model by remember { mutableStateOf(l?.model?.value.orEmpty()) }
        var serial by remember { mutableStateOf(l?.serial?.value.orEmpty()) }
        var code by remember { mutableStateOf(l?.barcode?.value ?: result.barcode.orEmpty()) }
        LaunchedEffect(barcodeFromImage) { if (code.isBlank() && barcodeFromImage != null) code = barcodeFromImage!! }
        var purchase by remember { mutableStateOf<LocalDate?>(null) }
        var warrantyMonths by remember { mutableStateOf(l?.warrantyPeriod?.toTotalMonths()?.toString() ?: "") }
        var returnDays by remember { mutableStateOf(l?.returnPeriod?.days?.takeIf { it > 0 }?.toString() ?: "") }
        val termsFromLabel = l?.warrantyPeriod != null
        val terms = purchase?.let { WarrantyTerms(it, warrantyMonths.toIntOrNull()?.let { m -> Period.ofMonths(m) }, returnDays.toIntOrNull()?.let { d -> Period.ofDays(d) }) }
        fun src(v: Value<String>?, now: String): ValueSource = if (v != null && v.value == now) v.source else ValueSource.EDITED
        ResultScaffold(
            info, "Product label", onBack,
            bottom = {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (host.capabilities.canSetReminders && terms?.returnBy != null) AppButton("Remind", {
                        val rb = terms.returnBy!!
                        onAction(ResultAction.Remind("Return window ends ${DisplayDates.format(rb)}", "${name.ifBlank { "Product" }}. 3 days left.", rb.minusDays(3).atTime(LocalTime.of(10, 0)), "warranty"))
                    }, Modifier.weight(1f), variant = ButtonVariant.TONAL, icon = AppIcons.bell, fill = true)
                    AppButton("Add warranty", {
                        val p = WarrantyPayload(name.ifBlank { "Product" }, model.ifBlank { null }, serial.ifBlank { null }, code.ifBlank { null }, purchase?.toString(),
                            terms?.returnBy?.toString(), terms?.warrantyUntil?.toString(), l?.warrantyText?.value, if (termsFromLabel) "label" else "entered")
                        val json = Json.encodeToString(WarrantyPayload.serializer(), p)
                        onAction(ResultAction.Custom(ACTION_WARRANTY, json))
                        if (host.capabilities.canSave) onAction(ResultAction.Save(ScanEnvelope(ID, 1, p.productName, listOfNotNull(p.model, p.serial).joinToString(" · "), json, result.sourceIds)))
                    }, Modifier.weight(1f), enabled = name.isNotBlank(), disabledReason = if (name.isBlank()) "Add the product name" else null, fill = true)
                }
            },
        ) {
            ModuleHeader(info, name.ifBlank { "Product" }, "Values below were read from the label unless marked")
            SourcePhoto(result.image)
            AppTextField(name, { name = it.take(80) }, "Product", badge = { SourceBadge(src(l?.brandOrName, name), enteredLabel = "Entered by you") })
            Spacer(Modifier.height(8.dp))
            AppTextField(model, { model = it.take(60) }, "Model", badge = { SourceBadge(src(l?.model, model), enteredLabel = "Entered by you") })
            Spacer(Modifier.height(8.dp))
            AppTextField(serial, { serial = it.take(60) }, "Serial number", badge = { SourceBadge(src(l?.serial, serial), enteredLabel = "Entered by you") })
            Spacer(Modifier.height(8.dp))
            AppTextField(code, { code = it.filter { c -> c.isLetterOrDigit() }.take(20) }, "Barcode", keyboardType = KeyboardType.Number,
                badge = { SourceBadge(if (code.isNotBlank() && (code == l?.barcode?.value || code == result.barcode || code == barcodeFromImage)) ValueSource.FROM_PHOTO else ValueSource.EDITED, enteredLabel = "Entered by you") })
            Spacer(Modifier.height(16.dp))
            Text("Warranty and return", style = AppType.h2, color = t.text)
            if (l?.warrantyText != null) ValueLine("Printed on label", l.warrantyText!!.value, ValueSource.FROM_PHOTO)
            else Banner(BadgeKind.INFO, "No warranty printed on this label", "Enter the terms from your receipt or warranty card. We never guess them.")
            DateField("Purchase date", purchase, { purchase = it }, badge = { SourceBadge(ValueSource.EDITED, enteredLabel = "Entered by you") }, helper = "From your receipt")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(warrantyMonths, { warrantyMonths = it.filter(Char::isDigit).take(3) }, "Warranty (months)", Modifier.weight(1f), keyboardType = KeyboardType.Number)
                AppTextField(returnDays, { returnDays = it.filter(Char::isDigit).take(3) }, "Return window (days)", Modifier.weight(1f), keyboardType = KeyboardType.Number)
            }
            if (terms != null) {
                Spacer(Modifier.height(8.dp))
                AppCard(Modifier.fillMaxWidth()) {
                    Column {
                        terms.returnBy?.let { ValueLine("Return by", DisplayDates.format(it), ValueSource.CALCULATED) }
                        terms.warrantyUntil?.let { ValueLine("Warranty until", DisplayDates.format(it), ValueSource.CALCULATED) }
                        Text("Calculated from the purchase date and the terms above.", style = AppType.cap, color = t.muted)
                    }
                }
            }
        }
    }
}
