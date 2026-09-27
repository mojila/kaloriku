package id.kaloriku.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import id.kaloriku.shared.domain.FoodEntry
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.wear.WearViewModel
import kotlinx.coroutines.launch

/**
 * Detail for one recent entry: what it was, how many calories, and how sure the
 * estimate is.
 *
 * Hierarchy mirrors the home screen: the food name and its calories lead, the
 * supporting facts follow as one compact block of label/value rows. The entry can be
 * edited or deleted; delete confirms inline, because a Wear dialog is mostly chrome
 * on a 1.4" round display.
 *
 * The logged date/time is shown explicitly: an entry can be rescheduled to another
 * day, so a bare "08:30" would be ambiguous for anything not logged today.
 */
@Composable
fun RecentDetailScreen(
    entry: FoodEntry,
    vm: WearViewModel,
    onClose: () -> Unit,
    onEdit: (FoodEntry) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val deleting by vm.deleteBusy.collectAsStateWithLifecycle()
    var confirmingDelete by remember(entry.id) { mutableStateOf(false) }

    val todayKey = JakartaTime.todayKey()
    val isToday = entry.dayKey == todayKey
    val dayLabel = JakartaTime.label(entry.dayKey, todayKey)

    ScreenScaffold(scrollState = listState) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // --- Lead: what and how much -----------------------------------------
            item {
                Text(
                    text = entry.foodName,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "${entry.kcal} kkal",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = if (entry.kcalLow != entry.kcalHigh) {
                            "rentang ${entry.kcalLow}–${entry.kcalHigh} kkal"
                        } else {
                            "perkiraan pasti"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Provenance badges, only when they actually apply.
            if (entry.isEdited || entry.webGrounded) {
                item {
                    Text(
                        text = buildList {
                            if (entry.isEdited) add("diedit")
                            if (entry.webGrounded) add("dari web")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // --- Supporting facts, as one block of label/value rows --------------
            item {
                FactsCard {
                    // When it happened first: the day is the field a reschedule
                    // changes, and it is what makes the time unambiguous.
                    FactRow(label = "Waktu", value = "${dayLabel}, ${timeText(entry.loggedAt)}")
                    if (!isToday) {
                        FactRow(label = "Tanggal", value = JakartaTime.fullDate(entry.dayKey))
                    }
                    FactDivider()
                    FactRow(label = "Waktu makan", value = entry.meal.label)
                    FactRow(label = "Porsi", value = entry.portionText.ifBlank { "tidak disebut" })
                    FactDivider()
                    FactRow(label = "Kepercayaan", value = "${(entry.confidence * 100).toInt()}%")
                    FactRow(label = "Jenis", value = if (entry.isLocal) "Makanan lokal" else "Umum")
                    FactRow(label = "Sumber", value = entry.source.label)
                }
            }

            // --- Actions ----------------------------------------------------------
            item {
                Button(
                    onClick = { onEdit(entry) },
                    enabled = !deleting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Ubah") }
            }

            if (!confirmingDelete) {
                item {
                    TextButton(onClick = { confirmingDelete = true }, enabled = !deleting) {
                        Text("Hapus")
                    }
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Hapus catatan ini?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        vm.deleteEntry(entry.id)
                                        onClose()
                                    }
                                },
                                enabled = !deleting,
                                modifier = Modifier.fillMaxWidth(),
                                // The error colour is only safe to use on a button if its
                                // content colour is stated too: leaving it implicit lets the
                                // label inherit a colour meant for a different container,
                                // which is how a destructive confirm button ends up unreadable.
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) {
                                if (deleting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.width(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Menghapus...")
                                } else {
                                    Text("Ya, hapus")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                TextButton(
                                    onClick = { confirmingDelete = false },
                                    enabled = !deleting,
                                ) { Text("Batal") }
                            }
                        }
                    }
                }
            }

            item {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Kembali")
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
