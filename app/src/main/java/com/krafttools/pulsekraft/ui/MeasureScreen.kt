package com.krafttools.pulsekraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krafttools.pulsekraft.BuildConfig
import com.krafttools.pulsekraft.core.Bufferbloat
import com.krafttools.pulsekraft.core.Latency
import com.krafttools.pulsekraft.core.Method
import com.krafttools.pulsekraft.core.Profiles
import com.krafttools.pulsekraft.core.TestReport
import com.krafttools.pulsekraft.core.Throughput
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.net.LiveState
import com.krafttools.pulsekraft.net.Phase
import com.krafttools.pulsekraft.net.Probe
import com.krafttools.pulsekraft.net.Reading
import com.krafttools.pulsekraft.ui.theme.PulsePalette
import kotlin.math.max

/**
 * The whole app, on one screen, with no scrolling.
 *
 * The no-scroll rule is the load-bearing one. It is not a preference:
 * a screen that scrolls is a screen whose hierarchy is unresolved, and
 * resolving it by pushing the least important thing below the fold is
 * how a design turns into a filing cabinet. Everything here has to earn
 * a place in one viewport, which is a far better discipline than any
 * amount of careful spacing.
 *
 * The second rule is that **typography is the interface**. There is no
 * dial and no gauge. A gauge makes the number a passenger inside a
 * drawing; type makes it the page. Hierarchy comes from size, weight and
 * space — and colour is reserved for meaning, which is why the only two
 * colours in the running state are the accent and the warning.
 */
private sealed interface MeasureState {
    data object Idle : MeasureState
    data class Running(val live: LiveState?, val phase: Phase) : MeasureState
    data class Done(val report: TestReport) : MeasureState
    data class Failed(val what: String, val why: String) : MeasureState
}

/**
 * Tabular figures.
 *
 * The single most important typographic detail in a live measurement,
 * and the one most easily missed. With proportional figures, a digit
 * changing from 1 to 8 reflows every glyph after it, so a value ticking
 * at ten times a second is an illegible shimmer. `tnum` fixes every
 * digit to the same width, and the number stops moving except where the
 * number actually moved.
 */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf<MeasureState>(MeasureState.Idle) }
    var methodOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    // One tick per state change, not one per sample. A screen that
    // re-renders a hundred times a second is a screen that cannot be
    // read, and the hardware gets no say in how fast a person reads.
    var lastFrame = 0L

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        when (val current = state) {
            MeasureState.Idle -> IdleBlock(
                onRun = {
                    lastFrame = 0L
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    runTest(
                        onPhase = { phase ->
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            state = MeasureState.Running(null, phase)
                        },
                        onLive = { live ->
                            val now = System.currentTimeMillis()
                            if (now - lastFrame >= FRAME_MS) {
                                lastFrame = now
                                state = MeasureState.Running(live, live.phase)
                            }
                        },
                        onFinish = { result ->
                            val report = result.report
                            state = if (report != null) {
                                MeasureState.Done(report)
                            } else {
                                MeasureState.Failed(
                                    result.failure?.what ?: "The test could not run",
                                    result.failure?.why ?: "an unknown reason",
                                )
                            }
                        },
                    )
                },
            )

            is MeasureState.Running -> RunningBlock(current)

            is MeasureState.Failed -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                FailedBlock(current)
            }

            is MeasureState.Done -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                DoneBlock(
                    report = current.report,
                    methodOpen = methodOpen,
                    onToggleMethod = { methodOpen = !methodOpen },
                )
            }
        }

        Spacer(Modifier.weight(1f))
        Footer()
    }
}

/** Ten frames a second. A number that updates faster is not read faster. */
private const val FRAME_MS = 100L

