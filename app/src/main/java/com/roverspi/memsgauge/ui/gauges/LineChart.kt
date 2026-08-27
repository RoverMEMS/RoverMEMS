package com.roverspi.memsgauge.ui.gauges

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/**
 * Minimal line sparkline modeled after MEMSFCR's per-metric Charts tab.
 * Auto-scales to the min/max of [values] so both a flat simulator idle and
 * a noisier real signal stay readable, with 目盛り (three gridlines + value
 * labels: min/mid/max) on the left so the reader isn't left guessing what
 * the line's height actually means.
 *
 * [timeAxisLabels], when non-null/non-empty, adds a bottom strip of evenly
 * spaced clock-time labels (used by the saved-log replay screen, where the
 * X axis is real elapsed time). The live in-app Charts view doesn't pass
 * this -- a rolling in-memory window doesn't have a stable time axis worth
 * labeling, so it stays unlabeled as before.
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    axisLabelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    timeAxisLabels: List<String>? = null
) {
    val axisPaint = remember(axisLabelColor) {
        Paint().apply {
            this.color = axisLabelColor.toArgb()
            textSize = 26f
            isAntiAlias = true
        }
    }
    val timeAxisPaint = remember(axisLabelColor) {
        Paint().apply {
            this.color = axisLabelColor.toArgb()
            textSize = 24f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val gridColor = axisLabelColor.copy(alpha = 0.3f)
    val hasTimeAxis = !timeAxisLabels.isNullOrEmpty()
    val timeStripHeightDp = 20.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(if (hasTimeAxis) 80.dp + timeStripHeightDp else 80.dp)
    ) {
        if (values.size < 2) return@Canvas

        val timeStripPx = if (hasTimeAxis) timeStripHeightDp.toPx() else 0f
        val plotHeight = size.height - timeStripPx

        val minValue = values.min()
        val maxValue = values.max()
        val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
        val axisWidth = 52.dp.toPx()
        val chartWidth = size.width - axisWidth
        val stepX = if (values.size > 1) chartWidth / (values.size - 1) else 0f

        // 目盛り: gridlines + value labels at min / mid / max.
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = plotHeight - (fraction * plotHeight)
            drawLine(
                color = gridColor,
                start = Offset(axisWidth, y),
                end = Offset(size.width, y),
                strokeWidth = 1f
            )
            val labelValue = minValue + fraction * range
            drawContext.canvas.nativeCanvas.drawText(
                formatAxisLabel(labelValue),
                4f,
                (y + 9f).coerceIn(20f, plotHeight),
                axisPaint
            )
        }

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = axisWidth + index * stepX
            val normalized = (value - minValue) / range
            val y = plotHeight - (normalized * plotHeight)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = color, style = Stroke(width = 3f))

        if (hasTimeAxis) {
            val labels = timeAxisLabels!!
            val labelY = plotHeight + timeStripPx * 0.75f
            labels.forEachIndexed { i, label ->
                val fraction = if (labels.size > 1) i.toFloat() / (labels.size - 1) else 0f
                val x = axisWidth + fraction * chartWidth
                drawContext.canvas.nativeCanvas.drawText(label, x, labelY, timeAxisPaint)
            }
        }
    }
}

private fun formatAxisLabel(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
