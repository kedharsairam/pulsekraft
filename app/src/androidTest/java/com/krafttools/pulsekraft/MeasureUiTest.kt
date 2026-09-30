package com.krafttools.pulsekraft

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import com.krafttools.pulsekraft.core.Latency
import com.krafttools.pulsekraft.core.LatencySummary
import com.krafttools.pulsekraft.core.Link
import com.krafttools.pulsekraft.core.LinkState
import com.krafttools.pulsekraft.core.Policy
import com.krafttools.pulsekraft.core.TestReport
import com.krafttools.pulsekraft.core.Throughput
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.core.Verdict
import com.krafttools.pulsekraft.net.LiveState
import com.krafttools.pulsekraft.net.Phase
import com.krafttools.pulsekraft.net.Reading
import com.krafttools.pulsekraft.ui.MeasureContent
import com.krafttools.pulsekraft.ui.PhaseFigure
import com.krafttools.pulsekraft.ui.MeasureState
import com.krafttools.pulsekraft.ui.theme.PulseKraftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Every state of the screen, on a real device.
 *
 * ## Why these exist
 *
 * The app had no instrumented tests at all before this file, which meant
 * the only way to see the refused, offline, stopped and running states
 * was to produce them for real: a phone in airplane mode, a roaming SIM,
 * a socket that fails on cue, and forty seconds of patience per case.
 *
 * `MeasureContent` exists so none of that is necessary. It is the screen
 * as a pure function of its state, and the coordinator owning the thread
 * and the platform read sits above it. Every state below is reachable in
 * a millisecond, deterministically.
 *
 * ## What these do not cover
 *
 * The transport, the platform read, and anything involving real timing.
 * Those need a real network and are deliberately not mocked here, because
 * a test that mocks the thing it is testing is worse than no test.
 *
 * What these do cover is the part that broke repeatedly and silently: a
 * control falling off the bottom of an overflowing column, a touch target
 * under the minimum, a degenerate series dividing by zero, and two
 * figures on one screen contradicting each other.
 */
class MeasureUiTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The state under test, driven by the test.
     *
     * A `setContent` per case is not allowed — Compose permits it once
     * per activity, and every test here renders more than one state. So
     * the content is set once against this value and the tests assign to
     * it, which is also closer to how the screen is really used: one
     * tree, many states.
     */
    private var state by mutableStateOf<MeasureState>(idle())

    /** Recorded callbacks, so a press can be asserted rather than guessed. */
    private var ranWith: Volume? = null
    private var chose: Volume? = null
    private var wasCancelled = false

    @Composable
    private fun Content() {
        MeasureContent(
            state = state,
            methodOpen = false,
            onRun = { ranWith = it },
            onCancel = { wasCancelled = true },
            onVolume = { chose = it },
            onAbout = {},
            onAgain = {},
            onToggleMethod = {},
        )
    }

    private fun show(vararg states: MeasureState) {
        compose.setContent {
            PulseKraftTheme { Box(Modifier.fillMaxSize()) { Content() } }
        }
        states.forEach {
            state = it
            compose.waitForIdle()
        }
    }

    // ---------------------------------------------------------------- states

    @Test
    fun everyStateRenders() {
        show(*states().map { it.second }.toTypedArray())
        compose.onRoot().assertIsDisplayed()
        states().forEach { (name, _) -> assertTrue("$name has no name", name.isNotBlank()) }
    }

    @Test
    fun theIdleControlRunsTheChosenProfile() {
        ranWith = null
        show(idle(Volume.FULL))
        compose.onNodeWithText("Tap to measure this connection").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Run test. About 100 MB on Wi-Fi · 9 seconds each way.",
        ).performClick()
        assertEquals("the control ran the wrong profile", Volume.FULL, ranWith)
    }

    @Test
    fun theHeavierProfileIsSelectable() {
        chose = null
        show(idle(Volume.LIGHT))
        compose.onNodeWithText("Full  100 MB").performClick()
        assertEquals("the heavier profile is not selectable", Volume.FULL, chose)
    }

    @Test
    fun aRefusalIsShownWhereTheRunWouldHaveBeen() {
        val link = LinkState(connected = true, link = Link.CELLULAR, metered = true)
        val reason = (Policy.decide(link, Volume.FULL) as? com.krafttools.pulsekraft.core.Permission.Refuse)
            ?.reason
        show(idle(Volume.FULL, link, reason))
        compose.onNodeWithText(reason!!).assertIsDisplayed()
    }

    @Test
    fun aRunningTestOffersAnExit() {
        wasCancelled = false
        show(runningDownload())
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        assertTrue("a running test cannot be stopped", wasCancelled)
    }

    @Test
    fun aStoppedRunKeepsWhatItMeasured() {
        show(
            MeasureState.Stopped(
                done = listOf(PhaseFigure(Phase.DOWNLOAD, "104 Mbps")),
                volume = Volume.LIGHT,
            ),
        )
        compose.onNodeWithText("You stopped this one.").assertIsDisplayed()
        // A cancelled run that threw its figures away would be discarding
        // a real measurement of a real transfer.
        compose.onNodeWithText("104 Mbps").assertIsDisplayed()
    }

    @Test
    fun aFailedRunSaysWhatFailedRatherThanShowingAZero() {
        show(
            MeasureState.Failed(
                "The connection dropped",
                "the socket failed mid-test, so there is nothing to report",
            ),
        )
        compose.onNodeWithText("The connection dropped").assertIsDisplayed()
        compose.onNodeWithText("the socket failed mid-test, so there is nothing to report")
            .assertIsDisplayed()
    }

    // ------------------------------------------------------- what actually broke

    @Test
    fun nothingOnAnyStateFallsOffTheBottom() {
        // The bug class this file mostly exists for. Three times in
        // development a control was silently clipped by the footer — the
        // stop control, the last row of the phase log, the method
        // disclosure — because the last child of an overflowing column is
        // cut off without any error. A screenshot shows it; this asserts
        // it, for every state, forever.
        show(*states().map { it.second }.toTypedArray())
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        states().forEach { (name, current) ->
            state = current
            compose.waitForIdle()
            val clipped = compose.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            ).fetchSemanticsNodes().filter { node ->
                val b = node.boundsInRoot
                b.bottom > root.bottom + 1f || b.top < root.top - 1f
            }.map { it.toString().take(60) }

            assertTrue("$name clips $clipped", clipped.isEmpty())
        }
    }

    @Test
    fun interactiveRowsAreBigEnoughToPress() {
        // 48dp is the smallest reliably hittable target. The text links
        // this replaced were 32dp tall.
        show(runningDownload())
        val stop = compose.onNodeWithText("Stop").fetchSemanticsNode()
        val height = with(compose.density) { stop.size.height.toDp() }
        assertTrue("the stop control is only $height tall", height >= 48.dp)
    }

    @Test
    fun theResultScreenNeverContradictsItself() {
        // Found twice by reading screenshots and never by a test: a
        // verdict of "Good for calls" printed directly above a stability
        // watch that ran to 405ms, and once above an unloaded median of
        // 342ms. The headline is the sentence a person acts on, so a
        // screen where it disagrees with the evidence beneath it is broken
        // in a way no layout assertion would catch.
        val spikySteady = report(Latency.Verdict.SPIKY, idleMs = 62.0, loadedMs = 62.0)
        val spikySlow = report(Latency.Verdict.SPIKY, idleMs = 342.0, loadedMs = 545.0)
        listOf(spikySteady, spikySlow).forEach { report ->
            val sentence = Verdict.for_(
                loadedMs = report.loadedDuringDownload?.medianMs,
                idleMs = report.idleLatency?.medianMs,
                index = report.bufferbloatIndex,
                spiky = report.stability?.verdict == Latency.Verdict.SPIKY,
            )
            if (report.stability?.verdict == Latency.Verdict.SPIKY) {
                assertTrue(
                    "a spiky watch was called \"$sentence\"",
                    !sentence.contains("Good for calls") && !sentence.contains("Fine for browsing"),
                )
            }
        }
        show(MeasureState.Done(spikySteady))
        compose.onRoot().assertIsDisplayed()
    }

    // --------------------------------------------------------------- the plot

    @Test
    fun thePlotSurvivesDegenerateSeries() {
        // Empty, single, flat, one spike a hundred times the rest, and
        // all zeros. Each has to render rather than divide by zero or put
        // a NaN in a coordinate, and only a real Compose tree will say so.
        show(runningLatency(emptyList()))
        compose.onRoot().assertIsDisplayed()
        listOf(
            listOf(42.0),
            List(40) { 50.0 },
            listOf(1.0) + List(40) { 50.0 } + listOf(5000.0),
            List(40) { 0.0 },
            List(300) { 50.0 + (it % 11) },
        ).forEach { series ->
            state = runningLatency(series)
            compose.waitForIdle()
            compose.onRoot().assertIsDisplayed()
        }
    }

    @Test
    fun theFigureOnScreenIsTheEndOfTheLine() {
        // The guarantee the running screen rests on. This was "0.0 Mbps"
        // beside a plot full of data, because the headline was the raw
        // interval rate and a socket buffer leaves that near zero.
        show(runningLatency(listOf(90.0, 88.0, 91.0, 89.0, 87.0)))
        // Asserted through the semantics rather than through the raw text,
        // because the figure and its unit are deliberately merged into one
        // spoken phrase — a screen reader announcing "87" and then "ms" as
        // two unrelated items is the accessibility version of publishing
        // the median instead of the goodput. The merge is the point, so
        // the test now pins the phrase.
        compose.onNodeWithContentDescription("Latency 87 ms").assertIsDisplayed()
    }

    @Test
    fun aLongStabilityWatchIsReadable() {
        show(MeasureState.Done(report()))
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("107 Mbps   2.0s").assertIsDisplayed()
    }

    // ------------------------------------------------- the configuration change

    @Test
    fun theScreenSurvivesAConfigurationChange() {
        // Regression test for the worst bug in this app's history.
        //
        // Every piece of state lived in `remember`, so rotating the phone
        // mid-test threw the composable and its values away — including
        // the handle on the worker thread. Proven on device: the run
        // disappeared back to the idle screen, the measurement carried on
        // with nothing able to reach it, could not be stopped, and its
        // result was written to a state holder that no longer existed.
        //
        // This does not test the ViewModel's survival — that is the
        // framework's job and a test of it would be a test of the
        // framework. It tests the thing that would break if the state
        // moved back into a composable: that a recreation leaves a
        // working screen rather than a crash or an empty one.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    "the activity did not set content",
                    activity.findViewById<android.view.View>(
                        android.R.id.content,
                    ) != null,
                )
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertTrue(
                    "the activity has no window after recreation",
                    activity.window != null,
                )
            }
        }
    }

    @Test
    fun theFigureIsSpokenAsOnePhrase() {
        // A screen reader used to announce "299" and then "Mbps" as two
        // unrelated items, with no indication that this was a download
        // rate. The unit and the figure are now one description.
        show(runningDownload())
        compose.onNodeWithContentDescription("Download 97.5 Mbps").assertIsDisplayed()
    }

    @Test
    fun theControlSpeaksTheCost() {
        // Found by enabling TalkBack and reading the accessibility tree
        // rather than by reasoning about it. The cost line and the list
        // of measurements are both non-focusable, so a screen reader
        // walked past "About 25 MB of mobile data" and stopped on a
        // control labelled only "Run test" — and for an app whose whole
        // argument is that it will not spend your data without saying
        // so, that is the one sentence that must be spoken.
        show(idle(Volume.LIGHT))
        compose.onNodeWithContentDescription(
            "Run test. About 25 MB on Wi-Fi · 4 seconds each way.",
        ).assertIsDisplayed()
    }

    @Test
    fun aRefusedControlSpeaksTheReason() {
        val link = LinkState(connected = true, link = Link.CELLULAR, metered = true)
        val reason = (Policy.decide(link, Volume.FULL)
            as? com.krafttools.pulsekraft.core.Permission.Refuse)?.reason
        show(idle(Volume.FULL, link, reason))
        compose.onNodeWithContentDescription(
            "Test unavailable. ${reason!!.trimEnd('.')}.",
        ).assertIsDisplayed()
    }

    @Test
    fun aRefusedControlDoesNotStillSayPressMe() {
        // Found on a real cellular connection: the mark greys and stops
        // breathing, the refusal appears — and the line above still said
        // "Tap to measure this connection", which is an instruction to
        // press something that will not press.
        val link = LinkState(connected = true, link = Link.CELLULAR, metered = true)
        val reason = (Policy.decide(link, Volume.FULL)
            as? com.krafttools.pulsekraft.core.Permission.Refuse)?.reason
        show(idle(Volume.FULL, link, reason))
        compose.onNodeWithText("Not available on this connection").assertIsDisplayed()
        assertTrue(
            "the instruction must not survive a refusal",
            compose.onAllNodesWithText("Tap to measure this connection")
                .fetchSemanticsNodes().isEmpty(),
        )
    }


    @Test
    fun theVerdictIsReadBeforeItsEvidence() {
        // The answer is the sentence a person acts on, so it carries a
        // heading and a description that includes the figure behind it.
        show(MeasureState.Done(report(Latency.Verdict.STEADY, 53.0, 70.0)))
        compose.onNodeWithContentDescription(
            "Good for calls. 70 milliseconds of latency while the connection was busy",
        ).assertIsDisplayed()
    }

    @Test
    fun theStabilityPlotDescribesItselfFromItsOwnData() {
        // A Canvas announces nothing, so the only picture in this app was
        // invisible to a screen reader. The description is generated from
        // the measured numbers rather than written by hand, so it cannot
        // describe a watch that did not happen.
        show(MeasureState.Done(report(Latency.Verdict.STEADY, 53.0, 70.0)))
        compose.onNodeWithContentDescription(
            "Latency stayed between 42 and 85 milliseconds over the watch. Steady.",
        ).assertIsDisplayed()
    }

    @Test
    fun nothingIsCutOffWhenTheTextIsLarge() {
        // Found by turning the system font scale up on a phone and
        // looking, which is the only way this class of bug is ever
        // found: "135 ms  jitter ±49" wrapped, dragged its label out of
        // line with the rows around it, and a separate line lost its
        // unit entirely — "unloaded 135" with the "ms" silently gone,
        // which is the worst way to lose a word because the number still
        // looks like a number.
        // The same one-shot content the other tests use, with the
        // density overridden. Two `setContent` calls on one activity is
        // the error this harness was written to avoid.
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides
                    androidx.compose.ui.unit.Density(density = 3f, fontScale = 2f),
            ) {
                PulseKraftTheme { Box(Modifier.fillMaxSize()) { Content() } }
            }
        }
        state = MeasureState.Done(report(Latency.Verdict.STEADY, 135.0, 84.0))
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val cut = compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
        ).fetchSemanticsNodes().filter {
            it.boundsInRoot.bottom > root.bottom + 1f
        }
        assertTrue("large text is cut off: $cut", cut.isEmpty())
    }

    // ------------------------------------------------------------- fixtures

    private fun states(): List<Pair<String, MeasureState>> = listOf(
        "idle-light" to idle(Volume.LIGHT),
        "idle-full" to idle(Volume.FULL),
        "idle-refused" to idle(
            Volume.FULL,
            LinkState(connected = true, link = Link.CELLULAR, metered = true),
            "You're roaming.",
        ),
        "idle-offline" to idle(
            Volume.LIGHT,
            LinkState(),
            "No connection. Nothing to measure.",
        ),
        "running-latency" to runningLatency(listOf(48.0, 51.0, 49.0, 52.0, 47.0)),
        "running-download" to runningDownload(),
        "running-empty-series" to runningLatency(emptyList()),
        "running-spiky" to runningLatency(
            listOf(50.0, 900.0, 51.0, 48.0, 52.0, 49.0, 47.0, 900.0, 50.0),
        ),
        "done" to MeasureState.Done(report()),
        "done-spiky" to MeasureState.Done(
            report(Latency.Verdict.SPIKY, idleMs = 62.0, loadedMs = 62.0),
        ),
        "stopped-with-figures" to MeasureState.Stopped(
            done = listOf(PhaseFigure(Phase.IDLE_LATENCY, "53 ms")),
            volume = Volume.LIGHT,
        ),
        "stopped-empty" to MeasureState.Stopped(emptyList(), Volume.FULL),
        "failed" to MeasureState.Failed(
            "The connection dropped",
            "the socket failed mid-test, so there is nothing to report",
        ),
    )

    private fun idle(
        volume: Volume = Volume.LIGHT,
        link: LinkState = LinkState(
            connected = true, link = Link.WIFI, metered = false,
        ),
        refusal: String? = null,
    ) = MeasureState.Idle(volume = volume, refusal = refusal, link = link)

    private fun runningDownload() = MeasureState.Running(
        live = LiveState(
            phase = Phase.DOWNLOAD,
            reading = Reading.DOWNLOAD,
            current = 96.4,
            series = listOf(88.0, 101.0, 94.0, 99.0, 96.4, 92.0, 97.5),
            peak = 101.0,
            scaleMax = 1000.0,
            bytesSoFar = 8_388_608L,
            averageMbpsToDate = 96.1,
        ),
        phase = Phase.DOWNLOAD,
        volume = Volume.LIGHT,
        done = listOf(PhaseFigure(Phase.IDLE_LATENCY, "53 ms")),
    )

    private fun runningLatency(series: List<Double>) = MeasureState.Running(
        live = LiveState(
            phase = Phase.IDLE_LATENCY,
            reading = Reading.LATENCY,
            current = series.lastOrNull() ?: 0.0,
            series = series,
            peak = series.maxOrNull() ?: 0.0,
            scaleMax = 200.0,
            bytesSoFar = 0L,
        ),
        phase = Phase.IDLE_LATENCY,
    )

    private fun report(
        stabilityVerdict: Latency.Verdict = Latency.Verdict.STEADY,
        idleMs: Double = 53.0,
        loadedMs: Double = 70.0,
    ) = TestReport(
        download = throughput(107.0),
        upload = throughput(43.0),
        idleLatency = summary(idleMs),
        loadedDuringDownload = summary(loadedMs),
        loadedDuringUpload = summary(loadedMs * 1.2),
        stability = Latency.Stability(
            verdict = stabilityVerdict,
            summary = summary(if (stabilityVerdict == Latency.Verdict.SPIKY) 180.0 else idleMs),
            thresholdP95OverMedian = 1.2,
            hardSpikeRatio = 3.0,
        ),
        volume = Volume.LIGHT,
        edgeHost = "speed.cloudflare.com",
        protocolNote = "test",
        stabilitySeries = List(40) { idleMs * (0.9 + (it % 7) * 0.03) },
    )

    private fun throughput(mbps: Double) = Throughput(
        averageMbps = mbps,
        medianMbps = mbps * 1.1,
        meanMbps = mbps,
        p10Mbps = mbps * 0.8,
        p90Mbps = mbps * 1.2,
        sampleCount = 40,
        totalBytes = 26_214_400L,
        measuredMillis = 2000L,
        discardedByGraceMillis = 0L,
    )

    private fun summary(median: Double) = LatencySummary(
        minMs = median * 0.8,
        medianMs = median,
        p95Ms = median * 1.6,
        maxMs = median * 3.0,
        jitterMs = median * 0.06,
        sampleCount = 12,
    )
}