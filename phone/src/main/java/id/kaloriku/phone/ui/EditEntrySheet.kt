package id.kaloriku.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.MealType

/**
 * Bottom sheet for editing an already-logged entry: food name, portion, meal and
 * notes. Calories are re-asked from Jev on save when the name or portion changed,
 * so the sheet only reports the current figure and shows progress while it runs.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditEntrySheet(
    entry: FoodEntry,
    /** Jev macro decision for this row, when this app session captured one. */
    macroProfile: String?,
    saving: Boolean,
    onSave: (foodName: String, portionText: String, meal: MealType, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Saveable: rotation must not throw away what the user typed in the sheet.
    var foodName by rememberSaveable(entry.id) { mutableStateOf(entry.foodName) }
    var portionText by rememberSaveable(entry.id) { mutableStateOf(entry.portionText) }
    var notes by rememberSaveable(entry.id) { mutableStateOf(entry.notes.orEmpty()) }
    var meal by rememberSaveable(entry.id) { mutableStateOf(entry.meal) }

    val nameChanged = !foodName.trim().equals(entry.foodName.trim(), ignoreCase = true)
    val portionChanged = portionText.trim() != entry.portionText.trim()
    val willReestimate = nameChanged || portionChanged

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
                    onClick = { onSave(foodName, portionText, meal, notes) },
                    enabled = !saving && foodName.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Simpan") }
            }
        }
    }
}
