package com.krafttools.pulsekraft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.krafttools.pulsekraft.ui.theme.PulsePalette
import kotlin.math.abs

/**
 * A trace that draws itself while the test runs.
 *
 * Two things it does that a plain line chart would not:
 *
 * **The head of the line is at the right edge and the history scrolls
 * left**, so the newest reading is always in the same place and the eye
 * does not have to hunt for it. A trace that scrolls the other way — or
 * grows rightwards, pushing the present off the edge — is a chart to
 * read, not an instrument to watch.
 *
 * **The scale is fixed, and the head is clamped to it.** A spike that
 * rescales the plot looks identical to a spike that does not, which
 * defeats the purpose of drawing the line at all. Values above the
 * ceiling are pinned to the top edge and the trace is drawn hollow,
 * which says "off the scale" without a number nobody could read anyway.
 */
/**
 * The trace, sized by its container rather than by a constant.
 *
 * This is the hero of the running screen and it takes whatever height
 * the layout gives it — most of the viewport. A strip 72dp tall on an
 * 870dp screen is a chart in a letterbox, and a two-second transfer
 * squeezed into a letterbox cannot be read at all: the whole point of
 * the trace is that the *shape* is legible, and shape needs height.
 */
@Composable
fun LiveTrace(
    values: List<Double>,
    ceiling: Double,
    lineColour: Color,
    modifier: Modifier = Modifier,
    label: String? = null,
    overCeiling: Boolean = false,
    /**
     * Plot the axis logarithmically.
     *
     * The same reasoning as the dial, and it was missed here first,
     * which showed up immediately: a 22 Mbps upload on a linear
     * 0-2000 plot is one percent of the height, so the trace sat on
     * the floor with three quarters of the screen empty above it. The
     * data was correct and the presentation made it look like nothing
     * happened.
     *
     * Latency stays linear, because 0-200 ms is a narrow range where
     * linear is the easier read and a log axis would exaggerate the
     * jitter that matters.
     */
    logarithmic: Boolean = false,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // The gridline at the ceiling, dashed so it reads as a bound
            // rather than as a measurement.
            val top = 2f
            val bottom = size.height - 2f
            drawLine(
                color = PulsePalette.GridLine,
                start = Offset(0f, top),
                end = Offset(size.width, top),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(6f, 8f), 0f,
                ),
            )
            drawLine(
                color = PulsePalette.GridLine,
                start = Offset(0f, (top + bottom) / 2f),
                end = Offset(size.width, (top + bottom) / 2f),
                strokeWidth = 1f,
            )
            drawLine(
                color = PulsePalette.GridLine,
                start = Offset(0f, bottom),
                end = Offset(size.width, bottom),
                strokeWidth = 1.5f,
            )

            if (values.size < 2) return@Canvas

            val scale = if (ceiling <= 0.0) 1.0 else ceiling
            // The time axis stretches to whatever history there is, so
            // the newest reading is always at the right edge and the
            // line fills the plot from the first sample. A fixed width
            // with a fixed 240-sample window leaves the trace occupying
            // a sliver on the left for the first twenty seconds, which
            // reads as a broken chart rather than as a young one.
            val shown = values.takeLast(WINDOW)
            val stepX = size.width / (shown.size - 1).coerceAtLeast(1).toFloat()

            // The displayed line is smoothed, and labelled as such where
            // it is shown. The instantaneous rate is bimodal — a full
            // socket buffer hands over a buffer's worth instantly, then
            // the next read waits for the network — so plotting it raw
            // gives a picket fence that hides the shape of the transfer.
            // The data is untouched; only the drawn line is.
            val smoothed = shown.windowed(SMOOTHING, 1, partialWindows = true)
                .map { window -> window.average() }
            if (smoothed.size < 2) return@Canvas

            fun pointAt(index: Int): Offset {
                val v = smoothed[index]
                val clamped = if (overCeiling) v.coerceAtMost(scale) else v
                val fraction = if (logarithmic) {
                    kotlin.math.log10(1.0 + clamped.coerceAtLeast(0.0)) /
                        kotlin.math.log10(1.0 + scale)
                } else {
                    clamped.coerceAtLeast(0.0) / scale
                }.coerceIn(0.0, 1.0).toFloat()
                return Offset(
                    x = index * stepX,
                    // Inverted: the top of the plot is the ceiling.
                    y = bottom - fraction * (bottom - top),
                )
            }

            // The area under the line, very faint. It gives the plot a
            // floor without competing with the trace itself.
            val area = Path().apply {
                moveTo(0f, bottom)
                smoothed.indices.forEach { lineTo(pointAt(it).x, pointAt(it).y) }
                lineTo((smoothed.size - 1) * stepX, bottom)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    listOf(lineColour.copy(alpha = 0.10f), Color.Transparent),
                    startY = top,
                    endY = bottom,
                ),
            )

            val line = Path().apply {
                smoothed.indices.forEach { i ->
                    val p = pointAt(i)
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
            }
            drawPath(
                path = line,
                color = lineColour,
                style = Stroke(width = 3f, cap = StrokeCap.Round),
            )

            // A point above the ceiling is drawn hollow. Silently
            // clamping it makes a fivefold spike look like a plateau,
            // and a plateau is a different finding from a spike.
            if (overCeiling) {
                smoothed.forEachIndexed { i, value ->
                    if (value > scale * 1.0001) {
                        val p = pointAt(i)
                        drawCircle(
                            color = PulsePalette.Warning,
                            radius = 4f,
                            center = p,
                            style = androidx.compose.ui.graphics.drawscope.Fill,
                        )
                    }
                }
            }

            // The head. Drawn last so nothing overlaps it.
            val head = pointAt(smoothed.size - 1)
            drawCircle(color = lineColour, radius = 6f, center = head)
            drawCircle(
                color = PulsePalette.Background,
                radius = 2.5f,
                center = head,
            )
        }

        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = PulsePalette.OnSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * How many readings the trace holds.
 *
 * Bounded, and deliberately. M-Lab's ndt7 bug log records a stability
 * sampler that froze after sixty seconds because its entry buffer was
 * unbounded — and a trace that grows without limit on a phone is a slow
 * memory leak wearing a chart's clothes. 240 samples at the 100 ms
 * cadence is 24 seconds of history, which is longer than the test.
 */
private const val WINDOW = 240

/**
 * Readings averaged into each drawn point.
 *
 * Nine at a 100 ms cadence is most of a second — long enough that the
 * socket-buffer bursts which make the raw rate bimodal read as a line
 * rather than as a picket fence, and short enough that a genuine stall
 * still shows as one. The data underneath is untouched; only the drawn
 * line is smoothed.
 */
private const val SMOOTHING = 9
