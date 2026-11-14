package dev.fughetta.dshcompose.ui.dynamicui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fughetta.dshcompose.feature.chat.R
import kotlin.math.*

internal data class UiChartScale(val divisor: Double, val low: Double, val high: Double) {
    fun fraction(value: Double): Float =
        ((value / divisor - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
}

internal fun chartScale(chart: UiChart): UiChartScale {
    return chartScale(chart.series.flatMap { it.values })
}

internal fun chartScale(points: List<Double?>): UiChartScale {
    val values = points.filterNotNull()
    val divisor = values.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    val low = min(0.0, values.minOfOrNull { it / divisor } ?: 0.0)
    val high = max(0.0, values.maxOfOrNull { it / divisor } ?: 0.0)
    return UiChartScale(divisor, low, if (high == low) low + 1.0 else high)
}

internal fun donutWeights(chart: UiChart): List<Float> {
    val values = chart.series.first().values
    val maximum =
        values.filterNotNull().filter { it > 0 }.maxOrNull() ?: return List(values.size) { 0f }
    val normalized = values.map { if (it != null && it > 0) it / maximum else 0.0 }
    val total = normalized.sum()
    return normalized.map { (it / total).toFloat() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DynamicUiChart(chart: UiChart) {
    val colors = MaterialTheme.colorScheme
    val palette =
        listOf(
            colors.primary,
            colors.tertiary,
            colors.secondary,
            colors.error,
            androidx.compose.ui.graphics.lerp(colors.primary, colors.onSurface, 0.3f),
            androidx.compose.ui.graphics.lerp(colors.tertiary, colors.onSurface, 0.3f),
        )
    val scale = remember(chart) { chartScale(chart) }
    val weights =
        remember(chart) {
            if (chart.kind == UiChartKind.Donut) donutWeights(chart) else emptyList()
        }
    var selected by remember(chart) { mutableIntStateOf(0) }
    var table by remember(chart) { mutableStateOf(false) }
    val kind =
        stringResource(
            when (chart.kind) {
                UiChartKind.Bar -> R.string.view_chart_bar
                UiChartKind.Horizontal -> R.string.view_chart_horizontal
                UiChartKind.Line -> R.string.view_chart_line
                UiChartKind.Donut -> R.string.view_chart_donut
            },
        )
    val seriesNames =
        chart.series.mapIndexed { index, series ->
            series.name ?: stringResource(R.string.view_chart_series, index + 1)
        }
    val values =
        remember(chart, seriesNames, selected) {
            chart.series.mapIndexed { index, series ->
                "${seriesNames[index]}: ${series.values[selected]?.let(::uiNumber) ?: "—"}${chart.unit.orEmpty()}"
            }
        }
    val description = "$kind. ${chart.categories[selected]}. ${values.joinToString(". ")}"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(kind, style = MaterialTheme.typography.labelLarge)
        chart.unit?.let {
            Text(
                stringResource(R.string.view_chart_unit, it),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (chart.kind == UiChartKind.Horizontal)
            Text(
                "${uiNumber(scale.low * scale.divisor)} — ${uiNumber(scale.high * scale.divisor)}",
                style = MaterialTheme.typography.labelSmall,
            )
        else if (chart.kind != UiChartKind.Donut)
            Text(uiNumber(scale.high * scale.divisor), style = MaterialTheme.typography.labelSmall)
        val plotHeight =
            if (chart.kind == UiChartKind.Horizontal) max(220, chart.categories.size * 28) else 220
        val scroll = rememberScrollState()
        Box(
            Modifier.fillMaxWidth()
                .heightIn(max = 220.dp)
                .then(
                    if (chart.kind == UiChartKind.Horizontal) Modifier.verticalScroll(scroll)
                    else Modifier,
                ),
        ) {
            Canvas(
                Modifier.fillMaxWidth()
                    .height(plotHeight.dp)
                    .semantics { contentDescription = description }
                    .pointerInput(chart) {
                        detectTapGestures { point ->
                            selected =
                                when (chart.kind) {
                                    UiChartKind.Donut -> {
                                        val angle =
                                            ((atan2(
                                                point.y - size.height / 2f,
                                                point.x - size.width / 2f,
                                            ) * 180f / PI.toFloat()) + 450f) % 360f
                                        var sum = 0f
                                        weights
                                            .indexOfFirst {
                                                sum += it * 360f
                                                angle < sum
                                            }
                                            .coerceAtLeast(0)
                                    }
                                    UiChartKind.Horizontal ->
                                        floor(point.y / size.height * chart.categories.size)
                                            .toInt()
                                            .coerceIn(chart.categories.indices)
                                    else ->
                                        floor(point.x / size.width * chart.categories.size)
                                            .toInt()
                                            .coerceIn(chart.categories.indices)
                                }
                        }
                    },
            ) {
                val width = size.width
                val height = size.height
                when (chart.kind) {
                    UiChartKind.Donut -> {
                        val diameter = min(width, height) - 28.dp.toPx()
                        val top = Offset((width - diameter) / 2, (height - diameter) / 2)
                        var angle = -90f
                        if (weights.all { it == 0f })
                            drawCircle(
                                colors.outlineVariant,
                                diameter / 2,
                                style = Stroke(20.dp.toPx()),
                            )
                        for ((index, weight) in weights.withIndex()) {
                            val sweep = weight * 360f
                            if (sweep > 0f)
                                drawArc(
                                    palette[index % palette.size],
                                    angle,
                                    sweep,
                                    false,
                                    top,
                                    Size(diameter, diameter),
                                    style =
                                        Stroke((if (index == selected) 25.dp else 20.dp).toPx()),
                                )
                            angle += sweep
                        }
                    }
                    UiChartKind.Horizontal -> {
                        val zero = width * scale.fraction(0.0)
                        drawLine(colors.outlineVariant, Offset(zero, 0f), Offset(zero, height))
                        val band = height / chart.categories.size
                        val bar = band * 0.8f / chart.series.size
                        for ((seriesIndex, series) in chart.series.withIndex()) for ((
                            index, value) in series.values.withIndex()) {
                            if (value == null) continue
                            val x = width * scale.fraction(value)
                            drawRect(
                                palette[seriesIndex % palette.size].copy(
                                    alpha = if (index == selected) 1f else 0.7f,
                                ),
                                Offset(
                                    min(zero, x),
                                    index * band + band * 0.1f + seriesIndex * bar,
                                ),
                                Size(abs(x - zero), bar.coerceAtLeast(0.5f)),
                            )
                        }
                    }
                    UiChartKind.Bar,
                    UiChartKind.Line -> {
                        val zero = height * (1 - scale.fraction(0.0))
                        for (fraction in listOf(0f, 0.5f, 1f)) drawLine(
                            colors.outlineVariant,
                            Offset(0f, height * fraction),
                            Offset(width, height * fraction),
                        )
                        drawLine(colors.outline, Offset(0f, zero), Offset(width, zero))
                        val band = width / chart.categories.size
                        for ((seriesIndex, series) in chart.series.withIndex()) {
                            val color = palette[seriesIndex % palette.size]
                            if (chart.kind == UiChartKind.Bar) {
                                val bar = band * 0.8f / chart.series.size
                                for ((index, value) in series.values.withIndex()) {
                                    if (value == null) continue
                                    val y = height * (1 - scale.fraction(value))
                                    drawRect(
                                        color.copy(alpha = if (index == selected) 1f else 0.7f),
                                        Offset(
                                            index * band + band * 0.1f + seriesIndex * bar,
                                            min(zero, y),
                                        ),
                                        Size(bar.coerceAtLeast(0.5f), abs(y - zero)),
                                    )
                                }
                            } else {
                                val path = Path()
                                var connected = false
                                for ((index, value) in series.values.withIndex()) {
                                    if (value == null) {
                                        connected = false
                                        continue
                                    }
                                    val x = (index + 0.5f) * band
                                    val y = height * (1 - scale.fraction(value))
                                    if (connected) path.lineTo(x, y) else path.moveTo(x, y)
                                    connected = true
                                    drawCircle(
                                        color,
                                        if (index == selected) 4.dp.toPx() else 2.dp.toPx(),
                                        Offset(x, y),
                                    )
                                }
                                drawPath(path, color, style = Stroke(2.dp.toPx()))
                            }
                        }
                    }
                }
            }
        }
        if (chart.kind in setOf(UiChartKind.Bar, UiChartKind.Line))
            Text(uiNumber(scale.low * scale.divisor), style = MaterialTheme.typography.labelSmall)
        Text(chart.categories[selected], style = MaterialTheme.typography.titleSmall)
        for ((index, value) in values.withIndex()) Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color =
                palette[(if (chart.kind == UiChartKind.Donut) selected else index) % palette.size],
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { selected-- }, enabled = selected > 0) {
                Text(stringResource(R.string.view_chart_previous))
            }
            TextButton(onClick = { selected++ }, enabled = selected < chart.categories.lastIndex) {
                Text(stringResource(R.string.view_chart_next))
            }
            TextButton(onClick = { table = !table }) {
                Text(
                    stringResource(
                        if (table) R.string.view_chart_hide_data else R.string.view_chart_data,
                    ),
                )
            }
        }
        if (table)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                itemsIndexed(chart.categories) { index, category ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(category, style = MaterialTheme.typography.labelLarge)
                        for ((seriesIndex, series) in chart.series.withIndex()) Text(
                            "${seriesNames[seriesIndex]}: ${series.values[index]?.let(::uiNumber) ?: "—"}${chart.unit.orEmpty()}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider()
                }
            }
        chart.caption?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
