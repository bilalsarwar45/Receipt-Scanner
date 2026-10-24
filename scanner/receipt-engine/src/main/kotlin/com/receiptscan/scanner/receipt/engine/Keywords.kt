package com.receiptscan.scanner.receipt.engine

/**
 * Locale keyword dictionaries for receipt rows. English first, with common Pakistani receipt terms
 * and Urdu/Roman Urdu words. Matching runs on [com.receiptscan.ocr.model.TextNormalizer.key] output.
 * Order matters: more specific patterns are checked first.
 */
internal object Keywords {
    private fun rx(vararg words: String) = Regex("(?<![\\p{L}])(" + words.joinToString("|") + ")(?![\\p{L}])")

    val ignored = rx(
        "total qty", "total quantity", "total items?", "no of items?", "items? count", "item count", "qty total",
        "ntn", "strn", "gst no", "gst reg", "sales tax reg", "tax reg(?:istration)?", "pos id", "terminal", "tel", "phone", "ph",
        "cashier", "counter", "table", "server", "guests?", "covers?", "thank you", "thanks", "visit again", "www", "fbr", "pra",
        "points? (?:earned|balance)", "loyalty", "barcode", "batch",
    )
    val change = rx("change", "change due", "balance returned", "returned", "baqaya", "باقی")
    val tendered = rx(
        "cash", "cash tendered", "tendered", "amount tendered", "amount received", "received", "paid", "amount paid",
        "card", "visa", "mastercard", "master card", "debit card", "credit card", "jazzcash", "easypaisa", "raast", "upi",
    )
    val rounding = rx("round(?:ing)?(?: off)?", "rounded")
    val subtotal = rx("sub ?total", "subtotal", "sub tot", "gross(?: amount| total)?", "total before tax", "amount before tax")
    val grandTotal = rx("grand total", "net total", "net amount", "net payable", "total payable", "amount payable", "payable", "amount due", "total due", "balance due", "bill amount", "total amount", "invoice total", "kul raqam", "کل رقم")
    val total = rx("total", "tot", "ttl", "kul", "کل", "ٹوٹل", "amount")
    val tax = rx("tax", "gst", "vat", "s ?tax", "sales tax", "fed", "pst", "ist", "service tax", "excise", "withholding", "wht", "ٹیکس")
    val taxIncluded = rx("incl", "inclusive", "included", "includes", "inc")
    val service = rx("service charges?", "service", "svc", "tip", "tips", "gratuity", "delivery(?: charges?| fee)?")
    val discount = rx("discount", "disc", "savings?", "you saved", "promo", "promotion", "coupon", "voucher", "off", "less", "rebate", "رعایت")
    val headerNoise = rx("receipt", "invoice", "tax invoice", "sales invoice", "bill", "welcome", "customer copy", "duplicate", "original", "address", "road", "street", "st", "block", "plot", "shop", "mall", "branch", "lahore", "karachi", "islamabad", "rawalpindi", "pakistan", "date", "time")
    val reference = Regex("(?i)(?:invoice|inv|receipt|bill|order|txn|transaction|ref|slip)\\s*(?:no|number|#|id)?\\s*[:#.]?\\s*([A-Z0-9][A-Z0-9-/]{2,})")
    val paymentMethod = Regex("(?i)\\b(cash|visa|mastercard|master card|debit card|credit card|card|jazzcash|easypaisa|raast|upi|amex)\\b")
}
