package id.kaloriku.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.kaloriku.phone.MainViewModel
import id.kaloriku.phone.ui.theme.KaloriColors
import id.kaloriku.shared.domain.DayTotal
import id.kaloriku.shared.domain.JakartaTime
import id.kaloriku.shared.domain.MealType

/**
 * The detailed statistics tab: distribution and rankings over the last 14 days.
 *
 * Split from "Wawasan" by intent. Wawasan answers "how am I doing and what should I
 * change" with a Jev conclusion; this screen answers "where exactly did the calories
 * come from" with charts and breakdowns. Both read the same `loadStats()` data, so they
 * cannot disagree.
 */
@Composable
fun StatsScreen(vm: MainViewModel) {
    val trend by vm.trend.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadStats() }

    val target = settings.dailyTargetKcal
    val loggedDays = trend.filter { it.entries > 0 }
    val avg = if (loggedDays.isEmpty()) 0 else loggedDays.map { it.kcal }.average().toInt()
    val best = trend.maxByOrNull { it.kcal }
    val mealTotals = MealType.entries.associateWith { meal ->
        summaries.sumOf { it.byMeal[meal] ?: 0 }
    }
    val mealTotalSum = mealTotals.values.sum()
    val totalKcal = summaries.sumOf { it.totalKcal }
    val localKcal = summaries.sumOf { it.localKcal }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column {
                SectionLabel("14 hari terakhir")
                Spacer(Modifier.height(4.dp))
                Text("Statistik kalori", style = MaterialTheme.typography.headlineMedium)
            }
        }

        if (loggedDays.isEmpty()) {
            // A screen of zeroes reads as broken. Say what is missing instead.
            item {
                Callout(
                    text = "Belum ada catatan dalam 14 hari terakhir. Statistik akan muncul " +
                        "setelah kamu mencatat makanan.",
                )
            }
        } else {
            item {
                SurfaceCard {
                    CalorieBarChart(trend = trend, target = target)
                    Text(
                        text = "Batang merah berarti hari itu melewati target. " +
                            "Garis tipis menandai target $target kkal.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionLabel("Ringkasan") }

            item {
                SurfaceCard {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Metric(
                            value = "$avg",
                            label = "rata-rata kkal/hari",
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            value = "${best?.kcal ?: 0}",
                            label = "hari tertinggi",
                            modifier = Modifier.weight(1f),
                        )
                        Metric(
                            value = "${loggedDays.size}",
                            label = "hari tercatat",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        text = "Rata-rata dihitung hanya dari hari yang ada catatannya.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionLabel("Sebaran waktu makan") }

            item {
                SurfaceCard {
                    if (mealTotalSum == 0) {
                        Text(
                            text = "Belum ada data waktu makan.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ProportionBar(
                            segments = MealType.entries.map { meal ->
                                meal.label to Triple(mealTotals[meal] ?: 0, 0, mealColor(meal))
                            },
                            total = mealTotalSum,
                        )
                        MealType.entries.forEach { meal ->
                            val kcal = mealTotals[meal] ?: 0
                            if (kcal > 0) {
                                val pct = kcal * 100 / mealTotalSum
                                RankedRow(
                                    label = meal.label,
                                    value = kcal,
                                    total = mealTotalSum,
                                    valueText = "$kcal kkal · $pct%",
                                    barColor = mealColor(meal),
                                )
                            }
                        }
                    }
                }
            }

            item { SectionLabel("Makanan penyumbang kalori terbanyak") }

            item {
                val topFoods by vm.topFoods.collectAsStateWithLifecycle()
                SurfaceCard {
                    if (topFoods.isEmpty()) {
                        Text(
                            text = "Belum cukup data.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val maxKcal = topFoods.maxOf { it.second }.coerceAtLeast(1)
                        topFoods.forEach { (name, kcal) ->
                            RankedRow(
                                label = name,
                                value = kcal,
                                total = maxKcal,
                                valueText = "$kcal kkal",
                            )
                        }
                    }
                }
            }

            item { SectionLabel("Makanan lokal") }

            item {
                SurfaceCard {
                    val share = if (totalKcal == 0) 0 else localKcal * 100 / totalKcal
                    Text(
                        text = "$share% kalori berasal dari makanan lokal Indonesia",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ProportionBar(
                        segments = listOf(
                            "lokal" to Triple(localKcal, 0, KaloriColors.LightAccent),
                            "lainnya" to Triple(totalKcal - localKcal, 0, KaloriColors.LightHairline),
                        ),
                        total = totalKcal,
                    )
                    Text(
                        text = "Total $localKcal kkal dari makanan lokal dalam 14 hari.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalorieBarChart(trend: List<DayTotal>, target: Int) {
    val accent = MaterialTheme.colorScheme.primary
    val over = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceVariant
    val maxValue = maxOf(trend.maxOfOrNull { it.kcal } ?: 0, target, 1)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            if (trend.isEmpty()) return@Canvas
            val gap = 4.dp.toPx()
            val barWidth = (size.width - gap * (trend.size - 1)) / trend.size
            val targetY = size.height - (target.toFloat() / maxValue) * size.height
            drawLine(
                color = track,
                start = Offset(0f, targetY),
                end = Offset(size.width, targetY),
                strokeWidth = 1.dp.toPx(),
            )
            trend.forEachIndexed { index, day ->
                val h = (day.kcal.toFloat() / maxValue) * size.height
                val left = index * (barWidth + gap)
                drawRoundRect(
                    color = if (day.kcal > target) over else accent,
                    topLeft = Offset(left, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                JakartaTime.label(trend.firstOrNull()?.dayKey ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "hari ini",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun mealColor(meal: MealType): Color = when (meal) {
    MealType.SARAPAN -> KaloriColors.MealSarapan
    MealType.MAKAN_SIANG -> KaloriColors.MealSiang
    MealType.MAKAN_MALAM -> KaloriColors.MealMalam
    MealType.CAMILAN -> KaloriColors.MealCamilan
}
