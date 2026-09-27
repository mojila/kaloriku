package id.kaloriku.phone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.MealType
import id.kaloriku.shared.sync.SyncStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One meal's entries for the day, with its calorie subtotal. */
data class MealGroup(val meal: MealType, val entries: List<FoodEntry>) {
    val totalKcal: Int get() = entries.sumOf { it.kcal }
}

/**
 * Groups a day's entries by meal, in [MealType] declaration order.
 *
 * Meals with no entries are dropped rather than rendered empty: the section header and
 * its subtotal only carry meaning when there is something under them.
 */
fun groupByMeal(entries: List<FoodEntry>): List<MealGroup> =
    MealType.entries.mapNotNull { meal ->
        entries.filter { it.meal == meal }
            .takeIf { it.isNotEmpty() }
            ?.let { MealGroup(meal, it) }
    }

/** Wall-clock time in Jakarta, 24-hour, Indonesian locale. */
fun formatClock(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(JakartaTime.zone)
        .format(DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("id-ID")))

/**
 * The manual sync control, kept visually secondary.
 *
 * Syncing is not the job of this screen — logging food is — so this is an outlined
 * button with a quiet caption rather than a full-weight primary action that would
 * compete with the voice button above it.
 */
@Composable
fun SyncRow(status: SyncStatus, onSync: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = onSync,
            enabled = !status.running,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (status.running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Menyinkronkan...")
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sinkronkan dengan jam")
            }
        }
        val hint = status.message
            ?: if (status.hasSynced) "Terakhir sinkron ${formatClock(status.lastSuccessAt!!)}" else null
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One meal's block: a heading with the meal's calorie subtotal, then its entries.
 *
 * The subtotal on the heading is what makes the day readable at a glance — the user can
 * see where the calories went without adding up the rows themselves.
 */
@Composable
fun MealSection(
    group: MealGroup,
    macroProfiles: Map<Long, String>,
    onEdit: (FoodEntry) -> Unit,
    onDelete: (FoodEntry) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(group.meal.label)
            Text(
                text = "${group.totalKcal} kkal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        group.entries.forEach { entry ->
            EntryRow(
                entry = entry,
                macroProfile = macroProfiles[entry.id],
                onEdit = { onEdit(entry) },
                onDelete = { onDelete(entry) },
            )
        }
    }
}

/**
 * A single logged food.
 *
 * The whole row opens the edit sheet, which is where every mutation of an entry lives
 * (including changing its date and time). Delete is a quiet text affordance rather than
 * a filled icon on every row: it is destructive and rare, so it must not be the most
 * visually prominent thing in the list.
 */
@Composable
private fun EntryRow(
    entry: FoodEntry,
    macroProfile: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.foodName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (macroProfile != null) {
                        Spacer(Modifier.width(8.dp))
                        MacroBadge(macroProfile)
                    }
                    if (entry.isEdited) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "diedit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = buildString {
                        append(formatClock(entry.loggedAt))
                        if (entry.portionText.isNotBlank()) append(" · ${entry.portionText}")
                        if (entry.isLocal) append(" · lokal")
                        if (entry.webGrounded) append(" · dari web")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${entry.kcal} kkal",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (entry.kcalLow != entry.kcalHigh) {
                    Text(
                        text = "± ${entry.kcalLow}-${entry.kcalHigh}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDelete) {
                Text(
                    text = "Hapus",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Hairline()
    }
}
