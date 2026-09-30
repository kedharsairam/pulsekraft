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
 * The waiting state, and the one control in the app.
 *
 * Split out of the screen file this started as one long column with. It
 * is the state with the least going on and the most decisions in it:
 * which profile, what it costs, on which network, and what to say when
 * the answer is no.
 */

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
internal fun IdleBlock(
    volume: Volume,
    link: LinkState,
    refusal: String?,
    onAbout: () -> Unit,
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

        Spacer(Modifier.height(24.dp))
        // About lives on the idle screen rather than the result screen
        // because this is where you go when you are not mid-test. It is
        // the one question worth answering before pressing anything: what
        // does this want permission for.
        Text(
            text = "What this app wants, and why",
            style = MaterialTheme.typography.labelLarge,
            color = PulsePalette.Primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onAbout)
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
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
internal fun VolumeSelector(volume: Volume, onVolume: (Volume) -> Unit) {
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
internal fun costLine(volume: Volume, link: LinkState): String {
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

/** The four things a run measures, as nouns. */
internal val MEASUREMENTS = listOf("Latency", "Download", "Upload", "Stability")

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
internal fun PulsingControl(onRun: () -> Unit, enabled: Boolean = true) {
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
internal fun StaticMark(size: androidx.compose.ui.unit.Dp, tint: Color) {
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
internal fun PulseMark(scale: Float = 1f, colour: Color = PulsePalette.Pulse) {
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
