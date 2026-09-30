package com.krafttools.pulsekraft

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.krafttools.pulsekraft.core.LinkState
import com.krafttools.pulsekraft.core.Permission
import com.krafttools.pulsekraft.core.Policy
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.net.LiveState
import com.krafttools.pulsekraft.net.Phase
import com.krafttools.pulsekraft.net.Probe
import com.krafttools.pulsekraft.net.Reachability
import com.krafttools.pulsekraft.ui.MeasureState
import com.krafttools.pulsekraft.ui.closeOffPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything the screen remembers, held somewhere that survives a
 * rotation.
 *
 * ## Why this exists
 *
 * Rotating the phone mid-test used to destroy the run. Proven on device:
 * `remember` does not survive a configuration change, so the composable
 * and every value in it were thrown away — including the handle on the
 * worker thread. The measurement kept going with nothing able to reach
 * it, could not be stopped, and when it finished its result was written
 * to a state holder that no longer existed and silently vanished.
 *
 * A `ViewModel` is exactly the scope that matches the problem: it
 * outlives the activity's configuration and is cleared only when the
 * activity is finished for good.
 *
 * ## What it deliberately does not survive
 *
 * Process death. There is nothing here to save, and that is the right
 * answer rather than a compromise — the worker is a thread in this
 * process, so if the process dies the measurement genuinely stopped, and
 * a run that was abandoned by the system is not a run that reported a
 * fault. Persisting a half-finished transfer across process death would
 * mean putting a socket's worth of state in a bundle to resume something
 * that has already lost its connection.
 */
internal class MeasureViewModel(application: Application) : AndroidViewModel(application) {

    var state by mutableStateOf<MeasureState>(MeasureState.Idle(Volume.LIGHT))
        private set

    var methodOpen by mutableStateOf(false)
        private set

    private val reach = Reachability(application)

    /**
     * The worker, or null when nothing is running.
     *
     * Held here rather than in the screen because the screen is what
     * gets destroyed. The stop control and the handle to the run have to
     * be the same lifetime, or the control arrives somewhere the run
     * cannot be reached.
     */
    private var runner: RunningTest? = null

    /** Throttles screen updates; the probe samples far faster than a reader. */
    private var lastFrame = 0L

    private val main = Handler(Looper.getMainLooper())

    /**
     * Re-read the link and update the idle screen's promise.
     *
     * Re-read on returning to rest rather than observed continuously:
     * the two moments that matter are "what did this screen say it would
     * cost" and "is that still true", and a listener firing on every
     * network change costs more than it is worth for a figure that only
     * has to be right when it is read.
     */
    fun refreshLink() {
        viewModelScope.launch {
            val link = withContext(Dispatchers.IO) { reach.current() }
            val idle = state as? MeasureState.Idle ?: return@launch
            if (idle.link != link) {
                state = idle.copy(
                    link = link,
                    // A refusal belongs to the press that produced it.
                    // Carrying it forward after the network changed would
                    // leave a message contradicting the screen it is on.
                    refusal = null,
                )
            }
        }
    }

    /** Choose a profile. Re-prices the button; starts nothing. */
    fun chooseVolume(volume: Volume) {
        val idle = state as? MeasureState.Idle ?: return
        state = MeasureState.Idle(
            volume = volume,
            link = idle.link,
            refusal = (Policy.decide(idle.link, volume) as? Permission.Refuse)?.reason,
        )
    }

    /**
     * The press: read the link, then run or refuse.
     *
     * The read happens here rather than being taken from the value the
     * idle screen was built with. The network can change while someone is
     * looking at a screen that says what the test will cost, and the
     * promise has to be the one that holds.
     */
    fun run(volume: Volume) {
        lastFrame = 0L
        viewModelScope.launch {
            val link = withContext(Dispatchers.IO) { reach.current() }
            when (val decision = Policy.decide(link, volume)) {
                is Permission.Refuse -> {
                    state = MeasureState.Idle(volume, decision.reason, link)
                    return@launch
                }
                // A warning does not stop the run. The cost was on the
                // idle screen in the same words before the button was
                // pressed, so the press IS the agreement; a dialog
                // restating it would be one more tap to say a thing the
                // screen already said.
                else -> {
                    state = MeasureState.Idle(volume, null, link)
                    startRun(volume)
                }
            }
        }
    }

