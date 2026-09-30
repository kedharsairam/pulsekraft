package com.krafttools.pulsekraft.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krafttools.pulsekraft.core.Bufferbloat
import com.krafttools.pulsekraft.core.Latency
import com.krafttools.pulsekraft.core.Method
import com.krafttools.pulsekraft.core.TestReport
import com.krafttools.pulsekraft.core.Throughput
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.net.Phase
import com.krafttools.pulsekraft.net.Probe
import com.krafttools.pulsekraft.ui.theme.PulsePalette

/**
 * What the screen is showing right now.
 *
 * A sealed set of four, because every other shape is a lie waiting to
 * happen: a test that has not run, a test running, a test that finished,
 * and a test that could not run at all. A screen that showed a number
 * in the last two of those without saying which would be showing a
 * measurement that never happened.
 */
private sealed interface MeasureState {
    data object Idle : MeasureState
    data class Running(val phase: Phase) : MeasureState
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
            MeasureState.Idle -> IdleBlock(profile = "Light") { runTest { state = it } }
            is MeasureState.Running -> RunningBlock(current.phase)
            is MeasureState.Failed -> FailedBlock(current)
            is MeasureState.Done -> DoneBlock(
                report = current.report,
                methodOpen = methodOpen,
                onToggleMethod = { methodOpen = !methodOpen },
            )
        }

        HorizontalDivider(color = PulsePalette.GridLine)
        Text(
            text = "v${com.krafttools.pulsekraft.BuildConfig.VERSION_NAME}" +
                " · to ${com.krafttools.pulsekraft.BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

/**
 * Runs the probe off the main thread and publishes each phase.
 *
 * A separate thread rather than a coroutine because the probe already
 * owns a thread per transfer direction and blocks for as long as the
 * test takes. Wrapping that in a coroutine would mean a dispatcher
 * whose only job was to wait for another thread.
 */
private fun runTest(set: (MeasureState) -> Unit) {
    Thread({
        val result = Probe(volume = Volume.LIGHT).run { phase ->
            set(MeasureState.Running(phase))
        }
        set(
            if (result.report != null) MeasureState.Done(result.report)
            else MeasureState.Failed(
                result.failure?.what ?: "The test could not run",
                result.failure?.why ?: "an unknown reason",
            ),
        )
    }, "pulsekraft-ui-driver").apply { isDaemon = true }.start()
}

@Composable
private fun IdleBlock(profile: String, onRun: () -> Unit) {
    Text(
        text = "Measures this connection to Cloudflare's nearest edge.",
        style = MaterialTheme.typography.bodyLarge,
        color = PulsePalette.OnSurface,
    )
    Text(
        text = "$profile profile · about 20 MB",
        style = MaterialTheme.typography.bodyMedium,
        color = PulsePalette.OnSurfaceVariant,
    )
    Button(onClick = onRun, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Run test")
    }
}

@Composable
private fun RunningBlock(phase: Phase) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(
            color = PulsePalette.Pulse,
            modifier = Modifier.heightIn(min = 32.dp),
        )
        Text(
            text = phase.label + "…",
            style = MaterialTheme.typography.titleMedium,
            color = PulsePalette.OnSurface,
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
}

@Composable
private fun DoneBlock(
    report: TestReport,
    methodOpen: Boolean,
    onToggleMethod: () -> Unit,
) {
    report.rejectedBecause?.let {
        // A refused or truncated transfer is stated before any figure,
        // so a partial result is never read as a complete one.
        Text(
            text = "Partial result: ${it.explanation}.",
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.Warning,
        )
    }

    ThroughputRow("Download", report.download, report.downloadMayBeBufferLimited())
    ThroughputRow("Upload", report.upload, report.uploadMayBeBufferLimited())

    HorizontalDivider(color = PulsePalette.GridLine)

    FigureRow("Idle latency", formatMs(report.idleLatency?.medianMs), PulsePalette.Pulse)
    FigureRow(
        "Latency under load",
        formatMs(report.loadedDuringDownload?.medianMs),
        PulsePalette.Pulse,
    )
    report.bufferbloatIndex?.let { index ->
        val grade = report.bufferbloatGrade
        FigureRow(
            "Bufferbloat",
            "%.2f× · %s".format(index, grade?.label ?: "—"),
            if (grade == Bufferbloat.Grade.NONE || grade == null) PulsePalette.OnSurface
            else PulsePalette.Warning,
        )
    }

    HorizontalDivider(color = PulsePalette.GridLine)

    report.stability?.let {
        FigureRow(
            "Stability",
            it.verdict.label,
            if (it.verdict == Latency.Verdict.STEADY) PulsePalette.OnSurface
            else PulsePalette.Warning,
        )
        Text(
            text = it.verdict.meaning,
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
    }

    HorizontalDivider(color = PulsePalette.GridLine)

    // The method is a paragraph, and paragraphs belong collapsed. Five
    // lines of it on the main screen would be the wall of text this
    // app exists not to be.
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
private fun ThroughputRow(label: String, value: Throughput?, maybeCapped: Boolean?) {
    FigureRow(
        label,
        if (value == null) "—" else "%.0f Mbps".format(value.averageMbps),
        PulsePalette.Primary,
    )
    if (value != null) {
        Text(
            // The median and the spread describe the shape of the
            // transfer, not its speed. Shown beside the average, and
            // labelled, so neither is mistaken for the other.
            text = "spread %.1f× · p10 %.0f · p90 %.0f".format(
                value.spreadRatio, value.p10Mbps, value.p90Mbps,
            ),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
    if (maybeCapped == true) {
        // The honest self-check, on screen. A low result that this app's
        // own buffer ceiling could explain must say so, or the number is
        // reporting the app rather than the line.
        Text(
            text = "Near this app's buffer ceiling — the line may be faster.",
            style = MaterialTheme.typography.bodySmall,
            color = PulsePalette.Warning,
        )
    }
}

@Composable
private fun FigureRow(label: String, value: String, colour: androidx.compose.ui.graphics.Color) {
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
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = colour,
        )
    }
}

private fun formatMs(value: Double?): String =
    if (value == null) "—" else "%.0f ms".format(value)
