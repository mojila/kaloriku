package id.kaloriku.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.MealType
import id.kaloriku.wear.WearViewModel
import kotlinx.coroutines.launch

/**
 * Edit one logged entry: name, portion and meal.
 *
 * The watch has no room for a dense form, so the fields are two tappable cards that
 * open the system keyboard and the meal is chosen from four large chips. Changing the
 * name or portion re-estimates the calories through Jev on save; changing only the
 * meal saves instantly. The current estimate is shown so the user sees the update.
 */
@Composable
fun EditEntryScreen(
    vm: WearViewModel,
    entry: FoodEntry,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy by vm.editBusy.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    // Input survives rotation and process death. The entry id is woven into each key so
    // a saved value from a different entry can never be restored onto this one.
    var foodName by rememberSaveable(entry.id) { mutableStateOf(entry.foodName) }
    var portionText by rememberSaveable(entry.id) { mutableStateOf(entry.portionText) }
    var meal by rememberSaveable(entry.id, stateSaver = mealSaver) { mutableStateOf(entry.meal) }
    // Live estimate shown while editing; only changes after a save re-estimates it.
    var shownKcal by remember(entry.id) { mutableStateOf(entry.kcal) }
    var shownRange by remember(entry.id) { mutableStateOf(entry.kcalRangeText) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }

    fun save() {
        if (foodName.isBlank() || busy) return
        scope.launch {
            val updated = vm.editEntry(
                id = entry.id,
                foodName = foodName,
                portionText = portionText,
                meal = meal,
                notes = entry.notes,
            )
            if (updated != null) {
                // The persisted numbers come straight from the write, so the calorie
                // line reflects Jev's answer without racing the database flow.
                shownKcal = updated.kcal
                shownRange = updated.kcalRangeText
                onDone()
            } else {
                // The entry is gone (deleted from the phone) or the write failed; say so
                // rather than leaving the screen looking like nothing happened.
                error = "Gagal menyimpan. Catatan mungkin sudah dihapus."
            }
        }
    }

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "Ubah catatan",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }

            item {
                Text(
                    text = "$shownKcal kkal",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            item {
                Text(
                    text = shownRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                WatchTextField(
                    label = "Nama makanan",
                    value = foodName,
                    placeholder = "nasi goreng",
                    onValueChange = { foodName = it },
                )
            }

            item {
                WatchTextField(
                    label = "Porsi",
                    value = portionText,
                    placeholder = "satu piring",
                    onValueChange = { portionText = it },
                )
            }

            item {
                Text(
                    text = "WAKTU MAKAN",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().paddingForSection(),
                )
            }

            // Two rows of two chips: every target stays comfortably thumb-sized.
            item {
                MealChips(
                    selected = meal,
                    onSelect = { meal = it },
                )
            }

            item {
                Button(
                    onClick = { save() },
                    enabled = foodName.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.heightIn(min = 18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Menyimpan...")
                    } else {
                        Text("Simpan")
                    }
                }
            }

            item {
                val isFoodChanged = foodName.trim() != entry.foodName.trim() ||
                    portionText.trim() != entry.portionText.trim()
                Text(
                    text = if (isFoodChanged) "Kalori dihitung ulang" else "Kalori tidak berubah",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            error?.let { message ->
                item {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                TextButton(onClick = onCancel, enabled = !busy) { Text("Batal") }
            }
        }
    }
}

/**
 * Saves [MealType] by its name so an in-progress edit survives process death.
 * Restores through [MealType.fromKey], which tolerates an unknown/renamed value.
 */
private val mealSaver = Saver<MealType, String>(
    save = { it.name },
    restore = { MealType.fromKey(it) },
)

/** Four tappable meal chips laid out two per row so each stays a large target. */
@Composable
private fun MealChips(selected: MealType, onSelect: (MealType) -> Unit) {
    val meals = MealType.entries
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        meals.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { meal ->
                    MealChip(
                        label = meal.label,
                        selected = meal == selected,
                        onClick = { onSelect(meal) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MealChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A filled Button for the active meal, an outlined card for the rest; both keep
    // the wear default minimum touch target.
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        Card(
            onClick = onClick,
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// Small layout helper keeps the section padding consistent with the other screens.
private fun Modifier.paddingForSection(): Modifier =
    padding(top = 8.dp, start = 4.dp)
