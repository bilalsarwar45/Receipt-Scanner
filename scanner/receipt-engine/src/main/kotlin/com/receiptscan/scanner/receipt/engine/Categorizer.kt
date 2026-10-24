package com.receiptscan.scanner.receipt.engine

import com.receiptscan.ocr.model.TextNormalizer

/**
 * Deterministic local categorisation: learned aliases first (the person's own corrections), then
 * merchant rules, then item keywords. Returns category ids; null means "Uncategorized".
 * Results are suggestions; they count only after the person confirms the receipt.
 * It never recategorises saved history by itself.
 */
class Categorizer(
    /** Normalized merchant key → category id, learned from corrections. */
    private val merchantRules: Map<String, String> = emptyMap(),
    /** Normalized item key → category id, learned from corrections. */
    private val itemRules: Map<String, String> = emptyMap(),
) {
    fun categorizeItem(description: String): String? {
        val key = TextNormalizer.key(description)
        if (key.isEmpty()) return null
        itemRules[key]?.let { return it }
        val words = key.split(' ').toSet()
        for ((category, keywords) in ITEM_KEYWORDS) {
            if (keywords.any { kw -> if (' ' in kw) key.contains(kw) else kw in words }) return category
        }
        return null
    }

    fun categorizeMerchant(merchant: String?): String? {
        val key = TextNormalizer.key(merchant ?: return null)
        merchantRules[key]?.let { return it }
        for ((category, keywords) in MERCHANT_KEYWORDS) {
            if (keywords.any { key.contains(it) }) return category
        }
        return null
    }

    /** Merchant rule wins; otherwise the category with the largest item spend. */
    fun categorizeReceipt(merchant: String?, items: List<ExtractedItem>): String? {
        categorizeMerchant(merchant)?.let { return it }
        val spend = items.mapNotNull { i -> (i.suggestedCategory ?: categorizeItem(i.description.value))?.let { it to i.lineTotal.value.minor } }
        return spend.groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }.maxByOrNull { it.value }?.key
    }

    companion object {
        val ITEM_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            "fuel" to listOf("petrol", "diesel", "fuel", "hi octane", "octane", "cng", "lpg"),
            "groceries" to listOf(
                "milk", "bread", "rice", "eggs", "egg", "oil", "cooking oil", "ghee", "flour", "atta", "sugar", "tea", "salt",
                "apples", "apple", "banana", "bananas", "onion", "onions", "tomato", "tomatoes", "potato", "potatoes", "chicken",
                "mutton", "beef", "fish", "yogurt", "dahi", "butter", "cheese", "daal", "dal", "lentils", "spices", "vegetables",
                "fruit", "fruits", "biscuits", "juice", "water", "cereal", "pasta", "noodles", "honey", "jam",
            ),
            "dining" to listOf("burger", "pizza", "coffee", "latte", "cappuccino", "sandwich", "biryani", "karahi", "tikka", "fries", "shake", "meal", "combo", "naan", "roti", "paratha"),
            "health" to listOf("tablet", "tablets", "capsule", "capsules", "syrup", "panadol", "paracetamol", "medicine", "vitamin", "bandage", "mask", "injection", "mg"),
            "personal-care" to listOf("shampoo", "soap", "toothpaste", "toothbrush", "lotion", "deodorant", "razor", "conditioner", "facewash", "face wash", "sanitizer", "tissue", "tissues"),
            "household" to listOf("detergent", "bleach", "surf", "dishwash", "cleaner", "broom", "mop", "bulb", "battery", "batteries", "garbage bags"),
            "electronics" to listOf("headphones", "earbuds", "charger", "cable", "usb", "adapter", "mouse", "keyboard", "phone", "laptop", "power bank", "speaker"),
            "education" to listOf("notebook", "pen", "pencil", "book", "books", "stationery", "register", "eraser"),
        )
        val MERCHANT_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            "fuel" to listOf("pso", "shell", "total parco", "attock", "petroleum", "filling station", "fuel", "byco", "hascol", "go petrol"),
            "groceries" to listOf("mart", "grocery", "supermarket", "super store", "superstore", "imtiaz", "carrefour", "metro cash", "al fatah", "chase up", "naheed", "bakery", "bake"),
            "dining" to listOf("cafe", "café", "restaurant", "kitchen", "pizza", "burger", "coffee", "grill", "dhaba", "tikka", "foods", "kfc", "mcdonald"),
            "health" to listOf("pharmacy", "chemist", "medical", "clinic", "hospital", "lab", "dawakhana"),
            "electronics" to listOf("tech", "electronics", "mobile", "computer"),
            "utilities" to listOf("electric", "power", "lesco", "k electric", "kelectric", "iesco", "sui gas", "ssgc", "sngpl", "water board", "ptcl", "internet", "nayatel", "stormfiber"),
        ).map { (c, ws) -> c to ws.map { TextNormalizer.key(it) } }
    }
}
