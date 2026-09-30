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
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
 * The three ways a run can end.
 *
 * Done, stopped and failed are three different things and must never look
 * alike. A run the person halted is not a fault and keeps what it had
 * measured; a transport failure says so in words rather than showing a
 * zero, because a zero would be a claim about the line.
 */
/**
 * A run that was stopped by the person, not by a fault.
 *
 * The phases that finished are still shown, because a download figure
 * from a cancelled run is a real measurement of a real transfer and
 * discarding it would be its own kind of dishonesty. The line above it
 * says what is missing, because a partial report that does not say it
 * is partial is worse than no report.
 */
@Composable
internal fun StoppedBlock(done: List<PhaseFigure>, onAgain: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "STOPPED",
            style = MaterialTheme.typography.labelMedium.merge(Tabular),
            color = PulsePalette.OnSurfaceVariant,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "You stopped this one.",
            style = MaterialTheme.typography.headlineSmall,
            color = PulsePalette.OnSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (done.isEmpty()) {
                "Nothing had finished yet."
            } else {
                "What finished is below. The rest was not measured, and this app " +
                    "will not guess at it."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = PulsePalette.OnSurfaceVariant,
        )
        if (done.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            PhaseLog(done)
        }
        Spacer(Modifier.height(34.dp))
        PrimaryAction("Test again", onAgain)
    }
}

