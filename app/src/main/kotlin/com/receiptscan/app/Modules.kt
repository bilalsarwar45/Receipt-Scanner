package com.receiptscan.app

import com.receiptscan.feature.documents.DocumentsFeature
import com.receiptscan.scanner.counter.CounterScanner
import com.receiptscan.scanner.expiry.ExpiryScanner
import com.receiptscan.scanner.food.FoodScanner
import com.receiptscan.scanner.invoice.BillScanner
import com.receiptscan.scanner.math.MathScanner
import com.receiptscan.scanner.nutrition.NutritionScanner
import com.receiptscan.scanner.plant.PlantScanner
import com.receiptscan.scanner.product.ProductScanner
import com.receiptscan.scanner.qr.QrScanner
import com.receiptscan.scanner.receipt.ReceiptScanner
import com.receiptscan.scanner.text.TextScanner
import com.receiptscan.scanner.translation.TranslationScanner
import com.receiptscan.scanner.ui.ModuleInfo
import com.receiptscan.scanner.ui.ScannerModule
import com.receiptscan.scanner.wifi.WifiScanner

/** The 14 tools in the three Tools groups (handover section 2). PDF is a feature, not an embeddable module. */
object Modules {
    val embeddable: List<ScannerModule<*>> = listOf(
        ReceiptScanner, BillScanner, TextScanner, ProductScanner, NutritionScanner, ExpiryScanner, FoodScanner,
        QrScanner, TranslationScanner, MathScanner, CounterScanner, PlantScanner, WifiScanner,
    )

    fun byId(id: String): ScannerModule<*>? = embeddable.firstOrNull { it.id.value == id }

    /** Info for every tool including PDF, by id. */
    fun info(id: String): ModuleInfo? = if (id == DocumentsFeature.ID.value) DocumentsFeature.info else byId(id)?.info

    val groups: List<Pair<String, List<String>>> = listOf(
        "Receipts & documents" to listOf("receipt", "invoice", "pdf", "text"),
        "Products & food" to listOf("product", "nutrition", "expiry", "food"),
        "Everyday tools" to listOf("qr", "translate", "math", "counter", "plant", "wifi"),
    )

    val allIds: List<String> get() = groups.flatMap { it.second }
}
