package id.kaloriku.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@Composable
fun StatsScreen(vm: MainViewModel) {
    val trend by vm.trend.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadStats() }

    val target = settings.dailyTargetKcal
    val avg = if (trend.isEmpty()) 0 else trend.map { it.kcal }.average().toInt()
    val best = trend.maxByOrNull { it.kcal }
    val mealTotals = MealType.entries.associateWith { meal ->
        summaries.sumOf { it.byMeal[meal] ?: 0 }
    }
    val mealTotalSum = mealTotals.values.sum()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column {
                SectionLabel("14 hari terakhir")
                Spacer(Modifier.height(4.dp))
                Text("Statistik kalori", style = MaterialTheme.typography.headlineMedium)
            }
        }

        item {
            CalorieBarChart(trend = trend, target = target)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatBlock("rata-rata/hari", "$avg", modifier = Modifier.weight(1f))
                StatBlock("tertinggi", "${best?.kcal ?: 0}", modifier = Modifier.weight(1f))
                StatBlock("hari tercatat", "${trend.count { it.entries > 0 }}", modifier = Modifier.weight(1f))
            }
        }

        item { SectionLabel("Sebaran waktu makan") }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProportionBar(
                    segments = MealType.entries.map { meal ->
                        meal.label to Triple(mealTotals[meal] ?: 0, 0, mealColor(meal))
                    },
                    total = mealTotalSum,
                )
                MealType.entries.forEach { meal ->
                    val kcal = mealTotals[meal] ?: 0
                    val pct = if (mealTotalSum == 0) 0 else (kcal * 100 / mealTotalSum)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Canvas(modifier = Modifier.size(10.dp)) { drawRect(color = mealColor(meal)) }
                            Text(meal.label, style = MaterialTheme.typography.bodyLarge)
                        }
                        Text(
                            "$kcal kkal · $pct%",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        item { SectionLabel("Makanan penyumbang kalori terbanyak") }

        item {
            val topFoods by vm.topFoods.collectAsStateWithLifecycle()
            if (topFoods.isEmpty()) {
                Text(
                    text = "Belum cukup data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val maxKcal = topFoods.maxOf { it.second }.coerceAtLeast(1)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    topFoods.forEach { (name, kcal) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text("$kcal kkal", style = MaterialTheme.typography.titleMedium)
                            }
                            ProportionBar(
                                segments = listOf(
                                    name to Triple(kcal, 0, MaterialTheme.colorScheme.primary),
                                ),
                                total = maxKcal,
                            )
                        }
                    }
                }
            }
        }

        item { SectionLabel("Makanan lokal") }

        item {
            val totalKcal = summaries.sumOf { it.totalKcal }
            val localKcal = summaries.sumOf { it.localKcal }
            val share = if (totalKcal == 0) 0 else (localKcal * 100 / totalKcal)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
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
                "garis = target $target kkal",
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
