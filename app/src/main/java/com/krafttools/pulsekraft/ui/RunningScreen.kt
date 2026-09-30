package com.krafttools.pulsekraft.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.krafttools.pulsekraft.core.Link
import com.krafttools.pulsekraft.core.LinkState
import com.krafttools.pulsekraft.core.Permission
import com.krafttools.pulsekraft.core.Policy
import com.krafttools.pulsekraft.core.Method
import com.krafttools.pulsekraft.core.Profiles
import com.krafttools.pulsekraft.core.TestReport
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.core.Verdict
import com.krafttools.pulsekraft.core.Throughput
import com.krafttools.pulsekraft.net.LiveState
import com.krafttools.pulsekraft.net.Phase
import com.krafttools.pulsekraft.net.Probe
import com.krafttools.pulsekraft.net.Reachability
import com.krafttools.pulsekraft.net.Reading
import com.krafttools.pulsekraft.ui.theme.PulsePalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/**
 * The running state: the reading, the plot, and the tally behind them.
 *
 * The rules that make this screen honest are all about the relationship
 * between the figure at the top and the line beneath it. The number IS
 * the last point of the series the plot draws; the axis is fitted to that
 * same series and printed underneath it; and nothing the app has decided
 * not to report — a buffer burst, a sub-one ratio — is allowed anywhere
 * near the headline.
 */

/**
 * How far the test has got, as five bars.
 *
 * A bare label cannot answer the only question a person watching this
 * has, which is "how much longer?" A label that says "Measuring upload"
 * could be the first second of five or the last. Five short bars say
 * it in one glance, and they cost twelve millimetres of height.
 *
 * They are the top of the screen on purpose. The previous version
 * opened with grey body text, which gave the eye nowhere to land —
 * a screen with no entry point reads as a screen that has not loaded.
 */
@Composable
internal fun PhaseTrack(phase: Phase) {
    val index = MEASUREMENT_PHASES.indexOf(phase)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MEASUREMENT_PHASES.forEachIndexed { i, _ ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            // Completed at 28%, current at full, future
                            // as bare chrome. Three visibly different
                            // states, because a track that cannot show
                            // which segment you are on is five lines of
                            // nothing.
                            i < index -> PulsePalette.Primary.copy(alpha = 0.28f)
                            i == index -> PulsePalette.Primary
                            else -> PulsePalette.GridLine
                        }
                    ),
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        text = phase.label.uppercase(),
        style = MaterialTheme.typography.labelMedium.merge(Tabular),
        color = PulsePalette.OnSurfaceVariant,
        // Letterspacing is what makes a string read as a label rather
        // than as content. The same characters, set loosely, sit one
        // tier above the number instead of competing with it.
        letterSpacing = 1.4.sp,
    )
}

/** The five phases a person waits through. DONE is not one of them. */
internal val MEASUREMENT_PHASES = listOf(
    Phase.CONNECTING, Phase.IDLE_LATENCY, Phase.DOWNLOAD,
    Phase.UPLOAD, Phase.STABILITY,
)

/**
 * The reading, as one tight cluster.
 *
 * Number, unit, peak and byte count are four parts of a single fact and
 * they are now set 6dp apart, inside a group that is 20dp from
 * anything else. Before, everything on the screen was separated by one
 * flat 12dp, so the number and its own unit were as far apart as the
 * number and the trace — a vertical list rather than a composition.
 *
 * The unit is on the same baseline as the number rather than beneath
 * it. A unit on its own line makes the reader do a small saccade and
 * re-attach it to the figure; on the baseline it was never a separate
 * object to begin with.
 */
