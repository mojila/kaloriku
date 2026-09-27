package id.kaloriku.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import id.kaloriku.wear.WearViewModel
import kotlinx.coroutines.launch

/**
 * Detail for one recent entry: range, confidence and meal type.
 *
 * The entry can be edited or deleted from here. Delete asks for confirmation inline
 * (Wear has no room for a long dialog), and the screen closes once the entry is gone.
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
                    text = entry.foodName,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            if (entry.isEdited) {
                item {
                    Text(
                        text = "diedit",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (entry.webGrounded) {
                item {
                    Text(
                        text = "dari web",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            item {
                Text(
                    text = "${entry.kcal} kkal",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Text(
                    text = if (entry.kcalLow != entry.kcalHigh) {
                        "Rentang ${entry.kcalLow}-${entry.kcalHigh} kkal"
                    } else {
                        "Perkiraan pasti"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                DetailLine("Waktu makan", entry.meal.label)
            }
            item {
                DetailLine("Porsi", entry.portionText.ifBlank { "tidak disebut" })
            }
            item {
                DetailLine("Kepercayaan", "${(entry.confidence * 100).toInt()}%")
            }
            item {
                DetailLine("Jenis", if (entry.isLocal) "Makanan lokal" else "Umum")
            }
            item {
                DetailLine("Sumber", entry.source.label)
            }

            item {
                Button(
                    onClick = { onEdit(entry) },
                    enabled = !deleting,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