@Composable
private fun Footer() {
    Column {
        HorizontalDivider(color = PulsePalette.GridLine)
        Text(
            text = "v${BuildConfig.VERSION_NAME} · to ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.labelSmall,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

private fun runTest(
    onPhase: (Phase) -> Unit,
    onLive: (LiveState) -> Unit,
    onFinish: (Probe.Result) -> Unit,
) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    Thread({
        val result = Probe(volume = Volume.LIGHT).run(onPhase = onPhase, onLive = onLive)
        // Every write to Compose state has to happen on the main thread,
        // and the probe emits from two of its own. The hop is not
        // optional and dropping it is a crash rather than a glitch.
        main.post { onFinish(result) }
    }, "pulsekraft-ui-driver").apply { isDaemon = true }.start()
}

@Composable
private fun IdleBlock(onRun: () -> Unit) {
    val light = Profiles.of(Volume.LIGHT)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = "PulseKraft",
            style = MaterialTheme.typography.titleMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        // The one control, large and centred, because there is exactly
        // one thing to do. Everything else the app can tell you comes
        // after this is pressed.
        Box(
            modifier = Modifier
                .size(184.dp)
                .clip(CircleShape)
                .background(PulsePalette.Surface)
                .clickable(onClick = onRun)
                .semantics { contentDescription = "Run test" },
            contentAlignment = Alignment.Center,
        ) {
            PulseMark()
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = "Measures this connection to Cloudflare's nearest edge.",
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            // Dividing two Ints gives a Long, and a Long handed to %.0f
            // throws at format time — a crash on the first frame, which
            // no unit test reaches.
            text = "Up to %.0f MB, about %.0f seconds each way.".format(
                light.targetBytes / (1024.0 * 1024.0),
                (light.graceMillis + light.measureMillis) / 1000.0,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = PulsePalette.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The mark: a pulse trace, in the two colours of the palette. */
@Composable
private fun PulseMark() {
    Canvas(modifier = Modifier.size(96.dp)) {
        val w = size.width
        val h = size.height
        val mid = h / 2f
        val stroke = w * 0.075f
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.10f, mid)
            lineTo(w * 0.34f, mid)
            lineTo(w * 0.46f, h * 0.16f)
            lineTo(w * 0.60f, h * 0.84f)
            lineTo(w * 0.72f, mid)
            lineTo(w * 0.82f, h * 0.32f)
            lineTo(w * 0.90f, mid)
        }
        drawPath(
            path = path,
            color = PulsePalette.Pulse,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
        )
    }
}

@Composable
private fun RunningBlock(state: MeasureState.Running) {
    val live = state.live
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = state.phase.label,
            style = MaterialTheme.typography.titleMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        if (live == null) {
            Text(
                text = "Starting…",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                color = PulsePalette.OnSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            return
        }

        // The number is the interface. Large, tabular, and the only
        // thing on this screen that is not either whitespace or the
        // trace beneath it.
        val shown by animateFloatAsState(
            targetValue = live.current.toFloat(),
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 220f),
            label = "reading",
        )
        Text(
            text = formatReading(live.reading, shown.toDouble()),
            style = MaterialTheme.typography.displayLarge.merge(Tabular),
            color = when (live.reading) {
                Reading.LATENCY -> PulsePalette.Pulse
                else -> PulsePalette.OnSurface
            },
            maxLines = 1,
        )
        Text(
            text = live.reading.unit,
            style = MaterialTheme.typography.titleMedium,
            color = PulsePalette.OnSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (live.peak > 0.0) {
                Text(
                    text = "peak " + formatReading(live.reading, live.peak),
                    style = MaterialTheme.typography.labelMedium.merge(Tabular),
                    color = PulsePalette.Warning,
                )
            }
            if (live.bytesSoFar > 0) {
                Text(
                    text = "%.1f MB".format(live.bytesSoFar / 1_048_576.0),
                    style = MaterialTheme.typography.labelMedium.merge(Tabular),
                    color = PulsePalette.OnSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // The trace takes everything that is left. On this screen that
        // is most of the viewport, which is the point: the shape of a
        // transfer cannot be read in a letterbox.
        //
        // The live trace AUTOSCALES to the peak seen so far, and the
        // result trace does not. That is not an inconsistency, it is the
        // distinction between the two jobs:
        //
        //  - While the test runs, nobody is comparing this trace to
        //    last week's. They are watching the shape of what is
        //    happening, and a fixed 0-2000 axis put a 20 Mbps transfer
        //    in the bottom tenth with half the screen empty.
        //  - After it finishes, the verdict has to mean the same thing
        //    every time, so that trace is scaled from the median and
        //    p95 and never from its own maximum.
        //
        // A floor of a quarter of the nominal scale stops the first
        // second from looking like a mountain range because two samples
        // happened to be fast.
        LiveTrace(
            values = live.series,
            ceiling = max(max(live.peak * 1.15, live.scaleMax * 0.25), 1.0),
            lineColour = when (live.reading) {
                Reading.LATENCY -> PulsePalette.Pulse
                else -> PulsePalette.Primary
            },
            logarithmic = live.reading != Reading.LATENCY,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FailedBlock(state: MeasureState.Failed) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = state.what,
            style = MaterialTheme.typography.titleLarge,
            color = PulsePalette.Warning,
        )
        Spacer(Modifier.height(8.dp))
        // A failed test says what failed and why, rather than showing a
        // zero. A zero would be a claim about the line.
        Text(
            text = state.why,
            style = MaterialTheme.typography.bodyLarge,
            color = PulsePalette.OnSurface,
        )
    }
}

/**
 * A ColumnScope extension, not a plain composable.
 *
 * `Modifier.weight` belongs to ColumnScope, so a composable that lays
 * its trace out to fill the remaining height has to be one. Written as
 * an ordinary function it looks fine and does not compile, which is a
 * confusing way to learn that the layout is doing something the
 * signature is not admitting to.
 */
@Composable
private fun ColumnScope.DoneBlock(
    report: TestReport,
    methodOpen: Boolean,
    onToggleMethod: () -> Unit,
) {
    report.rejectedBecause?.let {
        // A refused or truncated transfer is stated before any figure, so
        // a partial result is never read as a complete one.
        Text(
            text = "Partial result: ${it.explanation}.",
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.Warning,
        )
        Spacer(Modifier.height(8.dp))
    }

    // The verdict first, in plain words, because that is what a person
    // opened a speed test to find out. The figures underneath are the
    // evidence for it, not the headline.
    Text(
        text = verdict(report),
        style = MaterialTheme.typography.headlineSmall,
        color = PulsePalette.OnSurface,
    )
    Spacer(Modifier.height(16.dp))

    val loaded = report.loadedDuringDownload?.medianMs
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = if (loaded == null) "—" else "%.0f".format(loaded),
            style = MaterialTheme.typography.displayMedium.merge(Tabular),
            fontWeight = FontWeight.SemiBold,
            color = PulsePalette.OnSurface,
        )
        Text(
            text = " ms loaded",
            style = MaterialTheme.typography.titleMedium,
            color = PulsePalette.OnSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }
    Text(
        text = report.bufferbloatIndex?.let { "%.2fx idle".format(it) }
            ?: "latency under load",
        style = MaterialTheme.typography.bodyMedium,
        color = PulsePalette.OnSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    LiveTrace(
        values = report.stabilitySeries,
        // Scaled from the bulk of the samples, not the maximum. Scaling
        // to the maximum lets one spike set the top of the plot and
        // flattens everything else, so a verdict of "spiky" prints above
        // a line that looks level.
        ceiling = stabilityCeiling(report),
        lineColour = PulsePalette.Pulse,
        overCeiling = true,
        modifier = Modifier.weight(1f),
    )

    HorizontalDivider(color = PulsePalette.GridLine)
    Spacer(Modifier.height(10.dp))

    FigureRow("Download", report.download)
    Spacer(Modifier.height(6.dp))
    FigureRow("Upload", report.upload)
    report.stability?.let {
        Spacer(Modifier.height(6.dp))
        Text(
            text = "steady ±%.0f ms".format(
                (it.summary.p95Ms - it.summary.minMs) / 2.0,
            ),
            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
            color = PulsePalette.OnSurfaceVariant,
        )
    }

    Spacer(Modifier.height(10.dp))
    Text(
        // The method is a paragraph, and paragraphs belong collapsed.
        // Four lines of it on the main screen is the wall of text this
        // workspace does not tolerate.
        text = if (methodOpen) "Hide method" else "How this was measured",
        style = MaterialTheme.typography.bodyMedium,
        color = PulsePalette.Primary,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onToggleMethod)
            .padding(vertical = 12.dp),
    )
    if (methodOpen) {
        Text(
            text = Method.AGGREGATION,
            style = MaterialTheme.typography.bodySmall,
            color = PulsePalette.OnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Method.SCOPE,
            style = MaterialTheme.typography.bodySmall,
            color = PulsePalette.OnSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = Method.LIMITS,
            style = MaterialTheme.typography.bodySmall,
            color = PulsePalette.OnSurface,
        )
    }
}

@Composable
private fun FigureRow(label: String, value: Throughput?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = PulsePalette.OnSurfaceVariant,
        )
        Text(
            // The window matters as much as the rate. "107 Mbps over two
            // seconds" and "107 Mbps over ten" are different claims, and
            // quoting the first alone is how a burst becomes a speed.
            text = if (value == null) "—" else "%.0f Mbps  %.1fs".format(
                value.averageMbps, value.measuredMillis / 1000f,
            ),
            style = MaterialTheme.typography.bodyLarge.merge(Tabular),
            color = PulsePalette.OnSurface,
        )
    }
}

/**
 * The verdict, in a sentence a person can act on.
 *
 * This is the part that makes the app worth more than the four numbers
 * it is built from. Nobody opens a speed test to be told 106 Mbps —
 * they open it to find out whether their calls will work. The bands are
 * the ones the numbers actually justify, and the wording is deliberately
 * plain rather than promotional: a speed test that writes "Blazing
 * fast!" has told the reader nothing they can use.
 */
private fun verdict(report: TestReport): String {
    val index = report.bufferbloatIndex
    if (index == null) return "Test incomplete"
    return when {
        index > 4.0 -> "Fine for browsing, poor for calls"
        index > 2.0 -> "Usable, but calls may stutter"
        else -> "Good for calls"
    }
}

private fun stabilityCeiling(report: TestReport): Double {
    val sorted = report.stabilitySeries.sorted()
    if (sorted.isEmpty()) return 1.0
    val median = sorted[sorted.size / 2]
    val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
    return max(max(p95 * 1.6, median * 2.5), 1.0)
}

private fun formatReading(reading: Reading, value: Double): String = when (reading) {
    Reading.LATENCY -> "%.0f".format(value)
    else -> if (value >= 100) "%.0f".format(value) else "%.1f".format(value)
}
