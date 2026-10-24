package com.receiptscan.money

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formats amounts as "PKR 3,800.00": ISO code, grouping, and exactly the currency's fraction digits.
 * Uses tabular-friendly Western digits regardless of device locale, matching the design handover.
 */
object MoneyFormat {
    fun format(money: Money, withCode: Boolean = true): String {
        val number = formatNumber(money.toDecimal(), money.currency.fractionDigits)
        return if (withCode) "${money.currency.code} $number" else number
    }

    fun formatNumber(value: BigDecimal, fractionDigits: Int): String {
        val pattern = if (fractionDigits == 0) "#,##0" else "#,##0." + "0".repeat(fractionDigits)
        val df = DecimalFormat(pattern, DecimalFormatSymbols(Locale.US))
        df.isParseBigDecimal = true
        df.roundingMode = java.math.RoundingMode.UNNECESSARY
        return df.format(value)
    }

    /** "PKR 300" style used in short confirmations; drops a zero fraction. */
    fun formatShort(money: Money): String {
        val d = money.toDecimal()
        val text = if (d.stripTrailingZeros().scale() <= 0) formatNumber(d, 0) else formatNumber(d, money.currency.fractionDigits)
        return "${money.currency.code} $text"
    }
}
