package id.kaloriku.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.kaloriku.phone.MainViewModel
import id.kaloriku.shared.ai.KenariClient
import id.kaloriku.shared.domain.JakartaTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun syncTimeText(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(JakartaTime.zone)
        .format(DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.forLanguageTag("id-ID")))

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val syncStatus by vm.syncStatus.collectAsStateWithLifecycle()
    var target by remember(settings.dailyTargetKcal) { mutableFloatStateOf(settings.dailyTargetKcal.toFloat()) }
    var showReset by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column {
                SectionLabel("Preferensi")
                Spacer(Modifier.height(4.dp))
                Text("Pengaturan", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Target kalori harian")
                Text(
                    text = "${target.roundToInt()} kkal",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Slider(
                    value = target,
                    onValueChange = { target = it },
                    onValueChangeFinished = { vm.setTarget(target.roundToInt()) },
                    valueRange = 1000f..4000f,
                    steps = 29,
                )
                Text(
                    text = "Rekomendasi umum 1800-2500 kkal, sesuaikan dengan kebutuhanmu.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Mesin analisa")
                Text(
                    text = "Semua keputusan kalori, porsi, gizi, dan skor kesehatan dihitung " +
                        "oleh Jev System One. Model bahasa hanya merapikan ucapan dan " +
                        "menyusun ringkasan. Tidak ada yang perlu diatur.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LabeledRow(label = "Mesin keputusan (Jev)", value = KenariClient.JEV_MODEL)
                LabeledRow(label = "Model bahasa", value = KenariClient.CHAT_MODEL)
                LabeledRow(
                    label = "Kunci Kenari",
                    value = if (KenariClient.isKeyConfigured) "Terpasang" else "Belum diatur",
                    valueColor = if (KenariClient.isKeyConfigured) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                if (!KenariClient.isKeyConfigured) {
                    Text(
                        text = "Kunci Kenari ditanam saat build. Build ulang dengan variabel " +
                            "lingkungan KENARI_API_KEY agar analisa bisa berjalan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Sinkronisasi jam")
                Text(
                    text = "Catatan dari jam dan HP saling disalin otomatis. " +
                        "Gunakan tombol ini jika ingin menyinkronkan sekarang.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { vm.syncNow() },
                    enabled = !syncStatus.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (syncStatus.running) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Menyinkronkan...")
                    } else {
                        Text("Sinkronkan sekarang")
                    }
                }
                val hint = syncStatus.message
                    ?: if (syncStatus.hasSynced) {
                        "Terakhir sinkron: ${syncTimeText(syncStatus.lastSuccessAt!!)}"
                    } else {
                        null
                    }
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Data")
                Text(
                    text = "Menghapus data akan menghapus semua catatan makanan di HP ini " +
                        "dan, saat tersinkron, di jam juga.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { showReset = true }) { Text("Hapus semua data") }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Tentang")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(id.kaloriku.phone.R.drawable.ic_logo),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                    Column {
                        Text("KaloriKu", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Versi ${id.kaloriku.phone.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Pencatat kalori makanan Indonesia dengan input suara. " +
                        "Analisa oleh Kenari AI (${KenariClient.CHAT_MODEL}) dan Jev System One.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Hapus semua data?") },
            text = { Text("Tindakan ini tidak bisa dibatalkan.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearAll()
                        showReset = false
                    },
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("Batal") }
            },
        )
    }
}
