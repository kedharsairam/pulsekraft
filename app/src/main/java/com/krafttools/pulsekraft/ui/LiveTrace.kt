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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
     * A value worth drawing a line for, and what to call it.
     *
     * This is the reason the trace is not decoration. A bare number —
     * 54 ms — means nothing to anyone. A line labelled "100 ms" turns
     * the same number into a judgement, and turns the plot into
     * something read at a glance rather than something that has to be
     * interpreted. The band above it is tinted very faintly so the eye
     * learns where "too slow" lives before any number is read.
     */
    threshold: Double? = null,
    thresholdLabel: String? = null,
    /**
     * The bottom of the axis, which is not always zero.
     *
     * A log axis is right for spanning decades and wasteful the moment
     * the data occupies one decade: a transfer running between 25 and
     * 400 Mbps spends the entire bottom half of the plot on the region
     * below 1 Mbps, where nothing is happening. Letting the axis start
     * at the data's own floor uses the height, and costs nothing in
     * truthfulness because the figure in the line above the plot states
     * the absolute value — the plot is for shape, the number is for
     * magnitude.
     */
    floor: Double = 0.0,
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
            val scale = if (ceiling <= 0.0) 1.0 else ceiling
            val top = 16f
            val bottom = size.height - 16f
            val usable = bottom - top

            // One place decides where a value sits on this axis. Two
            // places is how a trace ends up with its threshold band and
            // its line disagreeing about what 100 ms looks like.
            val base = floor.coerceIn(0.0, scale)
            fun yFor(value: Double): Float {
                val clamped = value.coerceIn(base, scale)
                val fraction = if (logarithmic) {
                    // Offset by the floor first, so the log runs across
                    // the span that is actually occupied rather than
                    // from one.
                    (kotlin.math.log10(1.0 + (clamped - base)) /
                        kotlin.math.log10(1.0 + (scale - base)))
                        .coerceIn(0.0, 1.0)
                } else {
                    if (scale <= base) 1.0
                    else ((clamped - base) / (scale - base)).coerceIn(0.0, 1.0)
                }.toFloat()
                return bottom - fraction * usable
            }

            // Above the threshold is tinted, faintly. Enough for the eye
            // to learn where "too slow" is; not enough to compete with
            // the data. The old plot had three unlabelled gridlines
            // instead, which is chrome that looks like information.
            if (threshold != null && threshold < scale) {
                val y = yFor(threshold)
                drawRect(
                    color = PulsePalette.Warning.copy(alpha = 0.05f),
                    topLeft = Offset(0f, top),
                    size = androidx.compose.ui.geometry.Size(size.width, y - top),
                )
                drawLine(
                    color = PulsePalette.Warning.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5f,
                )
            }

            if (values.size < 2) return@Canvas

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
            val smoothed = smoothedForDisplay(shown)
            if (smoothed.size < 2) return@Canvas

            fun pointAt(index: Int): Offset {
                val v = if (overCeiling) smoothed[index].coerceAtMost(scale)
                else smoothed[index]
                return Offset(x = index * stepX, y = yFor(v))
            }

            // No fill under the line, and this is a deliberate removal
            // rather than an omission. A filled area encodes a
            // cumulative quantity — the taller the region, the more has
            // accumulated — and a transfer rate is not a quantity. The
            // shading also made the plot worse as a chart, because the
            // largest area drawn was the region *below* the data: the
            // emptier the transfer, the more confident it looked.
            //
            // A ground line replaces it. One hairline at the floor is
            // enough to stop the plot floating, and it is a thing the
            // reader can actually use.

            drawLine(
                color = PulsePalette.GridLine,
                start = Offset(0f, bottom),
                end = Offset(size.width, bottom),
                strokeWidth = 1.5f,
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
                style = Stroke(width = 2.5f, cap = StrokeCap.Round,
                    // Joined, not mitred. A 40-degree spike with a
                    // mitre join grows a spike of its own, and on a plot
                    // whose subject is spikes that is a lie about the
                    // data at exactly the moment it matters.
                    join = StrokeJoin.Round),
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
            drawCircle(color = lineColour, radius = 5f, center = head)
            // A hollow head in the plot's own ground colour, so the
            // newest reading reads as the point of the line rather than
            // as a bead threaded onto it.
            drawCircle(color = PulsePalette.Background, radius = 2f, center = head)
        }

        if (thresholdLabel != null && threshold != null && threshold < ceiling) {
            Text(
                text = thresholdLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = PulsePalette.Warning,
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
 * The signal the trace actually draws.
 *
 * Deliberately the same function the axis is computed from, and that
 * shared definition is the fix for the last wrong-looking plot. The
 * axis was fitted to the RAW interval series while the line drawn over
 * it was smoothed, so the two described different signals and an
 * upload's socket-buffer bursts — which the smoothing exists to erase —
 * set the scale anyway. The burst spikes are not rare outliers either:
 * they are roughly a third of an upload's samples, so no percentile and
 * no outlier fence separates them from the signal, because bimodal is
 * not the same thing as outlier-ridden.
 *
 * Fitting the axis to what is drawn is the answer, and the labels under
 * the plot now describe the visible line rather than the raw samples.
 */
internal fun smoothedForDisplay(values: List<Double>): List<Double> {
    val windows = values.windowed(SMOOTHING, 1, partialWindows = true)
        .map { it.average() }
    // Partial windows at BOTH ends are discarded once there are enough
    // samples to spare. They average fewer readings than the window
    // promises, so the last one is usually the transfer stopping rather
    // than the connection slowing — which drew a cliff off the bottom of
    // every single plot, on every phase, every run. A reader would have
    // had no way to know it was an artefact of the display.
    //
    // Short series keep their partial windows: dropping them would leave
    // a plot with nothing on it during the first second, and a blank
    // plot is a worse lie than a ragged one.
    val spare = (windows.size - 2).coerceAtLeast(0)
    if (values.size <= SMOOTHING || spare == 0) return windows
    return windows.dropLast(minOf(SMOOTHING - 1, spare))
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
