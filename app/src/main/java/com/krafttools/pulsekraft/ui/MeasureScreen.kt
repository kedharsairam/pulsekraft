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

/** One completed phase, reduced to the single figure worth keeping. */
internal data class PhaseFigure(val phase: Phase, val figure: String)

/**
 * What to call a phase in a list of results.
 *
 * The running screen says "Watching for spikes" because something is
 * happening and a sentence is right. A list of finished figures is a
 * table, and a table does not contain imperatives — it said "Watching
 * for spikes   213 ms", which is a thing being done next to a thing
 * that already happened.
 */
internal fun Phase.asNoun(): String = when (this) {
    Phase.CONNECTING -> "Connecting"
    Phase.IDLE_LATENCY -> "Idle latency"
    Phase.DOWNLOAD -> "Download"
    Phase.UPLOAD -> "Upload"
    Phase.STABILITY -> "Stability"
    Phase.DONE -> "Done"

}

internal sealed interface MeasureState {
    /**
     * Waiting to start.
     *
     * `refusal` is set when the pre-flight check declined to run, and
     * is why the app holds the reason on the idle screen instead of
     * opening a dialog: the answer belongs beside the button that
     * produced it, and a person who is offline should see why the app
     * is not working in the place where they would press it.
     */
    data class Idle(
        val volume: Volume = Volume.LIGHT,
        val refusal: String? = null,
        val link: LinkState = LinkState(),
    ) : MeasureState
    /**
     * A finished phase and the figure it produced.
     *
     * Kept as state rather than recomputed so the screen accumulates: a
     * running test that shows nothing but the current phase makes the
     * first twenty seconds feel like nothing is happening, when in fact
     * an idle latency and a download figure have already been measured.
     */
    data class Running(
        val live: LiveState?,
        val phase: Phase,
        val volume: Volume = Volume.LIGHT,
        val done: List<PhaseFigure> = emptyList(),
    ) : MeasureState
    data class Done(val report: TestReport) : MeasureState
    data class Failed(val what: String, val why: String) : MeasureState

