package com.krafttools.pulsekraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
 * What the screen is showing right now.
 *
 * Four states, because every other shape is a lie waiting to happen: a
 * test that has not run, a test running, a test that finished, and a
 * test that could not run. A screen showing a number in the last two
 * without saying which would be showing a measurement that never
 * happened.
 */
private sealed interface MeasureState {
    data object Idle : MeasureState
    data class Running(val live: LiveState?) : MeasureState
    data class Done(val report: TestReport) : MeasureState
    data class Failed(val what: String, val why: String) : MeasureState
}

@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf<MeasureState>(MeasureState.Idle) }
    var methodOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "PulseKraft",
            style = MaterialTheme.typography.headlineMedium,
            color = PulsePalette.Primary,
        )

        when (val current = state) {
            MeasureState.Idle -> IdleBlock { runTest { state = it } }
            is MeasureState.Running -> RunningBlock(current)
            is MeasureState.Failed -> FailedBlock(current)
            is MeasureState.Done -> DoneBlock(
                report = current.report,
                methodOpen = methodOpen,
                onToggleMethod = { methodOpen = !methodOpen },
            )
        }

        HorizontalDivider(color = PulsePalette.GridLine)
        Text(
            text = "v${BuildConfig.VERSION_NAME} · to ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

/**
 * Runs the probe off the main thread, publishing each phase and every
 * live reading to the main thread.
 *
 * A plain thread rather than a coroutine: the probe already owns a
 * thread per transfer direction and blocks for as long as the test
 * takes, so wrapping it in a coroutine would mean a dispatcher whose
 * only job was to wait for another thread. The hop to the main thread
 * is the part that matters — the probe emits from two threads and
 * Compose state may only be written from one.
 */
private fun runTest(set: (MeasureState) -> Unit) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    Thread({
        val result = Probe(volume = Volume.LIGHT).run(
            onPhase = { phase ->
                main.post { set(MeasureState.Running(null)) }
            },
            onLive = { live ->
                main.post { set(MeasureState.Running(live)) }
            },
        )
        main.post {
            set(
                if (result.report != null) MeasureState.Done(result.report)
                else MeasureState.Failed(
                    result.failure?.what ?: "The test could not run",
                    result.failure?.why ?: "an unknown reason",
                ),
            )
        }
    }, "pulsekraft-ui-driver").apply { isDaemon = true }.start()
}