@Composable
internal fun FailedBlock(state: MeasureState.Failed) {
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
internal fun DoneBlock(
    report: TestReport,
    methodOpen: Boolean,
    onToggleMethod: () -> Unit,
    onAgain: () -> Unit,
) = Column(modifier = Modifier.fillMaxSize()) {
    // A refused or truncated transfer is stated before any figure, so a
    // partial result is never read as a complete one.
    report.rejectedBecause?.let {
        Text(
            text = "PARTIAL — ${it.explanation.uppercase()}",
            style = MaterialTheme.typography.labelMedium.merge(Tabular),
            color = PulsePalette.Warning,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(20.dp))
    }

    // Three tiers, in this order, and the order is the design.
    //
    //  1. A letter-spaced label, so the eye has somewhere to enter.
    //  2. The verdict in words, which is the answer.
    //  3. The figure that supports it, large.
    //
    // The verdict used to be a headline and the figure beneath it a
    // display size, which made two headlines argue with each other.
    // One is the headline. The other is evidence.
    Text(
        text = "RESULT",
        style = MaterialTheme.typography.labelMedium.merge(Tabular),
        color = PulsePalette.OnSurfaceVariant,
        letterSpacing = 1.4.sp,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = verdict(report),
        style = MaterialTheme.typography.headlineSmall,
        color = PulsePalette.OnSurface,
        // The answer, read before anything else on the screen. The
        // figures underneath are its evidence, and a screen reader
        // should not have to walk past them to get to the conclusion.
        modifier = Modifier.semantics {
            heading()
            contentDescription = "${verdict(report)}. ${report.loadedDuringDownload?.medianMs?.let { "${"%.0f".format(it)} milliseconds of latency while the connection was busy" } ?: ""}"
        },
    )
    Spacer(Modifier.height(34.dp))

    val loaded = report.loadedDuringDownload?.medianMs
    val cap = LocalDensity.current.fontScale.coerceAtMost(HERO_FONT_SCALE_CAP)
    val size = 60.sp * cap
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = if (loaded == null) "—" else "%.0f".format(loaded),
            style = MaterialTheme.typography.displayMedium.merge(Tabular).copy(
                fontSize = size,
                lineHeight = size * 0.94f,
                letterSpacing = (-1.5).sp,
            ),
            color = PulsePalette.OnSurface,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "ms under load",
            style = MaterialTheme.typography.titleMedium.merge(Tabular),
            color = PulsePalette.OnSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.alignByBaseline(),
        )
    }
    Spacer(Modifier.height(8.dp))
    // Both comparisons on one line, in the quiet grey. The ratio and the
    // unloaded figure belong together: either alone is uninterpretable,
    // because "70 ms" means nothing and "1.4x" means less.
    Text(
        text = listOfNotNull(
            // A ratio below 1.0 is not a good bufferbloat score, it is an
            // inverted measurement: load made the line faster, which
            // happens when the unloaded baseline was the worse of the
            // two. Printing it as "0.33x" invites the reader to treat a
            // broken baseline as an excellent result.
            report.bufferbloatIndex?.let { ratio ->
                when {
                    // 0.98 is a dead heat, not an inverted measurement,
                    // and calling it "baseline unreliable" would be
                    // crying wolf over noise. Load simply did not add
                    // anything, which is a result.
                    ratio >= 0.85 -> if (ratio >= 1.0) {
                        "%.2fx".format(ratio)
                    } else {
                        "no added latency"
                    }
                    // Below that, load made the line measurably faster
                    // and the baseline is worse than the load it was
                    // supposed to be measured against.
                    else -> "baseline unreliable"
                }
            },
            report.idleLatency?.let { "unloaded %.0f ms".format(it.medianMs) },
        ).joinToString("   ·   "),
        style = MaterialTheme.typography.labelLarge.merge(Tabular),
        color = PulsePalette.OnSurfaceVariant,
        maxLines = 1,
    )

    // The self-check the whole method rests on, and which the README
    // claimed was on screen and was not.
    //
    // `downloadMayBeBufferLimited()` has existed, been documented and had
    // five tests for the whole life of this app, and nothing ever called
    // it. A low figure can be the line being slow, or it can be this
    // app's own receive buffer being the ceiling — and the second case
    // is the one where the number on screen is a fact about the app
    // rather than about the connection, which is precisely the thing this
    // app says it will not do.
    //
    // So it is stated, in words, next to the figures it qualifies.
    if (report.downloadMayBeBufferLimited() == true ||
        report.uploadMayBeBufferLimited() == true
    ) {
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(PulsePalette.Warning.copy(alpha = 0.10f))
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = "!",
                style = MaterialTheme.typography.titleMedium,
                color = PulsePalette.Warning,
            )
            Text(
                text = "This figure may be limited by the app, not your line. It ran " +
                    "at about the ceiling this app could reach, given the receive " +
                    "buffer the system granted it.",
                style = MaterialTheme.typography.bodyMedium,
                color = PulsePalette.OnSurface,
            )
        }
    }

    Spacer(Modifier.height(26.dp))

    // The stability trace, and the reference it is measured against is
    // this connection's OWN unloaded latency, not 150ms. The question
    // the trace answers is "did latency rise above its resting level",
    // and drawing a universal line instead would answer a question
    // nobody asked.
    LiveTrace(
        values = report.stabilitySeries,
        // Scaled from the bulk of the samples, not the maximum. Scaling
        // to the maximum lets one spike set the top of the plot and
        // flattens everything else, so a verdict of "spiky" printed
        // above a line that looked level.
        ceiling = stabilityCeiling(report),
        lineColour = PulsePalette.Pulse,
        overCeiling = true,
        threshold = report.idleLatency?.medianMs,
        thresholdLabel = "unloaded",
        // A canvas announces nothing by default, so a blind reader would
        // step straight over the only picture in the app. Described from
        // the data rather than in prose written by hand, so it cannot
        // describe a watch that was not measured.
        spokenSummary = report.stability?.let { stability ->
            "Latency stayed between ${"%.0f".format(stability.summary.minMs)} and " +
                "${"%.0f".format(stability.summary.p95Ms)} milliseconds over the " +
                "watch. ${stability.verdict.label}."
        },
        modifier = Modifier.weight(1f),
    )

    Spacer(Modifier.height(22.dp))

    // No divider here. The one below the trace is enough, and a second
    // hairline forty pixels under the first reads as a mistake rather
    // than as structure — it divides a gap, not two things.

    // The supporting figures, as one group four lines tall, set 3dp
    // apart with nothing between them. They are a table, and a table
    // with air between its rows is a list wearing a table's clothes.
    FigureRow("Download", report.download?.let {
        "%.0f Mbps   %.1fs".format(it.averageMbps, it.measuredMillis / 1000f)
    })
    Spacer(Modifier.height(3.dp))
    FigureRow("Upload", report.upload?.let {
        "%.0f Mbps   %.1fs".format(it.averageMbps, it.measuredMillis / 1000f)
    })
    Spacer(Modifier.height(3.dp))
    FigureRow("Unloaded latency", report.idleLatency?.let {
        "%.0f ms   jitter ±%.0f".format(it.medianMs, it.jitterMs)
    })
    Spacer(Modifier.height(3.dp))
    report.stability?.let {
        // A RANGE, not a ±. The stability row was printing
        // (p95 - min) / 2 under the same ± as the jitter above, and the
        // two numbers were computed from different things — RFC 3550
        // jitter is the mean absolute deviation between consecutive
        // samples, that half-range is not jitter under any name — and
        // they can differ by an order of magnitude on the same run.
        // A reader had no way to know which was which.
        //
        // The range is also the more honest shape for a watch whose
        // whole subject is excursions: "48 – 210 ms" says what actually
        // happened between the best and the ninety-fifth sample.
        FigureRow("Stability", "%s   %.0f–%.0f ms".format(
            it.verdict.label, it.summary.minMs, it.summary.p95Ms,
        ))
    }

    Spacer(Modifier.height(26.dp))

    // The primary action on this screen, as a primary action. It was a
    // line of purple text with the same weight and colour as the
    // disclosure below it and a hundred pixels of nothing between them,
    // so the two read as a pair of orphaned links rather than as
    // "the thing you press" and "the small print".
    PrimaryAction("Test again", onAgain)
    // The method is a paragraph, and paragraphs belong collapsed. Four
    // lines of it on the main screen is the wall of text this
    // workspace does not tolerate.
    ActionRow(
        label = if (methodOpen) "Hide method" else "How this was measured",
        tint = PulsePalette.OnSurfaceVariant,
        onClick = onToggleMethod,
    )
    Spacer(Modifier.height(8.dp))
    if (methodOpen) {
        // The one place in this app that scrolls, and deliberately so.
        // The no-scroll rule exists so a view cannot hide its own
        // priorities; a method statement is not a view, it is a
        // document, and documents are longer than phones. Bounding the
        // height keeps it a panel you dismiss rather than a page you
        // fall into, and the scroll exists so the last paragraph is
        // reachable at the largest text sizes instead of being clipped
        // off the bottom of a screen that has no room for it.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
        ) {
            listOf(Method.AGGREGATION, Method.SCOPE, Method.LIMITS).forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodySmall,
                    color = PulsePalette.OnSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/**
 * The screen's primary action, filled.
 *
 * 56dp tall rather than the 48dp minimum, because this is the one
 * control a person comes back for and the one they press without
 * reading. Rounded at 18dp — closer to a circle than the corners
 * elsewhere on the screen, because it is the only pressable thing here.
 *
 * The label is drawn in the background colour rather than white. The
 * accent is light, so dark-on-accent is both the higher contrast of the
 * two options (about 7:1) and the one that keeps the button from
 * glowing out of a screen that is otherwise almost entirely dark.
 */
@Composable
internal fun PrimaryAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PulsePalette.Primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = PulsePalette.Background,
        )
    }
}