    /**
     * Stopped by the person.
     *
     * Distinct from a failure on purpose. A cancelled run has figures
     * for the phases that finished and nothing usable for the rest, and
     * showing it as an error would be wrong — they did the stopping.
     * A partial report still renders, with a line saying so, because the
     * download figure from a cancelled run is a real measurement of a
     * real transfer and throwing it away would be its own kind of lie.
     */
    data class Stopped(
        val done: List<PhaseFigure>,
        val volume: Volume,
    ) : MeasureState
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

internal val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf<MeasureState>(MeasureState.Idle(Volume.LIGHT)) }
    var methodOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val reach = remember(context) { Reachability(context) }

    // The thread doing the measuring, so a running test can be stopped.
    // A forty-five second run that cannot be cancelled is a run you sit
    // through, and nothing else in this file matters if that is true.
    var runner by remember { mutableStateOf<RunningTest?>(null) }
    val scope = rememberCoroutineScope()

    // One tick per state change, not one per sample. A screen that
    // re-renders a hundred times a second is a screen that cannot be
    // read, and the hardware gets no say in how fast a person reads.
    var lastFrame = 0L

    // The link is re-read whenever the screen comes to rest, and again
    // at the moment the control is pressed. Not observed continuously:
    // the two moments that matter are "what did the idle screen promise"
    // and "is that promise still true", and a listener firing on every
    // network change would cost more than it is worth for a figure that
    // only has to be right when it is read.
    suspend fun refreshLink() {
        val link = withContext(Dispatchers.IO) { reach.current() }
        val idle = state as? MeasureState.Idle ?: return
        if (idle.link != link) {
            state = idle.copy(
                link = link,
                // A refusal from the last press is about that press.
                // Carrying it forward after the network changed would
                // leave a message contradicting the screen it sits on.
                refusal = null,
            )
        }
    }
    LaunchedEffect(Unit) { refreshLink() }

    // Start a run. A named local rather than an argument list inside the
    // `when`, because the three callbacks it hands the probe are the
    // part of this screen worth reading and they were eight levels deep
    // in a lambda argument.
    fun startRun(volume: Volume) {
        runner = runTest(
            volume = volume,
            onPhase = { phase ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val previous = state as? MeasureState.Running
                state = MeasureState.Running(
                    live = null,
                    phase = phase,
                    volume = volume,
                    done = previous?.closeOffPhase() ?: emptyList(),
                )
            },
            onLive = { live ->
                val now = System.currentTimeMillis()
                if (now - lastFrame >= FRAME_MS) {
                    lastFrame = now
                    state = MeasureState.Running(
                        live = live,
                        phase = live.phase,
                        volume = volume,
                        done = (state as? MeasureState.Running)?.done ?: emptyList(),
                    )
                }
            },
            onFinish = { result ->
                state = when {
                    result.report != null -> MeasureState.Done(result.report)
                    result.failure != null -> MeasureState.Failed(
                        result.failure.what,
                        result.failure.why,
                    )
                    // A null report and a null failure means the probe
                    // was interrupted, which is what cancelling is.
                    // Reporting that as a failure would blame the
                    // network for something the person did.
                    //
                    // The phase in flight is folded in first. Cancelling
                    // at the sixteen second mark discarded a finished
                    // idle-latency measurement — the figure was in hand
                    // and the screen said nothing had finished, which is
                    // the same class of lie as publishing a number the
                    // app cannot stand behind, just in the other
                    // direction.
                    else -> MeasureState.Stopped(
                        done = (state as? MeasureState.Running)?.closeOffPhase()
                            ?: emptyList(),
                        volume = volume,
                    )
                }
                runner = null
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        // ONE flexible child, not two.
        //
        // This column used to hold the state block, a weight(1f) spacer
        // and the footer. DoneBlock's trace is also weight(1f), and both
        // of those were children of THIS scope — so the leftover height
        // was split evenly between a plot and an empty gap, which is why
        // the result screen had a band of nothing under its content that
        // nothing could fill.
        //
        // The content region takes the slack as one box and each block
        // lays itself out inside it. A block's own weight then resolves
        // against its own column, which is the only scope where that
        // means anything.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // Room above the footer's own rule. The phase log is
                // pushed to the bottom of this region by a weight, and
                // without this its last row sat on the divider with
                // four pixels between them, which reads as two rules and
                // a gap rather than as one screen.
                .padding(bottom = 14.dp),
            contentAlignment = Alignment.TopStart,
        ) {
        // Keyed on the class, not the value. Running state is a new
        // instance ten times a second, and keying on the instance would
        // start a crossfade on every live reading — the screen would
        // strobe. Keying on the class animates the three transitions
        // that are transitions (idle to running, running to result,
        // result back to idle) and nothing else.
        Crossfade(
            targetState = state::class,
            animationSpec = tween(340),
            label = "screen",
        ) { _ ->
        when (val current = state) {
            is MeasureState.Idle -> IdleBlock(
                volume = current.volume,
                link = current.link,
                refusal = current.refusal,
                onAbout = { aboutOpen = true },
                onVolume = { volume ->
                    // Choosing the heavier profile on a metered network
                    // does not start anything; it just re-prices the
                    // button, and the refusal text appears immediately
                    // so the person knows before they press it.
                    state = MeasureState.Idle(
                        volume = volume,
                        link = current.link,
                        refusal = (Policy.decide(current.link, volume)
                            as? Permission.Refuse)?.reason,
                    )
                },
                onRun = {
                    lastFrame = 0L
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val volume = current.volume
                    scope.launch {
                        // Read at the moment of the press, not from the
                        // value the idle screen was built with. The
                        // network can change while someone is looking at
                        // a screen that says what it will cost, and the
                        // promise has to be the one that holds.
                        val link = withContext(Dispatchers.IO) { reach.current() }
                        when (val decision = Policy.decide(link, volume)) {
                            is Permission.Refuse -> {
                                state = MeasureState.Idle(volume, decision.reason, link)
                                return@launch
                            }
                            // A warning does not stop the run. The cost
                            // was on the idle screen in the same words
                            // before the button was pressed, so the press
                            // IS the agreement; a dialog restating it
                            // would be one more tap to say the thing the
                            // screen already said.
                            else -> state = MeasureState.Idle(volume, null, link)
                        }
                        startRun(volume)
                    }
                },
            )

            is MeasureState.Running -> RunningBlock(current, onCancel = {
                runner?.cancel()
            })

            is MeasureState.Stopped -> {
                StoppedBlock(
                    done = current.done,
                    onAgain = {
                        methodOpen = false
                        state = MeasureState.Idle(current.volume)
                    },
                )
            }

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
                    // Done used to be terminal. There was no control
                    // anywhere on the result screen that would start
                    // another test, so the second run of this app
                    // required killing the process. A measurement tool
                    // you can measure once is a demo, not a tool.
                    onAgain = {
                        methodOpen = false
                        // The volume they chose is remembered, and the
                        // link is re-read by the effect that runs on the
                        // way back to idle. Going back to the default
                        // profile here would silently discard a choice
                        // they made on purpose.
                        state = MeasureState.Idle(current.report.volume)
                    },
                )
            }
        }
        }
        }

        Footer()
    }

    // Above the whole screen rather than inside any one block: it is
    // about the app, not about a state.
    if (aboutOpen) {
        AboutSheet(onDismiss = { aboutOpen = false })
    }
}

