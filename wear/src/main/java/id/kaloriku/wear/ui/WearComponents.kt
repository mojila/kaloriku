package id.kaloriku.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Small pieces shared by the watch screens.
 *
 * Everything here is deliberately plain: a labelled value row, a section heading, a
 * progress-or-status line. No emoji, no icon tiles, no gradients — on a 1.4" round
 * display the only things that buy readability are size, spacing and contrast.
 */

/**
 * One readable "label : value" fact row.
 *
 * A watch is read in a glance, and a stack of centred uppercase labels over centred
 * values turns into a long undifferentiated column the eye has to re-parse for every
 * fact. A single left-aligned row keeps the label and its value on one scanning line.
 */
@Composable
internal fun FactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            // Values are short, but a long food name or portion must not push the
            // label off the row; two lines keep the label always visible.
            maxLines = 2,
        )
    }
}

/** Section heading above a group of items. Small, muted, left-aligned. */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

/** The single, surfaced sync state of the watch link: idle, syncing, ok or failed. */
internal enum class SyncVisual { SYNCING, OK, IDLE, FAILED }

/**
 * Classifies a [SyncStatus] into the one thing the UI needs to show.
 *
 * Kept as a pure function so the screens never branch on raw sync fields, and so the
 * "failed" state is derived from the coordinator's message rather than guessed.
 */
internal fun syncVisual(
    running: Boolean,
    hasSynced: Boolean,
    message: String?,
    lastSuccessAt: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): SyncVisual = when {
    running -> SyncVisual.SYNCING
    message != null && (lastSuccessAt == null || nowMillis - lastSuccessAt > 60_000L) -> SyncVisual.FAILED
    hasSynced -> SyncVisual.OK
    else -> SyncVisual.IDLE
}

/**
 * Quiet one-line sync status.
 *
 * Sync is secondary: it must never compete with the day's total, so it is a single
 * muted line — optionally with a small spinner while it runs — and never a card.
 */
@Composable
internal fun SyncStatusLine(
    running: Boolean,
    hasSynced: Boolean,
    message: String?,
    lastSuccessAt: Long?,
    modifier: Modifier = Modifier,
) {
    val visual = syncVisual(running, hasSynced, message, lastSuccessAt)
    val text: String = when (visual) {
        SyncVisual.SYNCING -> "Menyinkronkan…"
        SyncVisual.OK -> "Tersinkron ${timeText(lastSuccessAt!!)}"
        SyncVisual.FAILED -> "Gagal sinkron"
        SyncVisual.IDLE -> "Belum tersinkron"
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (visual == SyncVisual.SYNCING) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Spacer(Modifier.size(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (visual == SyncVisual.FAILED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * One row in the recent list: the food, then its calories and when it was eaten.
 *
 * The when-line adapts to the entry's day. An entry can be rescheduled to another day,
 * and "08:30" alone would read as today for a meal that was actually yesterday, so a
 * non-today entry shows its day label in place of the meal slot (the meal is still on
 * the detail screen). Today's entries keep the meal type, which is the more useful of
 * the two there.
 */
@Composable
internal fun RecentRow(
    entry: FoodEntry,
    todayKey: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToday = entry.dayKey == todayKey
    val whenText = if (isToday) {
        "${entry.meal.label} · ${timeText(entry.loggedAt)}"
    } else {
        // Non-today: the day label ("Kemarin", "25 Sep") carries the meaning.
        "${JakartaTime.label(entry.dayKey, todayKey)} · ${timeText(entry.loggedAt)}"
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = entry.foodName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${entry.kcal} kkal · $whenText",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A card holding a set of [FactRow]s, separated by hairline dividers.
 *
 * Used on the detail screen so the facts read as one block rather than a column of
 * loose centred pairs. Dividers are surface-level, never a card inside a card.
 */
@Composable
internal fun FactsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

/**
 * Hairline separator sized for the narrow watch card.
 *
 * Drawn as a bare 1dp box rather than a Material divider, because Wear Material3
 * 1.7.0 ships no divider component and pulling in the phone Material3 artifact just
 * for a line would put two Material3 flavours on the classpath.
 */
@Composable
internal fun FactDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/** "HH:mm" in the app's Jakarta timezone. */
internal fun timeText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(JakartaTime.zone)
        .format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.forLanguageTag("id-ID")))
