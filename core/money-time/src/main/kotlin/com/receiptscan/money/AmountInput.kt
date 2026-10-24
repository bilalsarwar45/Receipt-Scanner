package com.receiptscan.money

import java.math.BigDecimal

/**
 * Validation for an amount the person types into a field (handover section 7):
 * digits, one decimal point, at most the currency's fraction digits, and at most 99,999,999.99.
 * Grouping commas are allowed and ignored. Validation runs on blur/save, never per keystroke.
 */
object AmountInput {
    sealed interface Result {
        data class Valid(val money: Money) : Result
        data class Error(val message: String) : Result
    }

    fun validate(text: String, currency: CurrencySpec, allowZero: Boolean = false): Result {
        val example = if (currency.fractionDigits == 0) "550" else "550." + "0".repeat(currency.fractionDigits)
        val err = Result.Error("Enter an amount like $example")
        val cleaned = AmountParser.normalizeDigits(text).trim().replace(",", "")
        if (cleaned.isEmpty()) return err
        if (!Regex("""^\d+(\.\d*)?$""").matches(cleaned)) return err
        val parts = cleaned.split('.')
        if (parts.size == 2 && parts[1].length > currency.fractionDigits) return err
        val value = BigDecimal(cleaned.trimEnd('.'))
        if (value > Money.MAX_MAJOR) return Result.Error("Amount is too large")
        if (!allowZero && value.signum() == 0) return Result.Error("Must be more than 0")
        return Result.Valid(Money.fromDecimal(value, currency))
    }
}