/**
 * A secondary row the reader can act on, with a touch target that is
 * actually a touch target. A 48dp minimum is not a style preference,
 * it is the smallest reliably hittable size, and the previous 32dp-tall
 * text link was under it.
 */
@Composable
internal fun ActionRow(label: String, tint: Color, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = tint,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(vertical = 15.dp),
    )
}

/**
 * One line of the supporting table: the name on the left, the figures
 * on the right, both tabular so the columns line up down the screen.
 * Without `tnum` the right-hand values sit at a ragged left edge and
 * the table stops reading as a table.
 */
@Composable
internal fun FigureRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
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
            text = value ?: "—",
            style = MaterialTheme.typography.bodyLarge.merge(Tabular),
            color = PulsePalette.OnSurface,
        )
    }
}

/**
 * The verdict sentence, delegated.
 *
 * The decision itself lives in `core.Verdict` with a test suite beside
 * it, because it is the claim this app makes about someone's
 * connection and not a piece of presentation. A `private fun` in a
 * composable file cannot be pinned by a test, and a published sentence
 * that no test can reach is exactly the kind of thing that quietly
 * stops being true.
 */
internal fun verdict(report: TestReport): String = Verdict.for_(
    loadedMs = report.loadedDuringDownload?.medianMs,
    idleMs = report.idleLatency?.medianMs,
    index = report.bufferbloatIndex,
    spiky = report.stability?.verdict == Latency.Verdict.SPIKY,
)

/**
 * The top of the result trace, fitted to the bulk of the samples.
 *
 * This used to be max(p95 x 1.6, median x 2.5), which is the same
 * mistake the live trace had already been corrected for, left behind in
 * a second copy. A watch whose subject is excursions has a p95 well
 * above its median by definition, so scaling to it put the ordinary
 * samples in the bottom sixth of the plot with an empty band above —
 * and the band above the unloaded line is the part of this plot that
 * means something, so filling it with nothing wasted the one region a
 * reader looks at.
 *
 * Ninety percent sets the top and the excursion above it is pinned and
 * drawn hollow, which is the same arrangement as the live trace and for
 * the same reason. The unloaded reference line is guaranteed to be on
 * the plot whatever the samples do, because a trace whose reference line
 * has been scaled off the top cannot answer the question it exists to
 * answer.
 */
internal fun stabilityCeiling(report: TestReport): Double {
    val sorted = report.stabilitySeries.sorted()
    if (sorted.size < 4) return 1.0
    fun at(fraction: Double) = sorted[
        (sorted.size * fraction).toInt().coerceIn(0, sorted.size - 1)
    ]
    val p90 = at(0.90) * 1.25
    val unloaded = report.idleLatency?.medianMs ?: 0.0
    // Room for the reference line and its label even on a perfectly
    // steady watch, which would otherwise sit on the floor.
    return max(max(p90, unloaded * 1.8), 1.0)
}