@Composable
internal fun ReadingCluster(live: LiveState, volume: Volume) {
    // The figure on screen is the value at the trace's head, not the
    // last interval's raw rate.
    //
    // This is the most important correction in the file. A socket buffer
    // hands over several megabytes in one read and the next read waits,
    // so the instantaneous rate spends real stretches of a transfer near
    // zero. The screenshot that prompted this showed "0.0 Mbps" as the
    // largest text on the screen while the plot beside it was full of
    // data and half a megabyte had moved.
    //
    // Worse, it was the app publishing the exact artefact it refuses to
    // report anywhere else — the throughput peak was suppressed three
    // revisions ago because it is the kernel batching and not the
    // connection, and here the same batching was the headline. A number
    // a reader will act on has to mean the same thing as the number the
    // method statement describes.
    //
    // Taking the trace's own smoothed series makes the guarantee
    // checkable rather than promised: the figure on screen IS the
    // rightmost point of the line below it, by construction.
    val shown = live.displayed()
    // No spring. The value is already a nine-sample average arriving ten
    // times a second, and animating it again adds lag to a number whose
    // whole virtue was that it matches the line.
    // Dynamic Type is honoured up to a point and then stopped, which is
    // a deliberate decision rather than an oversight. A hero figure
    // that reflows off the edge at 2x is not an accessible instrument,
    // it is a broken one; past the cap the trace below and the result
    // screen still carry every figure, so nothing is actually lost.
    val cap = LocalDensity.current.fontScale.coerceAtMost(HERO_FONT_SCALE_CAP)
    val size = 74.sp * cap

    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = formatReading(live.reading, shown),
            style = MaterialTheme.typography.displayLarge.merge(Tabular).copy(
                fontSize = size,
                lineHeight = size * 0.92f,
                // Tight tracking on a large figure. The default
                // tracking is sized for body text and leaves a number
                // this wide visibly gappy between its digits.
                letterSpacing = (-2.5).sp,
            ),
            color = when (live.reading) {
                Reading.LATENCY -> PulsePalette.Pulse
                else -> PulsePalette.OnSurface
            },
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = live.reading.unit,
            style = MaterialTheme.typography.titleLarge.merge(Tabular),
            color = PulsePalette.OnSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }

    Spacer(Modifier.height(8.dp))
    // The sub-facts sit on one line, in the same quiet grey, separated
    // by a middot. The peak used to be amber, which put two accents on
    // the screen at once and made the peak look like a fault. It is not
    // a fault — it is the same fact as the number, and only one thing
    // on a screen is allowed to carry the accent.
    val parts = buildList {
        // The peak is shown for LATENCY and suppressed for throughput,
        // and the reason is a correctness one rather than a visual one.
        //
        // A worst-case latency sample is a property of the line: a
        // hundred and sixty milliseconds really did happen to a packet.
        // A peak throughput of 1756 on a 43 Mbps upload is not — it is
        // the socket buffer handing over four megabytes in a single
        // read, which says how the kernel batches and says nothing
        // about the connection. Printing it next to the live figure
        // invites the reader to believe their line touched 1756, and
        // the whole reason this app publishes its method is so that a
        // number on screen can be taken literally.
        if (live.reading == Reading.LATENCY && live.peak > 0.0) {
            add("worst " + formatReading(live.reading, live.peak))
        }
        if (live.bytesSoFar > 0) {
            add("%.1f of %.0f MB".format(
                live.bytesSoFar / 1_048_576.0,
                live.targetMegabytes(volume),
            ))
        }
    }
    if (parts.isNotEmpty()) {
        Text(
            text = parts.joinToString("   ·   "),
            style = MaterialTheme.typography.labelLarge.merge(Tabular),
            color = PulsePalette.OnSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * The value the instrument should show right now.
 *
 * The last point of the smoothed series, which is the same series the
 * trace draws. Falling back to the raw reading only when there is not
 * enough history to smooth, which is the first fraction of a second of
 * a phase.
 */
internal fun LiveState.displayed(): Double {
    if (series.size < 2) return current
    return smoothedForDisplay(series).lastOrNull() ?: current
}

/**
 * The axis range for a live trace, fitted to the data at both ends.
 *
 * Percentiles, not the maximum. The single fastest interval of a
 * transfer is a buffer handover, and letting it own the axis is what
 * left the previous version with a plot whose data occupied the top
 * third and a shaded slab filling the rest: the ceiling was 2019 Mbps
 * because one read was 1756, while the line sat between 25 and 400.
 *
 * The bounds widen over the first second so the plot opens at
 * something neutral and settles onto the transfer, which is the one
 * place a live scale moving is correct — there is no prior trace for
 * it to be inconsistent with.
 */
internal fun LiveState.fitSpan(): AxisSpan {
    if (series.size < 6) {
        return AxisSpan(0.0, max(max(peak * 1.2, scaleMax * 0.2), 1.0))
    }
    // Fitted to the SMOOTHED series, because that is what the trace
    // draws. Fitting to the raw samples was the bug: the axis was
    // scaled by values the line no longer contained, and an upload's
    // buffer bursts set a ceiling of 1427 Mbps over a signal living
    // between 25 and 200.
    //
    // The two percentile rules this replaced both failed the same way,
    // for the same reason. A socket buffer arrives whole and the next
    // read waits, so an upload's raw rates are bimodal — and a third of
    // the samples are in the upper mode. Tukey's fence assumes outliers
    // are rare; here they are common, so the fence widens with the very
    // spread it was meant to ignore. A fixed percentile is no better: the
    // 75th lands INSIDE the burst band. Bimodal is not outlier-ridden and
    // no fence around it will separate the modes.
    //
    // So the axis describes the signal as drawn, with a small margin.
    // The exact values are printed under the plot, so the fit is stated
    // rather than assumed.
    val shown = smoothedForDisplay(series)
    val low = shown.min()
    val high = shown.max()
    val margin = max(max((high - low) * 0.12, high * 0.06), 0.5)
    val paddedLow = max(low - margin, 0.0)
    return AxisSpan(paddedLow, high + margin)
}

/** The two ends of a plot's value axis. */
internal data class AxisSpan(val low: Double, val high: Double)

/**
 * How much data this test is going to move, for the "n of N MB" line.
 *
 * Reads the same constant the probe is given, so the denominator on
 * screen cannot drift from the number the transfer is actually
 * working to. Two copies of "the volume" is how a progress line starts
 * promising 25 MB of a 100 MB test.
 */
internal fun LiveState.targetMegabytes(volume: Volume): Double =
    Policy.megabytesFor(volume)

/**
 * Fold the finished phase into the log and clear the live series.
 *
 * Summarised from the interval series rather than carried out of the
 * probe, because by this point the probe's own summary is what the
 * result screen will use and the two must not disagree: the live
 * figure is the mean of the intervals, the reported one is the goodput
 * over the whole measured window, and they are close but not equal.
 */
internal fun MeasureState.Running.closeOffPhase(): List<PhaseFigure> {
    val series = live?.series ?: return done
    if (series.isEmpty()) return done
    val figure = when (live.reading) {
        Reading.LATENCY -> {
            val sorted = series.sorted()
            "%.0f ms".format(sorted[sorted.size / 2])
        }
        // The probe's own running goodput, not the mean of the series.
        // The two differ by enough to matter, and only one of them is
        // the figure the result screen will print.
        else -> live.averageMbpsToDate
            .takeIf { it > 0.0 }
            // "0 Mbps" reads as a broken app rather than as a very slow
            // one, because it is what a failed transfer would print
            // too. Below one megabit, keep a decimal — the run that
            // produced this had genuinely transferred, just badly, and
            // the number should say so.
            ?.let { if (it >= 1.0) "%.0f Mbps".format(it) else "%.1f Mbps".format(it) }
            ?: return done
    }
    if (done.any { it.phase == phase }) return done
    if (live == null) return done
    return done + PhaseFigure(phase, figure)

}

@Composable
internal fun RunningBlock(
    state: MeasureState.Running,
    onCancel: () -> Unit,
) {
    val live = state.live
    Column(modifier = Modifier.fillMaxSize()) {
        PhaseTrack(state.phase)

        if (live == null) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "Starting…",
                style = MaterialTheme.typography.displayMedium,
                color = PulsePalette.OnSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            return
        }

        // Generous. The track is a caption for the whole test; the
        // reading is the event. They are not the same thought and they
        // should not be adjacent.
        Spacer(Modifier.height(36.dp))
        ReadingCluster(live, state.volume)
        Spacer(Modifier.height(24.dp))

        // The trace takes everything that is left, which on this device
        // is most of the viewport. The shape of a transfer cannot be
        // read in a letterbox, and that was the point of taking the
        // dial away in the first place.
        //
        // The live trace AUTOSCALES to the peak seen so far; the result
        // trace does not. That is not an inconsistency, it is the
        // difference between the two jobs. While the test runs nobody is
        // comparing this trace to last week's — they are watching the
        // shape of what is happening, and a fixed 0-2000 axis put a
        // 20 Mbps transfer in the bottom tenth with half the screen
        // empty. After it finishes the verdict has to mean the same
        // thing every time, so that trace is scaled from its median and
        // p95 and never from its own maximum.
        val span = live.fitSpan()
        LiveTrace(
            values = live.series,
            ceiling = span.high,
            floor = span.low,
            lineColour = when (live.reading) {
                Reading.LATENCY -> PulsePalette.Pulse
                else -> PulsePalette.Primary
            },
            // Out-of-range points are pinned and drawn hollow, so
            // fitting the axis to the bulk does not quietly flatten
            // whatever escapes it.
            overCeiling = true,
            threshold = if (live.reading == Reading.LATENCY) VOICE_LIMIT_MS else null,
            thresholdLabel = if (live.reading == Reading.LATENCY) {
                "above %.0f ms".format(VOICE_LIMIT_MS)
            } else null,
            logarithmic = live.reading != Reading.LATENCY,
            // A FIXED height, not a cap on a weighted one.
            //
            // A plot given every remaining pixel was 457dp tall: at
            // that height the shape of a rate series stops reading as a
            // shape and starts reading as noise, because the vertical
            // resolution per megabit far exceeds anything the signal
            // actually varies over.
            //
            // It was then given `heightIn(max = 330.dp)` alongside a
            // weighted spacer, and the pair could still exceed the
            // column — the log's last row was clipped mid-glyph by the
            // footer on a run with three completed phases. A fixed
            // height plus a weighted spacer cannot overflow: the plot
            // is known, the log is bounded to three rows, and whatever
            // is left goes to the spacer where nothing can clip.
            // Flexible, with a cap — not a fixed height.
            //
            // A fixed 300dp plus the phase log plus the stop control
            // summed to more than a short screen holds, and the stop
            // control was the thing that disappeared: the last child of
            // an overflowing column is clipped, and the exit was on the
            // end. Weighting the plot lets it give up the space instead,
            // and the cap still stops it becoming the 457dp chart it was
            // when it had everything to itself.
            modifier = Modifier.weight(1f).heightIn(max = 300.dp),
        )

        Spacer(Modifier.height(10.dp))
        // The ends of the value axis. Without them the plot is a
        // drawing; with them it is a chart, and the reader can check
        // the line against a figure instead of taking its shape on
        // trust. The range is stated on the plot rather than hidden,
        // because a fitted axis is only honest if it says what it
        // fitted to.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "%.0f".format(span.low),
                style = MaterialTheme.typography.labelSmall.merge(Tabular),
                color = PulsePalette.OnSurfaceVariant,
            )
            Text(
                text = "%.0f %s".format(span.high, live.reading.unit),
                style = MaterialTheme.typography.labelSmall.merge(Tabular),
                color = PulsePalette.OnSurfaceVariant,
            )
        }

        // Whatever is left, which grows as phases complete. A running
        // test that shows only the current phase makes the first twenty
        // seconds look like nothing is happening, when an idle latency
        // and a download figure have already been measured and thrown
        // away. They stay on screen, which is also why nobody has to
        // remember them to compare against the final numbers.
        Spacer(Modifier.weight(1f))
        if (state.done.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            PhaseLog(state.done)
        }

        // The way out, at the bottom where a thumb already is.
        //
        // This control did not exist, which meant a run of up to fifty
        // seconds could not be stopped. That is not a missing feature so
        // much as a missing exit: every screen in this app is one tap
        // from anywhere else except this one, while it is running.
        Spacer(Modifier.height(18.dp))
        ActionRow("Stop", PulsePalette.OnSurfaceVariant, onCancel)
    }
}

