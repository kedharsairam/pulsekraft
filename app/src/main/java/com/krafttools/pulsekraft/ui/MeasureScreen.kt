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
private data class PhaseFigure(val phase: Phase, val figure: String)

/**
 * What to call a phase in a list of results.
 *
 * The running screen says "Watching for spikes" because something is
 * happening and a sentence is right. A list of finished figures is a
 * table, and a table does not contain imperatives — it said "Watching
 * for spikes   213 ms", which is a thing being done next to a thing
 * that already happened.
 */
private fun Phase.asNoun(): String = when (this) {
    Phase.CONNECTING -> "Connecting"
    Phase.IDLE_LATENCY -> "Idle latency"
    Phase.DOWNLOAD -> "Download"
    Phase.UPLOAD -> "Upload"
    Phase.STABILITY -> "Stability"
    Phase.DONE -> "Done"
}

private sealed interface MeasureState {
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
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf<MeasureState>(MeasureState.Idle(Volume.LIGHT)) }
    var methodOpen by remember { mutableStateOf(false) }
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
}

/** Ten frames a second. A number that updates faster is not read faster. */
private const val FRAME_MS = 100L

@Composable
private fun Footer() {
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
private fun runTest(
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
 * sockets, which is what unblocks a read; the interrupt is what
 * unblocks a sleep between samples. Calling only one leaves a cancel
 * that appears to work on some phases and hangs on others.
 */
private class RunningTest(private val thread: Thread, private val probe: Probe) {
    fun cancel() {
        probe.cancel()
        thread.interrupt()
    }
}

/**
 * The idle screen.
 *
 * Everything that was on it before was filler. The app's own name, at
 * 16sp, in the middle of the screen, saying nothing — the person has
 * the launcher in front of them and does not need to be told what app
 * this is. What earns the space instead is the control itself, larger,
 * and a mark that actually pulses, because that is what the app is
 * called and until now the name was the only part of it that did
 * anything.
 */
@Composable
private fun IdleBlock(
    volume: Volume,
    link: LinkState,
    refusal: String?,
    onVolume: (Volume) -> Unit,
    onRun: () -> Unit,
) {
    val profile = Profiles.of(volume)
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(16.dp))
        VolumeSelector(volume, onVolume)
        Spacer(Modifier.height(30.dp))

        PulsingControl(onRun, enabled = refusal == null)
        Spacer(Modifier.height(30.dp))

        Text(
            // An instruction, because a disc with a mark inside it does
            // not look like something you press.
            text = "Tap to measure this connection",
            style = MaterialTheme.typography.titleMedium,
            color = PulsePalette.OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            // The cost, in the words of the link actually carrying it.
            // "25 MB" means one thing on Wi-Fi and another on mobile
            // data, and this is the app asking for permission to spend
            // it — so the number is shown beside what it will be spent
            // on rather than floating free.
            text = costLine(volume, link),
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // A refusal lives here, under the cost, in the accent that means
        // something is wrong. It is not a dialog: the answer belongs
        // beside the control that produced it, and someone who is
        // offline should see why the app is not working in the place
        // where they would press it.
        refusal?.let {
            Spacer(Modifier.height(18.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = PulsePalette.Warning,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(26.dp))
        HorizontalDivider(color = PulsePalette.GridLine)
        Spacer(Modifier.height(14.dp))

        // What the test will measure, as four nouns.
        //
        // This was the words "Measuring idle latency · measuring
        // download · measuring upload · watching for spikes", which is
        // the same word four times, wraps mid-list with a middot left
        // dangling at the end of the first line, and reads as an
        // instruction rather than a list of subjects. The phases have
        // labels for the running screen, where a sentence is right
        // because something is happening. Standing still, they want
        // names.
        Text(
            text = MEASUREMENTS.joinToString("   ·   "),
            style = MaterialTheme.typography.labelMedium.merge(Tabular),
            color = PulsePalette.OnSurfaceVariant,
            textAlign = TextAlign.Center,
            letterSpacing = 0.6.sp,
        )
    }
}

/**
 * Light or Full, and what each will cost.
 *
 * This control did not exist while the README said a heavier test was
 * opt-in. `Volume.FULL` was defined, tested and unreachable, which is
 * worse than not having written it: the documentation described a
 * choice nobody could make.
 *
 * Shown as two pills rather than a switch because they are not on and
 * off — they are two different measurements with two different costs,
 * and a switch implies a binary where the real distinction is magnitude.
 * Each carries its own megabyte figure so the choice is priced where it
 * is made, which is the whole reason to make it at all.
 */
@Composable
private fun VolumeSelector(volume: Volume, onVolume: (Volume) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Volume.entries.forEach { option ->
            val selected = option == volume
            val megabytes = "%.0f MB".format(Policy.megabytesFor(option))
            val label = if (option == Volume.LIGHT) "Light" else "Full"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) PulsePalette.SurfaceRaised else Color.Transparent
                    )
                    .border(
                        width = 1.dp,
                        color = if (selected) PulsePalette.Primary else PulsePalette.GridLine,
                        shape = RoundedCornerShape(50),
                    )
                    .clickable { onVolume(option) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 20.dp, vertical = 11.dp)
                    .semantics { contentDescription = "$label, $megabytes" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$label  $megabytes",
                    style = MaterialTheme.typography.labelLarge.merge(Tabular),
                    color = if (selected) PulsePalette.OnSurface else PulsePalette.OnSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What this run will cost, in the words of the link carrying it.
 *
 * Reads the byte ceiling from the profile rather than carrying a
 * number beside this string, so the figure here and the figure the
 * policy warns with cannot drift apart — and on the run where being
 * wrong would cost somebody money, they are the same number.
 */
private fun costLine(volume: Volume, link: LinkState): String {
    val megabytes = "%.0f MB".format(Policy.megabytesFor(volume))
    val seconds = "%.0f".format(
        (Profiles.of(volume).graceMillis + Profiles.of(volume).measureMillis) / 1000.0
    )
    // "on" for a network and "of" for mobile data, because the first
    // says where the data goes and the second says whose it is. "25 MB
    // Wi-Fi" said neither and read as a label with a word missing.
    val where = when {
        !link.connected -> "You are offline"
        link.metered -> "of mobile data"
        else -> "on ${link.link.label}"
    }
    return "About $megabytes $where · $seconds seconds each way"
}

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
private fun StoppedBlock(done: List<PhaseFigure>, onAgain: () -> Unit) {
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

/** The four things a run measures, as nouns. */
private val MEASUREMENTS = listOf("Latency", "Download", "Upload", "Stability")

/**
 * The control, breathing.
 *
 * A slow pulse on the ring and the mark, and it is the one animation in
 * the app that runs with nothing happening — which is the point of it.
 * Every other transition is a response to something; this one is the
 * app saying it is awake and ready, and it costs no screen space to say
 * it. It stops the moment a test starts, because from then on the trace
 * is the thing that moves and two moving things is one too many.
 *
 * The period is 1600ms and the amplitude small. A fast pulse reads as
 * an alert; a slow one reads as breathing, which is what is meant.
 */
@Composable
private fun PulsingControl(onRun: () -> Unit, enabled: Boolean = true) {
    val breath = rememberInfiniteTransition(label = "breath")
    val swell by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "swell",
    )
    // A control the app will not press stops breathing. The pulse said
    // "ready", and when the answer is no, saying ready is a lie.
    if (!enabled) {
        StaticMark(214.dp, PulsePalette.GridLine)
        return
    }
    Box(modifier = Modifier.size(214.dp), contentAlignment = Alignment.Center) {
        // An echo that expands past the disc and fades on the same
        // cycle. The static edge stays put so the control's bounds are
        // always readable — this is the pulse leaving, not the button.
        Box(
            modifier = Modifier
                .size(214.dp)
                .scale(0.86f + swell * 0.16f)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = PulsePalette.Primary.copy(alpha = 0.5f * (1f - swell)),
                    shape = CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(214.dp)
                .clip(CircleShape)
                .background(PulsePalette.Surface)
                // border, not background. `background(brush)` paints the
                // whole area, which turns the control into a solid disc;
                // a ring has to be an outline, and the brush overload of
                // `border` strokes exactly the boundary at any density.
                .border(
                    brush = Brush.verticalGradient(
                        listOf(
                            PulsePalette.Primary.copy(alpha = 0.45f + swell * 0.35f),
                            PulsePalette.GridLine,
                        )
                    ),
                    shape = CircleShape,
                    width = 1.5.dp,
                )
                .clickable(onClick = onRun)
                .semantics { contentDescription = "Run test" },
            contentAlignment = Alignment.Center,
        ) {
            PulseMark(scale = 0.98f + swell * 0.04f)
        }
    }
}

/**
 * The control when it cannot be pressed.
 *
 * Same disc, same mark, no ring and no animation. A dimmed version of
 * the live control would still read as something to try; this reads as
 * the absence of one, and the reason is on screen underneath it.
 */
@Composable
private fun StaticMark(size: androidx.compose.ui.unit.Dp, tint: Color) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(PulsePalette.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .border(1.dp, tint, CircleShape),
        )
        PulseMark(scale = 1f, colour = PulsePalette.OnSurfaceVariant)
    }
}

/**
 * One beat.
 *
 * Flat, spike, deeper dip, flat. The previous mark was a five-point
 * zigzag, which reads as a broken line rather than a pulse — and the
 * whole name of the app rests on this shape being recognisable in the
 * launcher at forty pixels.
 *
 * `scale` lets the breathing animation swell the mark without redrawing
 * it, so the geometry is described once and animated by transform.
 */
@Composable
private fun PulseMark(scale: Float = 1f, colour: Color = PulsePalette.Pulse) {
    Canvas(modifier = Modifier.size(104.dp).scale(scale)) {
        val w = size.width
        val h = size.height
        val baseline = h * 0.52f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.06f, baseline)
            lineTo(w * 0.30f, baseline)
            // The upstroke is fast and the downstroke faster, which is
            // what makes it read as a heartbeat rather than a hill.
            cubicTo(
                w * 0.38f, baseline,
                w * 0.40f, h * 0.20f,
                w * 0.48f, h * 0.20f,
            )
            cubicTo(
                w * 0.56f, h * 0.20f,
                w * 0.58f, h * 0.82f,
                w * 0.64f, h * 0.82f,
            )
            cubicTo(
                w * 0.70f, h * 0.82f,
                w * 0.71f, baseline,
                w * 0.79f, baseline,
            )
            lineTo(w * 0.94f, baseline)
        }
        drawPath(
            path = path,
            color = colour,
            style = Stroke(
                width = w * 0.072f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

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
private fun PhaseTrack(phase: Phase) {
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
private val MEASUREMENT_PHASES = listOf(
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
private fun ReadingCluster(live: LiveState, volume: Volume) {
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
private fun LiveState.displayed(): Double {
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
private fun LiveState.fitSpan(): AxisSpan {
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
private data class AxisSpan(val low: Double, val high: Double)

/**
 * How much data this test is going to move, for the "n of N MB" line.
 *
 * Reads the same constant the probe is given, so the denominator on
 * screen cannot drift from the number the transfer is actually
 * working to. Two copies of "the volume" is how a progress line starts
 * promising 25 MB of a 100 MB test.
 */
private fun LiveState.targetMegabytes(volume: Volume): Double =
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
private fun MeasureState.Running.closeOffPhase(): List<PhaseFigure> {
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
private fun RunningBlock(
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
            modifier = Modifier.height(300.dp),
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
private fun PhaseLog(done: List<PhaseFigure>) {
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
private val VOICE_LIMIT_MS = Verdict.INTERACTIVE_LIMIT_MS

/** The cap on how far the hero figure grows with the reader's settings. */
private const val HERO_FONT_SCALE_CAP = 1.35f

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
private fun DoneBlock(
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
private fun PrimaryAction(label: String, onClick: () -> Unit) {
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
private fun ActionRow(label: String, tint: Color, onClick: () -> Unit) {
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
private fun FigureRow(label: String, value: String?) {
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
private fun verdict(report: TestReport): String = Verdict.for_(
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
private fun stabilityCeiling(report: TestReport): Double {
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

private fun formatReading(reading: Reading, value: Double): String = when (reading) {
    Reading.LATENCY -> "%.0f".format(value)
    else -> if (value >= 100) "%.0f".format(value) else "%.1f".format(value)
}
