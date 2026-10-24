package com.receiptscan.money

/**
 * A currency known to the app. [fractionDigits] is the number of minor-unit digits
 * (PKR 2, JPY 0, KWD 3). Stored amounts are always integer minor units.
 */
data class CurrencySpec(
    val code: String,
    val fractionDigits: Int,
    /** Lower-case words and symbols people write or say for this currency. */
    val aliases: Set<String> = emptySet(),
) {
    init {
        require(code.length == 3 && code.all { it in 'A'..'Z' }) { "Currency code must be ISO 4217: $code" }
        require(fractionDigits in 0..4) { "Unsupported fraction digits $fractionDigits" }
    }

    /** 10^fractionDigits as a Long, e.g. 100 for PKR. */
    val minorPerMajor: Long get() = POW10[fractionDigits]

    override fun toString(): String = code

    private companion object {
        val POW10 = longArrayOf(1, 10, 100, 1_000, 10_000)
    }
}

/**
 * Registry of supported currencies. Unknown ISO codes are rejected rather than guessed,
 * because a wrong fraction-digit count silently corrupts every stored amount.
 */
object Currencies {
    val PKR = CurrencySpec("PKR", 2, setOf("pkr", "rs", "rs.", "rupee", "rupees", "rupay", "rupaye", "rupe", "روپے", "روپیہ", "روپیے", "₨"))
    val INR = CurrencySpec("INR", 2, setOf("inr", "₹"))
    val USD = CurrencySpec("USD", 2, setOf("usd", "$", "us$", "dollar", "dollars"))
    val EUR = CurrencySpec("EUR", 2, setOf("eur", "€", "euro", "euros"))
    val GBP = CurrencySpec("GBP", 2, setOf("gbp", "£", "pound", "pounds"))
    val AED = CurrencySpec("AED", 2, setOf("aed", "dirham", "dirhams", "د.إ"))
    val SAR = CurrencySpec("SAR", 2, setOf("sar", "riyal", "riyals", "ر.س"))
    val JPY = CurrencySpec("JPY", 0, setOf("jpy", "¥", "yen"))
    val KWD = CurrencySpec("KWD", 3, setOf("kwd", "د.ك"))
    val BHD = CurrencySpec("BHD", 3, setOf("bhd"))
    val OMR = CurrencySpec("OMR", 3, setOf("omr"))

    val all: List<CurrencySpec> = listOf(PKR, INR, USD, EUR, GBP, AED, SAR, JPY, KWD, BHD, OMR)

    private val byCode = all.associateBy { it.code }

    fun of(code: String): CurrencySpec =
        byCode[code.uppercase()] ?: throw IllegalArgumentException("Unsupported currency: $code")

    fun find(code: String): CurrencySpec? = byCode[code.uppercase()]

    /**
     * Finds a currency from a word or symbol such as "rs", "rupees" or "$".
     * Ambiguous symbols are resolved only through [preferred], which is usually the user's default currency.
     */
    fun fromAlias(token: String, preferred: CurrencySpec = PKR): CurrencySpec? {
        val t = token.trim().lowercase()
        if (t.isEmpty()) return null
        if (preferred.aliases.contains(t) || preferred.code.equals(t, ignoreCase = true)) return preferred
        byCode[t.uppercase()]?.let { return it }
        return all.firstOrNull { t in it.aliases }
    }
}