/**
 * What the test has already found.
 *
 * Four rows, right-aligned tabular figures so the column lines up, the
 * newest at the bottom next to the phase being measured. Written as a
 * list rather than a table because it is a running tally that grows
 * downwards — the reader's eye is already at the bottom of the screen
 * watching the plot.
 */
@Composable
internal fun PhaseLog(done: List<PhaseFigure>) {
    Column {
        HorizontalDivider(color = PulsePalette.GridLine)
        // Three while running, every one when stopped. During a live run
        // the newest rows matter most and four plus the fixed plot will
        // not fit a short screen. Once the run is over there is nothing
        // new arriving, and dropping the earliest row drops the idle
        // latency — which is the figure the other three are read against.
        done.takeLast(if (done.size <= 4) done.size else 3).forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 34.dp)
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.phase.asNoun(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = PulsePalette.OnSurfaceVariant,
                )
                Text(
                    text = entry.figure,
                    style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    color = PulsePalette.OnSurface,
                )
            }
        }
    }
}

/**
 * Drawn on the latency plots only.
 *
 * Throughput has no comparable published standard, and inventing one
 * would be the same error wearing a different hat. The figure itself is
 * `Verdict.INTERACTIVE_LIMIT_MS` — ITU-T G.114 — so the line on the
 * plot and the sentence in the verdict cannot drift apart, which they
 * would if each held its own copy of the number.
 */
internal val VOICE_LIMIT_MS = Verdict.INTERACTIVE_LIMIT_MS

/** The cap on how far the hero figure grows with the reader's settings. */
internal const val HERO_FONT_SCALE_CAP = 1.35f
