package id.kaloriku.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.wear.WearViewModel

/**
 * The watch home.
 *
 * Reading order is deliberate and matches how the screen is actually used:
 *   1. the day's calorie total, as the one number worth a glance,
 *   2. a single primary action to log a meal,
 *   3. the recent-foods list, which is the content the user came for,
 *   4. a quiet sync line, demoted so it cannot compete with the total.
 *
 * Everything scrolls in a [ScalingLazyColumn] so items curve to the round bezel
 * instead of being clipped by it.
 */
@Composable
fun HomeScreen(
    vm: WearViewModel,
    onLogVoice: () -> Unit,
    onSelect: (FoodEntry) -> Unit,
) {
    val home by vm.home.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val todayKey = JakartaTime.todayKey()

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { DayTotalHeader(todayTotal = home.todayTotal, target = home.target) }

            item {
                Button(
                    onClick = onLogVoice,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Catat suara")
                }
            }

            // Secondary by design: one muted line, no card, no spinner unless running.
            item {
                SyncStatusLine(
                    running = syncStatus.running,
                    hasSynced = syncStatus.hasSynced,
                    message = syncStatus.message,
                    lastSuccessAt = syncStatus.lastSuccessAt,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            item { SectionHeader("MAKANAN TERAKHIR") }

            if (home.recent.isEmpty()) {
                item {
                    Text(
                        text = "Belum ada catatan hari ini.\nTekan Catat suara untuk mulai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(home.recent, key = { it.id }) { entry ->
                    RecentRow(
                        entry = entry,
                        todayKey = todayKey,
                        onClick = { onSelect(entry) },
                    )
                }
            }

            // Breathing room so the last row clears the round bezel instead of being
            // sliced by it.
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * The day's total, as the focal point of the screen.
 *
 * Layout: day label / big total / one subline. The total always keeps the primary
 * accent so the focal number has a stable identity; the subline states the target and
 * then either what is left or how far over. Over-target is shown with an explicit word
 * *and* the error colour, so the word alone already carries the meaning — the colour
 * only reinforces it, which keeps the state readable for colour-blind users and on a
 * dim always-on display.
 */
@Composable
private fun DayTotalHeader(todayTotal: Int, target: Int) {
    val over = todayTotal > target
    val remaining = (target - todayTotal).coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(id = id.kaloriku.wear.R.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = JakartaTime.label(JakartaTime.todayKey()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The one number that matters.
        Text(
            text = "$todayTotal",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "kkal",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = if (over) {
                "dari $target kkal · lewat ${todayTotal - target} kkal"
            } else {
                "dari $target kkal · sisa $remaining kkal"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (over) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
