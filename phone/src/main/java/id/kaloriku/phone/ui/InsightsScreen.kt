package id.kaloriku.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.kaloriku.phone.MainViewModel
import id.kaloriku.phone.ui.theme.KaloriColors
import id.kaloriku.shared.domain.JakartaTime

@Composable
fun InsightsScreen(vm: MainViewModel) {
    val insight by vm.insight.collectAsStateWithLifecycle()
    val loading by vm.insightLoading.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    // Load once when the screen is first shown. The insight is only generated if the
    // view model has none yet, so returning to this tab does not spend another call.
    LaunchedEffect(Unit) {
        vm.loadStats()
        vm.generateInsightIfAbsent()
    }

    val target = settings.dailyTargetKcal
    val avg = if (summaries.isEmpty()) 0 else summaries.map { it.totalKcal }.average().toInt()
    val health = if (summaries.isEmpty()) 0.0 else summaries.map { it.avgHealthScore }.filter { it > 0 }
        .takeIf { it.isNotEmpty() }?.average() ?: 0.0
    val streak = summaries.reversed().takeWhile { it.entryCount > 0 }.size

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column {
                SectionLabel("Analisa Jev")
                Spacer(Modifier.height(4.dp))
                Text("Wawasan", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBlock("skor sehat Jev", "%.1f".format(health), modifier = Modifier.weight(1f))
                StatBlock("rata-rata kkal", "$avg", modifier = Modifier.weight(1f))
                StatBlock("berturut-turut", "$streak hari", modifier = Modifier.weight(1f))
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Skor kesehatan pola makan", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = healthLabel(health),
                        style = MaterialTheme.typography.titleMedium,
                        color = healthColor(health),
                    )
                }
                LinearProgressIndicator(
                    progress = { (health / 4.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = healthColor(health),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = "Skor 0-4 dari Jev, dirata-ratakan dari catatan terakhir. " +
                        "Penilaian mencakup keseimbangan gizi, minyak, gula, dan porsi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (streak > 0) {
                        "Kamu mencatat makan $streak hari berturut-turut sampai hari ini."
                    } else {
                        "Belum ada catatan hari ini, jadi rentetan harinya masih 0."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionLabel("Kesimpulan") }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) {
                    Text(
                        "Menyusun analisis...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Text(
                        text = insight?.text ?: "Belum ada analisis.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Button(onClick = { vm.generateInsight() }, enabled = !loading) {
                    Text(if (loading) "Mohon tunggu" else "Buat ulang analisis")
                }
            }
        }

        item { SectionLabel("Ringkasan 14 hari") }

        item {
            Column {
                summaries.reversed().take(7).forEach { summary ->
                    LabeledRow(
                        label = "${JakartaTime.label(summary.dayKey)} · ${summary.entryCount} catatan",
                        value = "${summary.totalKcal} kkal",
                        valueColor = if (summary.totalKcal > target) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

private fun healthLabel(score: Double): String = when {
    score <= 0.0 -> "Belum ada data"
    score < 2.0 -> "Perlu perbaikan"
    score < 3.0 -> "Cukup"
    score < 3.5 -> "Baik"
    else -> "Sangat baik"
}

private fun healthColor(score: Double) = when {
    score < 2.0 -> KaloriColors.LightAlert
    score < 3.0 -> KaloriColors.MealMalam
    else -> KaloriColors.LightAccent
}
