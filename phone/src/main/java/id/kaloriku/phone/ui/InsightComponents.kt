package id.kaloriku.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import id.kaloriku.phone.DayRow
import id.kaloriku.phone.ui.theme.KaloriColors
import id.kaloriku.shared.domain.JakartaTime

/**
 * Indonesian quality label for the 0-4 Jev health score.
 *
 * Jev decides the score; this only names the band so the number is readable at a
 * glance. A zero score means Jev never scored a logged day, which is an empty state
 * rather than a judgement of "bad".
 */
internal fun healthLabel(score: Double): String = when {
    score <= 0.0 -> "Belum ada data"
    score < 2.0 -> "Perlu perbaikan"
    score < 3.0 -> "Cukup"
    score < 3.5 -> "Baik"
    else -> "Sangat baik"
}

/**
 * Colour for a health-score band. Alert below "cukup", accent from "baik" up, and the
 * uncertain middle stays muted so the accent is reserved for genuinely good results.
 */
@Composable
internal fun healthColor(score: Double): Color = when {
    score <= 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
    score < 2.0 -> MaterialTheme.colorScheme.error
    score < 3.0 -> KaloriColors.MealMalam
    else -> MaterialTheme.colorScheme.primary
}

/** Signed kcal delta relative to the target: "+320", "\u2212180" (true minus) or "tepat". */
internal fun deltaText(deltaKcal: Int): String = when {
    deltaKcal > 0 -> "+$deltaKcal"
    deltaKcal < 0 -> "\u2212${-deltaKcal}"
    else -> "tepat"
}

/**
 * The trend entry count beside a day's calories.
 *
 * A day with no entries has no measured kcal, so it reports "belum dicatat" rather
 * than a zero that would read as a real total.
 */
internal fun entryText(entries: Int): String =
    if (entries <= 0) "belum dicatat" else "$entries catatan"

/**
 * One row of the daily trend list: day label, kcal, entry count and signed delta, with
 * a proportional bar sized against the busiest day in the window.
 *
 * The bar delegates to [RankedRow], so the divide-by-zero guard and the track styling
 * live in one place. A day with no entries is rendered as a quiet label only, because
 * "0 kkal \u22122000" would misrepresent a day that was simply never logged.
 */
@Composable
internal fun TrendRow(
    row: DayRow,
    maxKcal: Int,
    modifier: Modifier = Modifier,
) {
    if (!row.hasData) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = JakartaTime.label(row.dayKey),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "belum dicatat",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    RankedRow(
        label = "${JakartaTime.label(row.dayKey)} \u00b7 ${entryText(row.entries)}",
        value = row.kcal,
        total = maxKcal,
        valueText = "${row.kcal} kkal  ${deltaText(row.deltaKcal)}",
        modifier = modifier,
        barColor = if (row.isOver) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

/**
 * A short explanatory line under a headline number. Small and muted so it never
 * competes with the value above it.
 */
@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
