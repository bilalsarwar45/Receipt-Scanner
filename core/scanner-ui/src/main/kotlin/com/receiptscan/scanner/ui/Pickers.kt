package com.receiptscan.scanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.receiptscan.design.AppIcons
import com.receiptscan.design.AppTextField
import com.receiptscan.design.FieldState
import com.receiptscan.time.DisplayDates
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Read-only field that opens the Material date picker. Dates are handled in UTC inside the picker. */
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    helper: String? = null,
    state: FieldState = FieldState.DEFAULT,
    badge: (@Composable () -> Unit)? = null,
    placeholder: String = "Pick a date",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        AppTextField(
            value = date?.let { DisplayDates.format(it) } ?: "",
            onValueChange = {},
            label = label,
            helper = helper,
            state = state,
            placeholder = placeholder,
            badge = badge,
            trailing = { androidx.compose.material3.Icon(AppIcons.calendar, null) },
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    pickerState.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } },
        ) { DatePicker(pickerState) }
    }
}

@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ onPick(LocalTime.of(state.hour, state.minute)) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}