    private fun startRun(volume: Volume) {
        val probe = Probe(volume = volume)
        val thread = Thread({
            val result = probe.run(
                onPhase = { main.hop { onPhase(it, volume) } },
                onLive = { main.hop { onLive(it, volume) } },
            )
            main.hop { onFinish(result, volume) }
        }, "pulsekraft-ui-driver").apply {
            isDaemon = true
            start()
        }
        runner = RunningTest(thread, probe)
    }

    private fun onPhase(phase: Phase, volume: Volume) {
        val previous = state as? MeasureState.Running
        state = MeasureState.Running(
            live = null,
            phase = phase,
            volume = volume,
            done = previous?.closeOffPhase() ?: emptyList(),
        )
    }

    private fun onLive(live: LiveState, volume: Volume) {
        val now = System.currentTimeMillis()
        if (now - lastFrame < FRAME_MS) return
        lastFrame = now
        state = MeasureState.Running(
            live = live,
            phase = live.phase,
            volume = volume,
            done = (state as? MeasureState.Running)?.done ?: emptyList(),
        )
    }

    private fun onFinish(result: Probe.Result, volume: Volume) {
        state = when {
            result.report != null -> MeasureState.Done(result.report)
            result.failure != null -> MeasureState.Failed(
                result.failure.what,
                result.failure.why,
            )
            // A null report and a null failure means the probe was
            // interrupted, which is what cancelling is. Reporting that as
            // a failure would blame the network for something the person
            // did.
            else -> MeasureState.Stopped(
                // The phase in flight is folded in first. Cancelling at
                // the sixteenth second used to discard a finished
                // idle-latency measurement while the screen said nothing
                // had finished.
                done = (state as? MeasureState.Running)?.closeOffPhase() ?: emptyList(),
                volume = volume,
            )
        }
        runner = null
    }

    fun cancel() {
        runner?.cancel()
    }

    /** Back to idle from any terminal state, keeping the chosen profile. */
    fun again() {
        methodOpen = false
        val volume = when (val current = state) {
            is MeasureState.Done -> current.report.volume
            is MeasureState.Stopped -> current.volume
            else -> Volume.LIGHT
        }
        state = MeasureState.Idle(volume)
    }

    fun toggleMethod() {
        methodOpen = !methodOpen
    }

    /**
     * The activity is finished for good.
     *
     * Cancelling here rather than relying on the daemon flag: a thread
     * marked daemon is killed abruptly when the process goes, and a
     * socket left mid-transfer is the sort of thing that shows up later as
     * a connection the edge has not yet reclaimed.
     */
    override fun onCleared() {
        runner?.cancel()
        runner = null
        super.onCleared()
    }

    /**
     * A test in progress, and the only two things anyone can do to it.
     *
     * Both halves matter and neither is sufficient. The probe closes its
     * sockets, which is what unblocks a read; the interrupt is what
     * unblocks a sleep between samples. Calling only one leaves a cancel
     * that works on some phases and hangs on others.
     */
    private class RunningTest(private val thread: Thread, private val probe: Probe) {
        fun cancel() {
            probe.cancel()
            thread.interrupt()
        }
    }

    private companion object {
        /**
         * Ten frames a second. A number that updates faster is not read
         * faster, and a screen that re-renders a hundred times a second
         * is a screen nobody can read.
         */
        const val FRAME_MS = 100L
    }
}

/**
 * Every callback the probe makes arrives on its own thread, and Compose
 * state may only be written from the main one. The hop is not optional
 * and dropping it is a crash rather than a glitch.
 */
private fun Handler.hop(body: () -> Unit) = post(body)