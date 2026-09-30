package com.krafttools.pulsekraft.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.krafttools.pulsekraft.BuildConfig
import com.krafttools.pulsekraft.MeasureViewModel
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.net.Reading
import com.krafttools.pulsekraft.ui.theme.PulsePalette

// ---------------------------------------------------------------------------
// The state contract
//
// These live here rather than in the screen composable because both the
// model that owns them and the screens that render them need them, and
// because they are the vocabulary the whole `ui` package shares.
// ---------------------------------------------------------------------------

/** One completed phase, reduced to the single figure worth keeping. */
internal data class PhaseFigure(val phase: com.krafttools.pulsekraft.net.Phase, val figure: String)

/**
 * What to call a phase in a list of results.
 *
 * The running screen says "Watching for spikes" because something is
 * happening and a sentence is right. A list of finished figures is a
 * table, and a table does not contain imperatives — it read "Watching
 * for spikes   213 ms", which is a thing being done next to a thing
 * that already happened.
 */
internal fun com.krafttools.pulsekraft.net.Phase.asNoun(): String = when (this) {
    com.krafttools.pulsekraft.net.Phase.CONNECTING -> "Connecting"
    com.krafttools.pulsekraft.net.Phase.IDLE_LATENCY -> "Idle latency"
    com.krafttools.pulsekraft.net.Phase.DOWNLOAD -> "Download"
    com.krafttools.pulsekraft.net.Phase.UPLOAD -> "Upload"
    com.krafttools.pulsekraft.net.Phase.STABILITY -> "Stability"
    com.krafttools.pulsekraft.net.Phase.DONE -> "Done"
}

/**
 * Every state the one screen can be in.
 *
 * The idle state carries the chosen profile, the current link and any
 * refusal, because all three change what the control says it will do and
 * none of them is a property of the measurement. The running state
 * carries its profile for the same reason: the "n of N MB" denominator
 * must be the profile actually being run, not a constant beside the code
 * that prints it.
 */
internal sealed interface MeasureState {

    /**
     * Waiting to start.
     *
     * `refusal` is set when the policy declined, and is why the reason
     * sits on the idle screen instead of in a dialog: the answer belongs
     * beside the control that produced it, and a person who is offline
     * should see why the app is not working in the place where they would
     * press it.
     */
    data class Idle(
        val volume: Volume = Volume.LIGHT,
        val refusal: String? = null,
        val link: com.krafttools.pulsekraft.core.LinkState =
            com.krafttools.pulsekraft.core.LinkState(),
    ) : MeasureState

    data class Running(
        val live: com.krafttools.pulsekraft.net.LiveState?,
        val phase: com.krafttools.pulsekraft.net.Phase,
        val volume: Volume = Volume.LIGHT,
        /**
         * The phases already finished, kept rather than recomputed so the
         * screen accumulates: a running test showing only the current
         * phase makes the first twenty seconds look like nothing is
         * happening, when an idle latency and a download figure have
         * already been measured and thrown away.
         */
        val done: List<PhaseFigure> = emptyList(),
    ) : MeasureState

    data class Done(val report: com.krafttools.pulsekraft.core.TestReport) : MeasureState

    data class Failed(val what: String, val why: String) : MeasureState

    /**
     * Stopped by the person, not by a fault.
     *
     * Distinct from a failure on purpose. They did the stopping, and the
     * phases that finished are still real measurements of real transfers.
     */
    data class Stopped(
        val done: List<PhaseFigure>,
        val volume: Volume,
    ) : MeasureState
}

/**
 * Fold the phase in flight into the tally and drop its live series.
 *
 * Summarised from the interval series rather than carried out of the
 * probe, because by this point the probe's own summary is what the result
 * screen will use and the two must not disagree: the live figure is the
 * goodput so far, the reported one is over the whole measured window, and
 * they are close but not equal.
 */
internal fun MeasureState.Running.closeOffPhase(): List<PhaseFigure> {
    val live = live ?: return done
    val series = live.series
    if (series.isEmpty()) return done
    if (done.any { it.phase == phase }) return done

    val figure = when (live.reading) {
        Reading.LATENCY -> {
            val sorted = series.sorted()
            "%.0f ms".format(sorted[sorted.size / 2])
        }
        // The probe's own running goodput, not the mean of the series.
        // The two differ by enough to matter and only one of them is the
        // figure the result screen will print.
        else -> live.averageMbpsToDate
            .takeIf { it > 0.0 }
            // "0 Mbps" reads as a broken app rather than as a very slow
            // one, because it is what a failed transfer would print too.
            ?.let { if (it >= 1.0) "%.0f Mbps".format(it) else "%.1f Mbps".format(it) }
            ?: return done
    }
    return done + PhaseFigure(phase, figure)
}

/**
 * Tabular figures, applied to every number that can change.
 *
 * The single most important typographic detail in a live measurement,
 * and the easiest to miss. With proportional figures, a digit changing
 * from 1 to 8 reflows every glyph after it, so a value ticking ten times
 * a second is an illegible shimmer. `tnum` fixes every digit to the same
 * width and the number stops moving except where the number moved.
 */
internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

/**
 * How a reading is written.
 *
 * Whole numbers above a hundred, a decimal below it. A latency of 95.4
 * carries information that 95 does not; a throughput of 104.7 Mbps does
 * not.
 */
internal fun formatReading(reading: Reading, value: Double): String = when (reading) {
    Reading.LATENCY -> "%.0f".format(value)
    else -> if (value >= 100) "%.0f".format(value) else "%.1f".format(value)
}

// ---------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------

/**
 * The screen, wired to the model that holds it.
 *
 * Deliberately thin. Everything with state or a side effect lives in
 * [MeasureViewModel], which survives a rotation; everything with a shape
 * lives in [MeasureContent]. This is the join between them, plus the two
 * things that need a composition: haptics and the about sheet.
 */
@Composable
internal fun MeasureScreen(
    model: MeasureViewModel,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    // The about sheet is the one piece of state that genuinely should not
    // survive anything. It is about the app rather than about a
    // measurement, and it is the thing someone rotates away from and back
    // to, so losing it is the correct behaviour rather than a bug.
    var aboutOpen by rememberSaveable { mutableStateOf(false) }

    // Re-read the link whenever this composition comes up, which includes
    // after a rotation. The screen may be showing a figure priced on the
    // network as it was a moment ago.
    LaunchedEffect(model) { model.refreshLink() }

    MeasureContent(
        state = model.state,
        methodOpen = model.methodOpen,
        onRun = { volume ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            model.run(volume)
        },
        onCancel = model::cancel,
        onVolume = model::chooseVolume,
        onAbout = { aboutOpen = true },
        onAgain = model::again,
        onToggleMethod = model::toggleMethod,
        modifier = modifier,
    )

    if (aboutOpen) {
        AboutSheet(onDismiss = { aboutOpen = false })
    }
}

@Composable
internal fun Footer() {
    Column {
        HorizontalDivider(color = PulsePalette.GridLine)
        Text(
            // The build code went here once as "to 1", which reads as a
            // fragment of a sentence about something. A build code is
            // what a bug report needs and nobody else does, and it is one
            // number further down the manifest for anyone who wants it.
            text = "PulseKraft ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}