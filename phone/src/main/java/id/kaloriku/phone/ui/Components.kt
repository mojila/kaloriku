package id.kaloriku.phone.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A plain calorie progress ring. Solid stroke, no gradient. */
@Composable
fun CalorieRing(
    progress: Float,
    total: Int,
    target: Int,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 200.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val accent = if (progress >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = total.toString(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "dari $target kkal",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The standard grouped container for a section of content.
 *
 * A single flat surface with a hairline border and no shadow or gradient: sections are
 * separated by whitespace and one boundary, never by nested cards. Content inside is
 * spaced by [Arrangement.spacedBy] so callers do not hand-place padding.
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 18.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** A simple labelled number block, separated by hairlines rather than boxes. */
@Composable
fun StatBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Horizontal proportion bar used in the statistics breakdowns. */
@Composable
fun ProportionBar(
    segments: List<Pair<String, Triple<Int, Int, Color>>>,
    total: Int,
    modifier: Modifier = Modifier,
) {
    if (total <= 0) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(10.dp),
        )
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEach { (_, triple) ->
            val (kcal, _, color) = triple
            val weight = kcal.toFloat() / total
            if (weight > 0f) {
                Canvas(modifier = Modifier.weight(weight).height(10.dp)) {
                    drawRect(color = color)
                }
            }
        }
    }
}

/** A single labelled row with a right-aligned value. */
@Composable
fun LabeledRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = valueColor,
        )
    }
}

/** Section heading with a small uppercase label. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        textAlign = TextAlign.Start,
    )
}

/**
 * One headline metric: a large value over a quiet caption.
 *
 * Deliberately borderless and background-free so a row of these reads as one band of
 * numbers rather than three separate boxes. Separation comes from [Row] spacing.
 */
@Composable
fun Metric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A labelled row with a proportional bar underneath, for ranked breakdowns.
 *
 * The bar encodes [value] against [total]; a zero total renders an empty track rather
 * than a divide-by-zero. The value text is the accessible source of truth, so the bar
 * is decoration on top of a number that is always present.
 */
@Composable
fun RankedRow(
    label: String,
    value: Int,
    total: Int,
    valueText: String,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val fraction = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(barColor),
                )
            }
        }
    }
}

/**
 * A quiet inline note: a single tinted container for the one message that matters on a
 * screen. [emphasis] selects the alert palette, otherwise the neutral accent is used.
 */
@Composable
fun Callout(
    text: String,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val container = if (emphasis) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val content = if (emphasis) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    androidx.compose.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
        }
    }
}

/** A hairline divider that matches the outline token used by every card boundary. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * Indonesian display label for a Jev macro-profile key.
 *
 * The keys (`karbohidrat`, `protein`, `lemak`, `serat`, `seimbang`, `tidak_jelas`)
 * are decided by Jev in `FoodAnalyzer`; this mapping is presentation only and does
 * not re-judge the value. An unknown key is passed through unchanged so a future Jev
 * option still renders something honest instead of being silently dropped.
 */
fun macroLabel(rawKey: String): String = when (rawKey) {
    "karbohidrat" -> "Karbohidrat"
    "protein" -> "Protein"
    "lemak" -> "Lemak"
    "serat" -> "Serat"
    "seimbang" -> "Seimbang"
    "tidak_jelas" -> "Tidak jelas"
    else -> rawKey
}

/**
 * Small macro badge for a food row.
 *
 * Renders nothing when [macroProfile] is null: a row whose macro was never decided
 * (for example one loaded from Room after a restart) must not imply a judgement Jev
 * never made. When Jev explicitly answered `tidak_jelas`, that is shown as
 * "Tidak jelas" because the answer itself is information.
 */
@Composable
fun MacroBadge(macroProfile: String?, modifier: Modifier = Modifier) {
    if (macroProfile.isNullOrBlank()) return
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.45f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = macroLabel(macroProfile),
            style = MaterialTheme.typography.labelSmall,
            color = accent,
        )
    }
}
