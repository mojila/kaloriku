package id.kaloriku.phone.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.kaloriku.phone.InsightSummary
import id.kaloriku.phone.MainViewModel
import id.kaloriku.shared.domain.JakartaTime

/**
 * The "Wawasan" tab: one screen that answers "how am I actually doing?".
 *
 * Information architecture, top to bottom:
 *  1. Hero \u2014 the single derived headline plus the three numbers behind it, so the
 *     most important reading is visible without scrolling.
 *  2. Skor kesehatan \u2014 the Jev score on its 0-4 scale, with its band name.
 *  3. Kesimpulan Jev \u2014 the generated Indonesian conclusion, refreshable.
 *  4. Tren harian \u2014 per-day totals against target, newest first.
 *  5. Ringkasan \u2014 best/highest day, over-target share and local-food share.
 *
 * Every number comes from [InsightSummary], the same fold the statistics tab reads, so
 * the two screens can never disagree. An empty log collapses the whole screen to one
 * callout instead of a wall of zeroes.
 */
@Composable
fun InsightsScreen(vm: MainViewModel) {
    val summary by vm.insightSummary.collectAsStateWithLifecycle()
    val insight by vm.insight.collectAsStateWithLifecycle()
    val loading by vm.insightLoading.collectAsStateWithLifecycle()

    // Load once when the tab is first shown. The insight is only generated when the
    // view model holds none, so returning to this tab does not spend another call.
    LaunchedEffect(Unit) {
        vm.loadStats()
        vm.generateInsightIfAbsent()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column {
                SectionLabel("Analisa Jev")
                Spacer(Modifier.height(4.dp))
                Text("Wawasan", style = MaterialTheme.typography.headlineMedium)
            }
        }

        if (!summary.hasData) {
            item {
                Callout(
                    text = "Belum ada catatan makanan. Catat makanmu dulu di Beranda, " +
                        "lalu kembali ke sini untuk melihat ringkasan, skor kesehatan, dan tren harianmu.",
                )
            }
            return@LazyColumn
        }

        item { HeroCard(summary) }
        item { HealthScoreCard(summary) }

        item { SectionLabel("Kesimpulan Jev") }
        item { ConclusionCard(insight?.text, loading, onRefresh = vm::generateInsight) }

        item {
            Column {
                SectionLabel("Tren harian")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Dibanding target ${summary.target} kkal",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { DailyTrendCard(summary) }

        item { SectionLabel("Ringkasan") }
        item { FactsCard(summary) }

        item {
            Caption(
                "Angka rata-rata hanya menghitung hari yang kamu catat, supaya hari kosong " +
                    "tidak membuat asupanmu terlihat lebih rendah dari sebenarnya.",
            )
        }
    }
}

/**
 * The hero: the derived headline is the largest text on the screen, with the three
 * summary numbers directly beneath it. No second card, no competing value.
 */
@Composable
private fun HeroCard(summary: InsightSummary) {
    SurfaceCard {
        Text(
            text = summary.headline,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Metric(
                value = "${summary.avgKcal}",
                label = "rata-rata kkal/hari",
                modifier = Modifier.weight(1f),
                valueColor = if (summary.avgDeltaKcal > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Metric(
                value = "${summary.streak} hari",
                label = "berturut-turut",
                modifier = Modifier.weight(1f),
            )
            Metric(
                value = "${summary.loggedDays}/${summary.totalDays}",
                label = "hari tercatat",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The Jev health score on its 0-4 scale, as a large value with the band name and a
 * proportional bar. An unscored log shows an explicit empty state rather than a
 * zero-length bar pretending the score is 0.0.
 */
@Composable
private fun HealthScoreCard(summary: InsightSummary) {
    val score = summary.healthScore
    val color = healthColor(score)
    SurfaceCard {
        SectionLabel("Skor kesehatan pola makan")
        if (score <= 0.0) {
            Text(
                text = "Belum ada skor",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Caption(
                "Jev menilai keseimbangan gizi, minyak, gula, dan porsi dari makanan yang " +
                    "kamu catat. Skor muncul setelah ada minimal satu catatan yang dinilai.",
            )
            return@SurfaceCard
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Metric(
                value = "%.1f".format(score),
                label = "dari 4,0",
                valueColor = color,
            )
            Text(
                text = healthLabel(score),
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
        }
        ProportionBar(
            segments = listOf(
                "skor" to Triple((score * 100).toInt(), 0, color),
                "sisa" to Triple(((4.0 - score) * 100).toInt(), 0, MaterialTheme.colorScheme.surfaceVariant),
            ),
            total = 400,
        )
        Caption(
            "Skor 0-4 dari Jev, dirata-ratakan dari hari yang dinilai. Angka ini menilai " +
                "kualitas pola makan, bukan sekadar jumlah kalori.",
        )
    }
}

/**
 * The generated Indonesian conclusion.
 *
 * While loading, a spinner and a short line stand in for the text so the card keeps its
 * height instead of jumping. The refresh button stays disabled during a run to prevent
 * stacking two generations on the same view model state.
 */
@Composable
private fun ConclusionCard(text: String?, loading: Boolean, onRefresh: () -> Unit) {
    SurfaceCard {
        when {
            loading && text == null -> {
                Row(
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Menyusun analisis\u2026",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            text.isNullOrBlank() -> {
                Text(
                    text = "Belum ada analisis. Tekan tombol di bawah untuk membuatnya.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading && text != null) {
                Text(
                    text = "Memperbarui\u2026",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "Mohon tunggu" else "Buat ulang analisis")
            }
        }
    }
}

/**
 * Per-day totals against target, newest first.
 *
 * Bars scale against the busiest day in the window (never zero, so the divisor is
 * safe). Days with no entries stay in the list as quiet labels rather than being
 * hidden, because the gap itself is information.
 */
@Composable
private fun DailyTrendCard(summary: InsightSummary) {
    val ordered = summary.days.asReversed()
    val maxKcal = summary.days.maxOfOrNull { it.kcal }?.coerceAtLeast(1) ?: 1
    SurfaceCard {
        SectionLabel("${summary.totalDays} hari terakhir")
        if (ordered.isEmpty()) {
            Caption("Belum ada data harian.")
            return@SurfaceCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ordered.forEach { row ->
                TrendRow(row = row, maxKcal = maxKcal, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Compact facts block: best day, highest day, share of days over target, local-food
 * share. Labelled rows with hairlines, not one card per fact.
 */
@Composable
private fun FactsCard(summary: InsightSummary) {
    val best = summary.bestDay
    val highest = summary.highestDay
    val overPct = (summary.overTargetShare * 100).toInt()
    val localPct = (summary.localShare * 100).toInt()
    SurfaceCard {
        FactRow(
            label = "Hari paling dekat target",
            value = best?.let { "${JakartaTime.fullDate(it.dayKey)} \u00b7 ${it.kcal} kkal" }
                ?: "Belum ada",
            valueColor = MaterialTheme.colorScheme.primary,
        )
        Hairline(Modifier.fillMaxWidth())
        FactRow(
            label = "Hari tertinggi",
            value = highest?.let { "${JakartaTime.fullDate(it.dayKey)} \u00b7 ${it.kcal} kkal" }
                ?: "Belum ada",
            valueColor = if (highest?.isOver == true) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Hairline(Modifier.fillMaxWidth())
        FactRow(
            label = "Hari melewati target",
            value = "${summary.overTargetDays} dari ${summary.loggedDays} hari ($overPct%)",
            valueColor = if (overPct >= 50) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        Hairline(Modifier.fillMaxWidth())
        FactRow(
            label = "Kalori dari makanan lokal",
            value = "$localPct%",
            valueColor = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * A fact row whose value may be longer than the label, so the value wraps on its own
 * line when needed instead of being squeezed into a single line.
 */
@Composable
private fun FactRow(label: String, value: String, valueColor: Color) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
        )
    }
}