/** Ten frames a second. A number that updates faster is not read faster. */

private const val FRAME_MS = 100L

@Composable
internal fun Footer() {
    Column {
        HorizontalDivider(color = PulsePalette.GridLine)
        Text(
            // The build code went here as "to 1", which reads as a
            // fragment of a sentence about something. A build code is
            // what a bug report needs and nobody else does, and it is
            // one number further down the manifest for anyone who wants
            // it. The name is the only part worth screen space.
            text = "PulseKraft ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = PulsePalette.OnSurfaceVariant,
        )
    }
}

/**
 * Runs a test on its own thread and hands that thread back.
 *
 * Returning the thread is the whole reason this function changed shape.
 * An earlier version returned Unit, which meant there was no handle on
 * the work and therefore no way to stop it: a forty-five second run was
 * something to sit through. Now the caller keeps the handle and
 * interrupts it, and the probe treats an interrupt as a stop rather
 * than as a fault.
 *
 * Every callback hops to the main thread on the way out. Compose state
 * may only be written from one, and the probe emits from two of its
 * own; dropping the hop is a crash rather than a glitch.
 */
internal fun runTest(
    volume: Volume,
    onPhase: (Phase) -> Unit,
    onLive: (LiveState) -> Unit,
    onFinish: (Probe.Result) -> Unit,
): RunningTest {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun hop(body: () -> Unit) = main.post(body)
    val probe = Probe(volume = volume)
    val thread = Thread({
        val result = probe.run(
            onPhase = { hop { onPhase(it) } },
            onLive = { hop { onLive(it) } },
        )
        hop { onFinish(result) }
    }, "pulsekraft-ui-driver").apply {
        isDaemon = true
        start()
    }
    return RunningTest(thread, probe)
}

/**
 * A test in progress, and the only two things anyone can do to it.
 *
 * Both halves matter and neither is sufficient. The probe closes its
 * sockets, which is what unblocks a read; the interrupt is what unblocks
 * a sleep between samples. Calling only one leaves a cancel that appears
 * to work on some phases and hangs on others.
 */
internal class RunningTest(private val thread: Thread, private val probe: Probe) {
    fun cancel() {
        probe.cancel()
        thread.interrupt()
    }
}

internal fun formatReading(reading: Reading, value: Double): String = when (reading) {
    Reading.LATENCY -> "%.0f".format(value)
    else -> if (value >= 100) "%.0f".format(value) else "%.1f".format(value)
}
