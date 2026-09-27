package id.kaloriku.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.MealType
import java.util.Locale

/**
 * Bottom sheet for editing an already-logged entry: food name, portion, meal,
 * notes and the date/time the entry is logged at. Calories are re-asked from Jev
 * on save when the name or portion changed, so the sheet only reports the current
 * figure and shows progress while it runs. Moving the entry in time is a pure
 * reschedule and never triggers an estimate.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditEntrySheet(
    entry: FoodEntry,
    /** Jev macro decision for this row, when this app session captured one. */
    macroProfile: String?,
    saving: Boolean,
    onSave: (foodName: String, portionText: String, meal: MealType, notes: String, dayKey: String, hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Saveable: rotation must not throw away what the user typed in the sheet.
    var foodName by rememberSaveable(entry.id) { mutableStateOf(entry.foodName) }
    var portionText by rememberSaveable(entry.id) { mutableStateOf(entry.portionText) }
    var notes by rememberSaveable(entry.id) { mutableStateOf(entry.notes.orEmpty()) }
    var meal by rememberSaveable(entry.id) { mutableStateOf(entry.meal) }
    // The picked day is kept as the shared day key and the time as plain ints, so
    // process death restores an unambiguous value without needing a custom saver.
    var dayKey by rememberSaveable(entry.id) { mutableStateOf(entry.dayKey) }
    var hour by rememberSaveable(entry.id) { mutableIntStateOf(JakartaTime.hourOfDay(entry.loggedAt)) }
    var minute by rememberSaveable(entry.id) { mutableIntStateOf(JakartaTime.minuteOfHour(entry.loggedAt)) }

    var showDatePicker by rememberSaveable(entry.id) { mutableStateOf(false) }
    var showTimePicker by rememberSaveable(entry.id) { mutableStateOf(false) }

    val nameChanged = !foodName.trim().equals(entry.foodName.trim(), ignoreCase = true)
    val portionChanged = portionText.trim() != entry.portionText.trim()
    val willReestimate = nameChanged || portionChanged
    // A reschedule is not a calorie change, so it gets its own helper line rather
    // than reusing the Jev re-estimation notice.
    val newLoggedAt = JakartaTime.atTime(dayKey, hour, minute)
    val timeChanged = newLoggedAt != entry.loggedAt

    if (showDatePicker) {
        // selectedDateMillis is midnight UTC of the tapped calendar date, so it must
        // be read back in UTC; feeding it to dayKey would shift the date by a day.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = JakartaTime.pickerUtcMillis(entry.dayKey),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { picked ->
                            dayKey = JakartaTime.dayKeyFromPickerUtc(picked)
                        }
                        showDatePicker = false
                    },
                ) { Text("Pilih") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Batal") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showTimePicker) {
        val timeState = rememberTimePickerState(
            initialHour = hour,
            initialMinute = minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Pilih jam") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        hour = timeState.hour
                        minute = timeState.minute
                        showTimePicker = false
                    },
                ) { Text("Atur") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Batal") }
            },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Ubah catatan", style = MaterialTheme.typography.titleLarge)
                MacroBadge(macroProfile)
            }

            OutlinedTextField(
                value = foodName,
                onValueChange = { foodName = it },
                label = { Text("Nama makanan") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = portionText,
                onValueChange = { portionText = it },
                label = { Text("Porsi") },
                singleLine = true,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Tanggal & jam",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateTimeField(
                        icon = Icons.Outlined.CalendarMonth,
                        label = "Tanggal",
                        value = JakartaTime.fullDate(dayKey),
                        enabled = !saving,
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                    DateTimeField(
                        icon = Icons.Outlined.Schedule,
                        label = "Jam",
                        value = String.format(Locale.forLanguageTag("id-ID"), "%02d:%02d", hour, minute),
                        enabled = !saving,
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (timeChanged) {
                    Text(
                        text = "Catatan akan dipindah ke ${JakartaTime.fullDate(dayKey)} pukul " +
                            String.format(Locale.forLanguageTag("id-ID"), "%02d:%02d", hour, minute) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Waktu makan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MealType.entries.forEach { option ->
                        FilterChip(
                            selected = meal == option,
                            onClick = { meal = option },
                            enabled = !saving,
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Catatan (opsional)") },
                minLines = 2,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatBlock(label = "kalori sekarang", value = "${entry.kcal} kkal")
                StatBlock(label = "rentang", value = entry.kcalRangeText)
            }

            if (saving) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Menghitung ulang kalori dengan Jev...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (willReestimate) {
                Text(
                    text = "Kalori akan dihitung ulang oleh Jev sesuai perubahan nama atau porsi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss, enabled = !saving) { Text("Batal") }
                Button(
                    onClick = { onSave(foodName, portionText, meal, notes, dayKey, hour, minute) },
                    enabled = !saving && foodName.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Simpan") }
            }
        }
    }
}

/** Compact tappable field showing an editable date or time value. */
@Composable
private fun DateTimeField(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
