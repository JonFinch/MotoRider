package com.motorider.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.motorider.ui.theme.BrandBlue

/** Top of the dial. A bike will not see the far end of it, but the scale stays readable. */
private const val MAX_DISPLAY_SPEED = 200

/**
 * Small speed dial for the navigation overlay, where the map needs the space.
 *
 * The number sits inside the dial rather than beside it so the whole readout is one
 * glance-sized target - a rider reads this at 60 mph, not studies it.
 */
@Composable
fun CompactSpeedometer(
    currentSpeed: Float,
    useMiles: Boolean = false,
    modifier: Modifier = Modifier
) {
    val perUnit = if (useMiles) 2.236936f else 3.6f
    val displaySpeed = (currentSpeed * perUnit).toInt().coerceAtLeast(0)

    Box(modifier = modifier.size(84.dp), contentAlignment = Alignment.Center) {
        SpeedGauge(
            speed = displaySpeed.toFloat(),
            maxSpeed = MAX_DISPLAY_SPEED.toFloat(),
            speedColor = BrandBlue,
            modifier = Modifier.fillMaxSize()
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$displaySpeed",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (useMiles) "mph" else "km/h",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpeedGauge(
    speed: Float,
    maxSpeed: Float,
    speedColor: Color,
    modifier: Modifier = Modifier
) {
    val bgArcColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    val tickColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(modifier = modifier) {
        val strokeWidth = 8.dp.toPx()
        val radius = (size.minDimension / 2f) * 0.85f
        val center = Offset(size.width / 2f, size.height / 2f)
        val startAngle = 135f
        val sweepAngle = 270f
        val arcSize = Size(2 * radius, 2 * radius)
        val arcTopLeft = Offset(center.x - radius, center.y - radius)

        drawArc(
            color = bgArcColor,
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            size = arcSize,
            topLeft = arcTopLeft,
            style = Stroke(width = strokeWidth)
        )

        val currentSweep = (speed / maxSpeed).coerceIn(0f, 1f) * sweepAngle
        if (currentSweep > 0f) {
            drawArc(
                color = speedColor,
                startAngle = startAngle,
                sweepAngle = currentSweep,
                useCenter = false,
                size = arcSize,
                topLeft = arcTopLeft,
                style = Stroke(width = strokeWidth)
            )
        }

        // Ticks share the arc's angle convention: 0° at 3 o'clock, growing clockwise.
        for (i in 0..10) {
            val tickAngle = startAngle + (i / 10f) * sweepAngle
            val isMajor = i % 2 == 0
            val tickLength = if (isMajor) 10.dp.toPx() else 5.dp.toPx()
            val outerR = radius - strokeWidth
            val innerR = outerR - tickLength

            val rad = Math.toRadians(tickAngle.toDouble())
            val cos = Math.cos(rad).toFloat()
            val sin = Math.sin(rad).toFloat()

            drawLine(
                color = tickColor,
                start = Offset(center.x + innerR * cos, center.y + innerR * sin),
                end = Offset(center.x + outerR * cos, center.y + outerR * sin),
                strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx()
            )
        }
    }
}
