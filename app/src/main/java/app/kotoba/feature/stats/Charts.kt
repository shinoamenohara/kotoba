package app.kotoba.feature.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kotoba.core.ui.theme.LocalUserSettings
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** A line on a [LineChart]; null values break the line (e.g. days without answers). */
data class LineSeries(val label: String, val values: List<Float?>, val color: Color, val fill: Boolean = false)

/** One stacked bar; segments are drawn bottom-up. [marker] draws a dot above the bar (e.g. goal reached). */
data class BarStack(val segments: List<Pair<Float, Color>>, val marker: Boolean = false) {
    val total: Float get() = segments.sumOf { it.first.toDouble() }.toFloat()
}

/** Rounds up to 1/2/5 × 10ⁿ so axis labels stay readable. */
internal fun niceMax(value: Float): Float {
    if (value <= 0f) return 1f
    val exp = 10.0.pow(ceil(log10(value.toDouble())) - 1).toFloat()
    val f = value / exp
    val nice = when {
        f <= 1f -> 1f
        f <= 2f -> 2f
        f <= 5f -> 5f
        else -> 10f
    }
    return nice * exp
}

@Composable
private fun rememberReveal(key: Any?): Animatable<Float, *> {
    val animate = LocalUserSettings.current.animations
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key, animate) {
        if (animate) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        } else {
            progress.snapTo(1f)
        }
    }
    return progress
}

private class Axis(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/** Draws y grid + labels and x labels; returns the plotting area. */
private fun DrawScope.drawAxes(
    measurer: TextMeasurer,
    style: TextStyle,
    gridColor: Color,
    yMax: Float,
    yFormat: (Float) -> String,
    xLabels: List<String>,
    slotCentered: Boolean,
): Axis {
    val ticks = listOf(0f, yMax / 2f, yMax)
    val yTexts = ticks.map { measurer.measure(yFormat(it), style) }
    val labelW = yTexts.maxOf { it.size.width }.toFloat()
    val xTextH = if (xLabels.isEmpty()) 0f else measurer.measure("0", style).size.height.toFloat()
    val gap = 6.dp.toPx()
    val area = Axis(labelW + gap, yTexts.last().size.height / 2f, size.width, size.height - xTextH - gap)

    ticks.forEachIndexed { i, t ->
        val y = area.bottom - area.height * (t / yMax)
        drawLine(
            gridColor, Offset(area.left, y), Offset(area.right, y), strokeWidth = 1.dp.toPx(),
            pathEffect = if (i == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )
        val tl = yTexts[i]
        drawText(tl, topLeft = Offset(labelW - tl.size.width, y - tl.size.height / 2f))
    }

    val n = xLabels.size
    if (n > 0) {
        // At most ~5 labels so they never overlap on phones.
        val step = max(1, ceil(n / 5f).toInt())
        val indices = (0 until n step step).toMutableList()
        if (indices.last() != n - 1 && (n - 1 - indices.last()) >= step / 2f) indices += n - 1
        indices.forEach { i ->
            val x = xFor(i, n, area, slotCentered)
            val tl = measurer.measure(xLabels[i], style)
            val tx = (x - tl.size.width / 2f).coerceIn(area.left, size.width - tl.size.width)
            drawText(tl, topLeft = Offset(tx, area.bottom + gap))
        }
    }
    return area
}

private fun xFor(i: Int, n: Int, area: Axis, slotCentered: Boolean): Float = when {
    slotCentered -> area.left + area.width * (i + 0.5f) / n
    n <= 1 -> area.left + area.width / 2f
    else -> area.left + area.width * i / (n - 1)
}

@Composable
fun LineChart(
    series: List<LineSeries>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    fixedMax: Float? = null,
    yFormat: (Float) -> String = { it.roundToInt().toString() },
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val reveal = rememberReveal(series)
    val dataMax = series.flatMap { it.values }.filterNotNull().maxOrNull() ?: 0f
    val yMax = fixedMax ?: niceMax(dataMax)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val area = drawAxes(measurer, style, grid, yMax, yFormat, xLabels, slotCentered = false)
        clipRect(left = 0f, top = 0f, right = area.left + area.width * reveal.value + 4.dp.toPx(), bottom = size.height) {
            series.forEach { s ->
                val n = s.values.size
                val pts = s.values.mapIndexed { i, v ->
                    v?.let { Offset(xFor(i, n, area, false), area.bottom - area.height * (it / yMax).coerceIn(0f, 1f)) }
                }
                val line = Path()
                var started = false
                pts.forEach { p ->
                    if (p == null) started = false
                    else if (!started) { line.moveTo(p.x, p.y); started = true } else line.lineTo(p.x, p.y)
                }
                val nonNull = pts.filterNotNull()
                if (s.fill && nonNull.size > 1 && nonNull.size == pts.size) {
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(nonNull.last().x, area.bottom)
                        lineTo(nonNull.first().x, area.bottom)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.28f), Color.Transparent), area.top, area.bottom))
                }
                drawPath(line, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                // Isolated points (neighbours missing) would be invisible without a dot.
                pts.forEachIndexed { i, p ->
                    if (p != null && (n <= 31 || pts.getOrNull(i - 1) == null && pts.getOrNull(i + 1) == null)) {
                        drawCircle(s.color, radius = if (n <= 31) 3.dp.toPx() else 2.dp.toPx(), center = p)
                    }
                }
            }
        }
    }
}

@Composable
fun BarChart(
    bars: List<BarStack>,
    xLabels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    markerColor: Color = MaterialTheme.colorScheme.tertiary,
    yFormat: (Float) -> String = { it.roundToInt().toString() },
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val reveal = rememberReveal(bars)
    val yMax = niceMax(bars.maxOfOrNull { it.total } ?: 0f)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val area = drawAxes(measurer, style, grid, yMax, yFormat, xLabels, slotCentered = true)
        val n = bars.size.coerceAtLeast(1)
        val slot = area.width / n
        val barW = (slot * 0.62f).coerceAtMost(28.dp.toPx())
        val radius = CornerRadius((barW / 4f).coerceAtMost(4.dp.toPx()))
        bars.forEachIndexed { i, bar ->
            val cx = xFor(i, n, area, true)
            var y = area.bottom
            bar.segments.forEach { (v, color) ->
                if (v <= 0f) return@forEach
                val h = area.height * (v / yMax) * reveal.value
                drawRoundRect(color, Offset(cx - barW / 2f, y - h), Size(barW, h), radius)
                y -= h
            }
            if (bar.marker) {
                drawCircle(markerColor, radius = (barW / 5f).coerceIn(2.dp.toPx(), 4.dp.toPx()), center = Offset(cx, y - 6.dp.toPx()), alpha = reveal.value)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
