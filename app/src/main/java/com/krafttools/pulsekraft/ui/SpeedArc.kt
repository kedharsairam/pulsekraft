package com.krafttools.pulsekraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.krafttools.pulsekraft.net.LiveState
import com.krafttools.pulsekraft.net.Reading
import com.krafttools.pulsekraft.ui.theme.PulsePalette
import kotlin.math.min

/**
 * A sweeping arc gauge with a peak marker, and the reading inside it.
 *
 * The vocabulary is the same as the rest of the family: an arc, a
 * graduations line, and a figure in the middle. What is new here is the
 * **peak marker**, and it earns its place for a reason specific to this
 * app. A speed test's most useful single fact is the best the line ever
 * did, not the number it happened to be showing when the screenshot was
 * taken — so the dial remembers where it peaked while it is still
 * showing where it is.
 *
 * ## The scale does not move
 *
 * [LiveState.scaleMax] is fixed for the whole run. A gauge that
 * rescales as the value climbs makes a rising needle look flat and a
 * falling one look dramatic, and an instrument whose scale shifts under
 * the needle is misleading whoever is reading it. The top of the dial
 * is the top of the dial.
 */
@Composable
fun SpeedArc(
    live: LiveState,
    modifier: Modifier = Modifier,
) {
    // A linear dial is the wrong shape for throughput, because speed
    // spans orders of magnitude: 5 Mbps and 500 Mbps differ by a
    // hundredfold and a linear scale puts the first at one percent of
    // the dial. So throughput is logarithmic — decades are equally far
    // apart, which is how a speed actually behaves — and latency stays
    // linear, because 0-200 ms is a narrow range where linear is the
    // easier read.
    //
    // The exact value is always printed in the middle, so the scale is
    // for shape and the number is for precision. A log dial nobody could
    // read precisely would be a worse instrument than a linear one, and
    // this is the compromise that keeps both.
    val logarithmic = live.reading != Reading.LATENCY
    fun place(value: Double): Float {
        if (live.scaleMax <= 0.0 || value <= 0.0) return 0f
        val fraction = if (logarithmic) {
            kotlin.math.log10(1.0 + value) / kotlin.math.log10(1.0 + live.scaleMax)
        } else {
            value / live.scaleMax
        }
        return fraction.coerceIn(0.0, 1.0).toFloat()
    }
    val fraction = place(live.current)
    val peakFraction = place(live.peak)
    val offScale = live.reading != Reading.LATENCY &&
        live.peak > live.scaleMax
    val colour = when (live.reading) {
        Reading.LATENCY -> PulsePalette.Pulse
        else -> PulsePalette.Primary
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(232.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(232.dp)) {
            val stroke = size.minDimension * 0.11f
            val inset = stroke * 0.9f
            val arcSize = Size(
                size.width - inset * 2 - stroke,
                size.width - inset * 2 - stroke,
            )
            val topLeft = Offset(
                (size.width - arcSize.width) / 2f,
                (size.width - arcSize.height) / 2f,
            )

            // The track. Present, never louder than the data.
            drawArc(
                color = PulsePalette.GridLine,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            // The value.
            if (fraction > 0f) {
                drawArc(
                    color = colour,
                    startAngle = START_ANGLE,
                    sweepAngle = SWEEP_ANGLE * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // Graduations at a quarter, a half, three quarters and full.
            val centre = Offset(size.width / 2f, size.width / 2f)
            val radius = arcSize.width / 2f + stroke * 0.05f
            for (mark in 1..4) {
                val angle = Math.toRadians((START_ANGLE + SWEEP_ANGLE * mark / 4f).toDouble())
                val outer = Offset(
                    centre.x + (radius + stroke * 0.75f) * kotlin.math.cos(angle).toFloat(),
                    centre.y + (radius + stroke * 0.75f) * kotlin.math.sin(angle).toFloat(),
                )
                val inner = Offset(
                    centre.x + (radius + stroke * 0.25f) * kotlin.math.cos(angle).toFloat(),
                    centre.y + (radius + stroke * 0.25f) * kotlin.math.sin(angle).toFloat(),
                )
                drawLine(
                    color = PulsePalette.GridLine,
                    start = inner,
                    end = outer,
                    strokeWidth = 2f,
                )
            }

            // The peak, held while the needle keeps moving.
            if (peakFraction > 0.01f) {
                val angle = Math.toRadians(
                    (START_ANGLE + SWEEP_ANGLE * peakFraction).toDouble(),
                )
                val outer = Offset(
                    centre.x + (radius - stroke * 0.2f) * kotlin.math.cos(angle).toFloat(),
                    centre.y + (radius - stroke * 0.2f) * kotlin.math.sin(angle).toFloat(),
                )
                val inner = Offset(
                    centre.x + (radius - stroke * 1.1f) * kotlin.math.cos(angle).toFloat(),
                    centre.y + (radius - stroke * 1.1f) * kotlin.math.sin(angle).toFloat(),
                )
                drawLine(
                    color = PulsePalette.Warning,
                    start = inner,
                    end = outer,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            // Pushed below the centre of the dial so the figure sits in
            // the open part of the arc rather than behind the sweep.
            modifier = Modifier.padding(top = 56.dp),
        ) {
            Text(
                text = formatReading(live),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = colour,
                textAlign = TextAlign.Center,
            )
            Text(
                text = live.reading.unit,
                style = MaterialTheme.typography.titleSmall,
                color = PulsePalette.OnSurfaceVariant,
            )
            if (live.peak > 0.0) {
                Text(
                    text = buildString {
                        append("peak ")
                        append(formatReading(live.copy(current = live.peak)))
                        // A peak beyond the top of the dial is a real
                        // result, and silently clamping it would make a
                        // 1.9 Gbps burst look like a 600 Mbps one.
                        if (offScale) append("  > scale")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = PulsePalette.Warning,
                )
            }
        }
    }
}

/** The scale's two ends, under the dial. */
@Composable
fun ScaleLegend(
    live: LiveState,
    modifier: Modifier = Modifier,
) {
    Row2(
        modifier = modifier.fillMaxWidth().padding(horizontal = 44.dp),
        left = "0",
        right = formatCeiling(live.scaleMax) + " " + live.reading.unit,
    )
}

private fun formatCeiling(value: Double): String =
    if (value >= 100) "%.0f".format(value) else "%.0f".format(value)

private fun formatReading(live: LiveState): String = when (live.reading) {
    Reading.LATENCY -> "%.0f".format(live.current)
    else -> "%.0f".format(live.current)
}

@Composable
private fun Row2(modifier: Modifier, left: String, right: String) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = left,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = PulsePalette.OnSurfaceVariant,
        )
        Text(
            text = right,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

/** 260 degrees, starting at the lower left — a gauge, not a pie. */
private const val START_ANGLE = 140f
private const val SWEEP_ANGLE = 260f
