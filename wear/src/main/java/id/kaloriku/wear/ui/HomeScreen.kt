package id.kaloriku.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import id.kaloriku.wear.WearViewModel
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.sync.SyncStatus
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The watch home. Today's total sits at the top; the recent-foods list is the
 * main content and the primary thing the user sees on a glance.
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

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(id.kaloriku.wear.R.drawable.ic_logo),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = JakartaTime.label(JakartaTime.todayKey()).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${home.todayTotal}",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item {
                Text(
                    text = "dari ${home.target} kkal · sisa ${home.remaining}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            item {
                Button(
                    onClick = onLogVoice,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null)
                    Spacer(Modifier.height(2.dp))
                    Text("Catat suara")
                }
            }

            item {
                SyncStatusLine(status = syncStatus)
            }

            item {
                Text(
                    text = "MAKANAN TERAKHIR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
                )
            }

            if (home.recent.isEmpty()) {
                item {
                    Text(
                        text = "Belum ada catatan. Tekan Catat suara untuk mulai.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            } else {
                items(home.recent, key = { it.id }) { entry ->
                    RecentCard(entry = entry, onClick = { onSelect(entry) })
                }
            }
        }
    }
}

/**
 * Read-only sync status. Sync is triggered from the phone (the manual button) or
 * automatically on save/foreground, so the watch only reports the last result.
 */
@Composable
private fun SyncStatusLine(status: SyncStatus) {
    val message = status.message
    val hint: String = when {
        status.running -> "Menyinkronkan..."
        !message.isNullOrBlank() -> message
        status.hasSynced -> "Tersinkron ${timeText(status.lastSuccessAt!!)}"
        else -> "Belum tersinkron"
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    ) {
        if (status.running) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecentCard(entry: FoodEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = entry.foodName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${entry.kcal} kkal · ${timeText(entry.loggedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun timeText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(JakartaTime.zone)
        .format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.forLanguageTag("id-ID")))
