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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.MealType
import id.kaloriku.wear.WearViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Edit one logged entry: name, portion, meal and the date/time it was logged at.
 *
 * The watch has no room for a dense form, so the fields are two tappable cards that
 * open the system keyboard and the meal is chosen from four large chips. The date/time
 * editor is a compact row of steppers rather than a Material `DatePicker`: on a ~1.4"
 * round display a calendar grid is cramped and hard to hit, while +/- buttons and two
 * quick "Hari ini"/"Kemarin" chips keep every target thumb-sized.
 *
 * Changing the name or portion re-estimates the calories through Jev on save; changing
 * the meal or only the date/time saves instantly and reuses the existing numbers. The
 * current estimate is shown so the user sees the update.
 */
@Composable
fun EditEntryScreen(
    vm: WearViewModel,
    entry: FoodEntry,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val busy by vm.editBusy.collectAsStateWithLifecycle()
    val vmError by vm.errorMessage.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    // Input survives rotation and process death. The entry id is woven into each key so
    // a saved value from a different entry can never be restored onto this one.
    var foodName by rememberSaveable(entry.id) { mutableStateOf(entry.foodName) }
    var portionText by rememberSaveable(entry.id) { mutableStateOf(entry.portionText) }
    var meal by rememberSaveable(entry.id, stateSaver = mealSaver) { mutableStateOf(entry.meal) }
    // The edited date/time as its two user-facing parts: the Jakarta day key and the
    // wall-clock time. Kept as separate primitives so all three save as plain values.
    var dayKey by rememberSaveable(entry.id) { mutableStateOf(entry.dayKey) }
    var hour by rememberSaveable(entry.id) { mutableIntStateOf(JakartaTime.hourOfDay(entry.loggedAt)) }
    var minute by rememberSaveable(entry.id) { mutableIntStateOf(JakartaTime.minuteOfHour(entry.loggedAt)) }
    // Live estimate shown while editing; only changes after a save re-estimates it.
    var shownKcal by remember(entry.id) { mutableIntStateOf(entry.kcal) }
    var shownRange by remember(entry.id) { mutableStateOf(entry.kcalRangeText) }
    var error by remember(entry.id) { mutableStateOf<String?>(null) }

    // A save failure raised by the view model (the repository threw) has to reach the
    // screen too, not only the local "entry is gone" case below. This screen is where
    // the save happens, so it shows the message; it is cleared once shown so a later
    // screen does not repeat it.
    LaunchedEffect(vmError) {
        vmError?.let {
            error = it
            vm.clearError()
        }
    }

    // True only when the user actually moved the entry, used for the hint line and to
    // decide whether the repository needs a new timestamp at all.
    val scheduleChanged = dayKey != entry.dayKey ||
        hour != JakartaTime.hourOfDay(entry.loggedAt) ||
        minute != JakartaTime.minuteOfHour(entry.loggedAt)

    fun save() {
        if (foodName.isBlank() || busy) return
        scope.launch {
            val updated = vm.editEntry(
                id = entry.id,
                foodName = foodName,
                portionText = portionText,
                meal = meal,
                notes = entry.notes,
                dayKey = dayKey,
                hour = hour,
                minute = minute,
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
                Text(
                    text = "TANGGAL & JAM",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().paddingForSection(),
                )
            }

            item {
                DateTimeEditor(
                    dayKey = dayKey,
                    hour = hour,
                    minute = minute,
                    enabled = !busy,
                    onDayKeyChange = { dayKey = it },
                    onHourChange = { hour = it },
                    onMinuteChange = { minute = it },
                )
            }

            if (scheduleChanged) {
                item {
                    Text(
                        text = "Dipindah ke ${JakartaTime.fullDate(dayKey)} " +
                            formatTime(hour, minute),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center,
                    )
                }
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
                    text = if (isFoodChanged) {
                        "Kalori dihitung ulang"
                    } else {
                        // A date/time-only move never re-estimates, so say so explicitly
                        // rather than leaving the user unsure whether the move cost a
                        // fresh Jev call.
                        "Kalori tidak berubah"
                    },
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

/**
 * Compact date/time editor for the round display.
 *
 * A Material `DatePicker` needs a full-screen calendar that never fits a ~1.4" watch
 * comfortably, so the day is chosen with two quick chips plus a day stepper and the
 * time with hour/minute steppers. Everything is a large button; there is no text field
 * and therefore no keyboard.
 */
@Composable
private fun DateTimeEditor(
    dayKey: String,
    hour: Int,
    minute: Int,
    enabled: Boolean,
    onDayKeyChange: (String) -> Unit,
    onHourChange: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
) {
    val today = JakartaTime.todayKey()
    val dayLabel = JakartaTime.label(dayKey, today)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The exact calendar day is always visible, so a stepper press never leaves the
        // user guessing which date they landed on.
        Text(
            text = "${JakartaTime.fullDate(dayKey)} • $dayLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Quick jumps for the two days that cover almost every correction.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickDayButton(
                label = "Hari ini",
                selected = dayKey == today,
                enabled = enabled,
                onClick = { onDayKeyChange(today) },
                modifier = Modifier.weight(1f),
            )
            QuickDayButton(
                label = "Kemarin",
                selected = dayKey == shiftDay(today, -1),
                enabled = enabled,
                onClick = { onDayKeyChange(shiftDay(today, -1)) },
                modifier = Modifier.weight(1f),
            )
        }

        // Day stepper: one step is one calendar day in Asia/Jakarta.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StepButton(
                label = "−",
                enabled = enabled,
                onClick = { onDayKeyChange(shiftDay(dayKey, -1)) },
                modifier = Modifier.weight(1f),
            )
            StepReadout(text = "hari", modifier = Modifier.weight(1.2f))
            StepButton(
                label = "+",
                enabled = enabled,
                onClick = { onDayKeyChange(shiftDay(dayKey, 1)) },
                modifier = Modifier.weight(1f),
            )
        }

        // Time stepper: hour and minute each get a pair of buttons around a readout.
        // Steppers beat a keyboard here: the target stays large and the value wraps.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepColumnLabel(text = "Jam")
            StepButtonSmall(
                label = "−",
                enabled = enabled,
                onClick = { onHourChange(floorMod(hour - 1, 24)) },
            )
            StepReadout(text = "%02d".format(hour))
            StepButtonSmall(
                label = "+",
                enabled = enabled,
                onClick = { onHourChange(floorMod(hour + 1, 24)) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepColumnLabel(text = "Menit")
            StepButtonSmall(
                label = "−",
                enabled = enabled,
                onClick = { onMinuteChange(floorMod(minute - 5, 60)) },
            )
            StepReadout(text = "%02d".format(minute))
            StepButtonSmall(
                label = "+",
                enabled = enabled,
                onClick = { onMinuteChange(floorMod(minute + 5, 60)) },
            )
        }
    }
}

/** A quick-jump chip for a named day; filled when it is the current selection. */
@Composable
private fun QuickDayButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        Card(
            onClick = onClick,
            enabled = enabled,
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

/** Large +/− target used for the day stepper. */
@Composable
private fun StepButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Slightly smaller +/− target for the hour/minute rows. */
@Composable
private fun StepButtonSmall(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Centered readout that shows the value a stepper pair is editing. */
@Composable
private fun StepReadout(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.heightIn(min = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}

/** Fixed-width row label so the hour and minute rows line up. */
@Composable
private fun StepColumnLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(44.dp),
    )
}

/** "HH:mm" for the hint line. */
private fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

/**
 * Moves [dayKey] by [days] calendar days.
 *
 * Uses [LocalDate] rather than millisecond arithmetic so a month/year boundary and a
 * short month are handled by the calendar, not by hand. An unparseable key is left
 * as-is; a day key can only ever be produced by [JakartaTime], so this is defensive.
 */
private fun shiftDay(dayKey: String, days: Long): String = runCatching {
    LocalDate.parse(dayKey, DateTimeFormatter.ISO_LOCAL_DATE).plusDays(days)
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
}.getOrDefault(dayKey)

/** Modulo that never returns a negative value, for wrapping the hour/minute steppers. */
private fun floorMod(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus

// Small layout helper keeps the section padding consistent with the other screens.
private fun Modifier.paddingForSection(): Modifier =
    padding(top = 8.dp, start = 4.dp)