@Composable
private fun IdleBlock(onRun: () -> Unit) {
    val light = Profiles.of(Volume.LIGHT)
    Text(
        text = "Measures this connection to Cloudflare's nearest edge.",
        style = MaterialTheme.typography.bodyLarge,
        color = PulsePalette.OnSurface,
    )
    Text(
        // Dividing two Ints gives a Long, and a Long handed to %.0f is
        // an IllegalFormatConversionException at format time — which is
        // a crash on the first frame, before any test can look at it.
        text = "Up to %.0f MB, about %.0f seconds each way.".format(
            light.targetBytes / (1024.0 * 1024.0),
            (light.graceMillis + light.measureMillis) / 1000.0,
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = PulsePalette.OnSurfaceVariant,
    )
    Button(onClick = onRun, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Run test")
    }
}

@Composable
private fun RunningBlock(state: MeasureState.Running) {
    val live = state.live
    if (live == null) {
        // Before the first reading there is nothing to point a needle at.
        // A placeholder arc with the real scale is better than a
        // spinner: it shows what is about to be measured.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(
                color = PulsePalette.Pulse,
                modifier = Modifier.height(28.dp),
            )
            Text(
                text = "Starting…",
                style = MaterialTheme.typography.titleMedium,
                color = PulsePalette.OnSurface,
            )
        }
        return
    }

    Text(
        text = live.phase.label + "…",
        style = MaterialTheme.typography.titleMedium,
        color = PulsePalette.OnSurface,
    )
    SpeedArc(live = live)
    ScaleLegend(live)
    LiveTrace(
        values = live.series,
        ceiling = live.scaleMax,
        lineColour = when (live.reading) {
            Reading.LATENCY -> PulsePalette.Pulse
            else -> PulsePalette.Primary
        },
        height = 84.dp,
    )
    if (live.bytesSoFar > 0) {
        Text(
            text = "%.1f MB moved".format(live.bytesSoFar / 1_048_576.0),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

@Composable
private fun FailedBlock(state: MeasureState.Failed) {
    Text(
        text = state.what,
        style = MaterialTheme.typography.titleMedium,
        color = PulsePalette.Warning,
    )
    // A failed test says what failed and why, rather than showing a
    // zero. A zero would be a claim about the line.
    Text(
        text = state.why,
        style = MaterialTheme.typography.bodyMedium,
        color = PulsePalette.OnSurface,
    )
    Button(onClick = { }, modifier = Modifier.heightIn(min = 48.dp), enabled = false) {
        Text("No result")
    }
}

@Composable
private fun DoneBlock(
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
    }

    // The hero is latency under load, not download. Not for effect: it is
    // the number that changes what a person does. A line can advertise
    // 300 Mbps and still ruin a video call, and this is the only app in
    // the set that will say so with a figure attached.
    HeroLatency(report)

    HorizontalDivider(color = PulsePalette.GridLine)
    ThroughputPanel("Download", report.download, report.downloadMayBeBufferLimited())
    ThroughputPanel("Upload", report.upload, report.uploadMayBeBufferLimited())

    report.stability?.let { stability ->
        HorizontalDivider(color = PulsePalette.GridLine)
        Text(
            text = "Stability · " + stability.verdict.label,
            style = MaterialTheme.typography.titleMedium,
            color = if (stability.verdict == Latency.Verdict.STEADY) PulsePalette.OnSurface
            else PulsePalette.Warning,
        )
        if (report.stabilitySeries.size > 1) {
            // The ceiling is NOT the series maximum.
            //
            // Scaling to the maximum lets a single spike set the top of
            // the plot, and everything else — which is the part a person
            // is trying to read — flattens onto the floor. A trace
            // verdict of "Spiky" printed above a line that looks level
            // is the chart disagreeing with the caption, and the chart
            // is the one that has to change.
            //
            // So the scale is set from the bulk of the data and the
            // spikes are pinned to the top edge and drawn hollow, which
            // says "off the scale" instead of hiding everything else
            // behind one outlier.
            val sorted = report.stabilitySeries.sorted()
            val median = sorted[sorted.size / 2]
            val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
            val ceiling = maxOf(p95 * 1.6, median * 2.5, 1.0)
            LiveTrace(
                values = report.stabilitySeries,
                ceiling = ceiling,
                lineColour = PulsePalette.Pulse,
                height = 72.dp,
                overCeiling = true,
            )
        }
        Text(
            text = stability.verdict.meaning,
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
    }

    HorizontalDivider(color = PulsePalette.GridLine)

    // The method is a paragraph, and paragraphs belong collapsed. Five
    // lines of it on the main screen is the wall of text this app exists
    // not to be.
    Button(onClick = onToggleMethod, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (methodOpen) "Hide method" else "How this was measured")
    }
    if (methodOpen) {
        Text(
            text = Method.AGGREGATION,
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurface,
        )
        Text(
            text = Method.SCOPE,
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurface,
        )
        Text(
            text = Method.LIMITS,
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurface,
        )
        Text(
            text = "Edge: ${report.edgeHost}",
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

@Composable
private fun HeroLatency(report: TestReport) {
    val loaded = report.loadedDuringDownload?.medianMs
    val idle = report.idleLatency?.medianMs
    val index = report.bufferbloatIndex
    val grade = report.bufferbloatGrade

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Latency under load",
            style = MaterialTheme.typography.titleSmall,
            color = PulsePalette.Pulse,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (loaded == null) "—" else "%.0f".format(loaded),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = PulsePalette.OnSurface,
            )
            Text(
                text = " ms",
                style = MaterialTheme.typography.titleMedium,
                color = PulsePalette.OnSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (index != null && grade != null) {
            Text(
                text = "%.2f× idle · %s".format(index, grade.label),
                style = MaterialTheme.typography.bodyLarge,
                color = if (grade == Bufferbloat.Grade.NONE) PulsePalette.OnSurface
                else PulsePalette.Warning,
            )
        } else if (idle != null) {
            Text(
                text = "idle was %.0f ms".format(idle),
                style = MaterialTheme.typography.bodyMedium,
                color = PulsePalette.OnSurfaceVariant,
            )
        }
        Text(
            text = grade?.advice ?: "How the line behaves while it is busy.",
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

@Composable
private fun ThroughputPanel(
    label: String,
    value: Throughput?,
    maybeCapped: Boolean?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = PulsePalette.Primary,
            )
            Text(
                text = if (value == null) "—" else "%.0f Mbps".format(value.averageMbps),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = PulsePalette.OnSurface,
            )
        }
        if (value != null) {
            // The window matters as much as the rate. "109 Mbps over two
            // seconds" and "109 Mbps over ten" are different claims, and
            // quoting the first without the second is how a two-second
            // burst gets read as a sustained speed.
            Text(
                text = "over %.1f s · spread %.1f×".format(
                    value.measuredMillis / 1000f, value.spreadRatio,
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = PulsePalette.OnSurfaceVariant,
            )
        }
        if (maybeCapped == true) {
            // The honest self-check, on screen. A low result this app's
            // own buffer ceiling could explain must say so, or the number
            // is reporting the app rather than the line.
            Text(
                text = "At this app's buffer ceiling — the line may be faster.",
                style = MaterialTheme.typography.labelSmall,
                color = PulsePalette.Warning,
            )
        }
    }
}
