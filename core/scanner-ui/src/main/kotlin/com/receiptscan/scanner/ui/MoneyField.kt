package com.receiptscan.scanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import com.receiptscan.design.AppTextField
import com.receiptscan.design.AppType
import com.receiptscan.design.FieldState
import com.receiptscan.money.AmountInput
import com.receiptscan.money.CurrencySpec
import com.receiptscan.money.Money
import com.receiptscan.money.MoneyFormat

/** Plain number text for editing a [Money] value ("3800.00"). */
fun Money.editText(): String = toDecimal().toPlainString()

/**
 * Amount field that validates on blur (and when [forceValidate] is set on save), never while the
 * first characters are typed (handover section 7).
 */
@Composable
fun MoneyField(
    text: String,
    onText: (String) -> Unit,
    label: String,
    currency: CurrencySpec,
    modifier: Modifier = Modifier,
    forceValidate: Boolean = false,
    warn: String? = null,
    badge: (@Composable () -> Unit)? = null,
    allowZero: Boolean = false,
) {
    var touched by remember { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }
    val result = AmountInput.validate(text, currency, allowZero)
    val showError = (touched || forceValidate) && result is AmountInput.Result.Error
    AppTextField(
        value = text,
        onValueChange = onText,
        label = "$label (${currency.code})",
        modifier = modifier.onFocusChanged { f ->
            if (wasFocused && !f.hasFocus) touched = true
            wasFocused = f.hasFocus
        },
        keyboardType = KeyboardType.Decimal,
        state = when { showError -> FieldState.ERROR; warn != null -> FieldState.WARN; else -> FieldState.DEFAULT },
        helper = when { showError -> (result as AmountInput.Result.Error).message; warn != null -> warn; else -> null },
        badge = badge,
        textStyle = AppType.num.copy(fontSize = AppType.b1.fontSize),
    )
}

/** Parses an amount field; null when invalid. */
fun parseMoney(text: String, currency: CurrencySpec, allowZero: Boolean = false): Money? =
    (AmountInput.validate(text, currency, allowZero) as? AmountInput.Result.Valid)?.money

fun Money.display(): String = MoneyFormat.format(this)
