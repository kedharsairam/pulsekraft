package com.krafttools.pulsekraft.net

import com.krafttools.pulsekraft.core.Bufferbloat
import com.krafttools.pulsekraft.core.Latency
import com.krafttools.pulsekraft.core.Profiles
import com.krafttools.pulsekraft.core.TestProfile
import com.krafttools.pulsekraft.core.TestReport
import com.krafttools.pulsekraft.core.Volume
import java.util.Collections
import kotlin.math.abs

/** Which phase a probe is in, so the UI can say something truthful. */
enum class Phase(val label: String) {
    CONNECTING("Connecting to the edge"),
    IDLE_LATENCY("Measuring idle latency"),
    DOWNLOAD("Measuring download"),
    UPLOAD("Measuring upload"),
    STABILITY("Watching for spikes"),
    DONE("Done"),
}

/**
 * Gap between idle-latency samples, in milliseconds.
 *
 * Wider than the profile's 100ms cadence on purpose, and only for this
 * phase. See the call site: at 100ms the edge throttles the probe and
 * the baseline it produces is worse than the line.
 */
private const val IDLE_SPACING_MS = 500L

/** Which way the live instrument is currently reading. */
enum class Reading(val unit: String) {
    LATENCY("ms"),
    DOWNLOAD("Mbps"),
    UPLOAD("Mbps"),
    ;
}

/**
 * What the live instrument should be showing at this instant.
 *
 * Emitted from a background thread, so anything that touches the screen
 * has to hop to the main thread itself. It carries the whole series
 * rather than a delta because a trace that only knows its last point
 * cannot draw a line, and a gauge that only knows its current value
 * cannot show a peak.
 */
data class LiveState(
    val phase: Phase,
    val reading: Reading,
    /** The figure the instrument is pointing at now. */
    val current: Double,
    /** The series behind it, oldest first. */
    val series: List<Double>,
    /** The highest value seen so far in this phase. */
    val peak: Double,
    /**
     * The top of the scale, fixed for the phase.
     *
     * Held constant rather than tracking the peak, because a scale that
     * grows as the values grow makes a rising number look flat and a
     * falling one look dramatic. A scale that moves is a scale that
     * lies.
     */
    val scaleMax: Double,
    /** Bytes moved so far, for the running total. Not on the dial. */
    val bytesSoFar: Long,
    /**
     * Goodput over the whole phase so far, in Mbps. Zero on latency
     * phases.
     *
     * Carried separately from the series on purpose. The series holds
     * per-interval rates, and because a socket buffer arrives all at
     * once their mean sits well above the true sustained rate — 56 to
     * 901 Mbps intervals with a median of 414 is not a connection
     * doing 414. The result screen reports bytes over the measured
     * window, so the figure quoted while the phase runs has to be
     * computed the same way, or the app shows one number live and a
     * different one on the result screen and both are called "the
     * speed".
     */
    val averageMbpsToDate: Double = 0.0,
)

/** What went wrong, in words a person can read. */
data class ProbeFailure(val what: String, val why: String)

/**
 * Runs the test.
 *
 * ## Two connections, on purpose
 *
 * Latency under load cannot be measured on the connection doing the
 * loading. The transfer saturates the link, its own acknowledgements and
 * its return path are busy, and any sample taken on that socket measures
 * the transfer rather than the line. So the throughput phase runs on one
 * connection while a **second** connection to the same edge samples round
 * trips while it happens. Without the second socket there is no loaded
 * latency, only throughput — which is the number every commercial test
 * stops at.
 *
 * ## The buffer is sized for a guess, then checked against the result
 *
 * There is no way to know the line's speed before measuring it, so the
 * connection opens with a buffer sized for a fast link. Once the first
 * transfer has happened the granted buffer is known, and
 * [com.krafttools.pulsekraft.core.Buffer.mayBeBufferLimited] compares it
 * against what was measured. If the figure is sitting on the ceiling this
 * app was capable of, the result screen says so rather than publishing a
 * number the app itself produced.
 */
class Probe(
    private val volume: Volume = Volume.LIGHT,
    private val assumeBps: Double = 1_000_000_000.0,
    private val assumedRttMs: Double = 40.0,
) {

    private val profile: TestProfile = Profiles.of(volume)

    data class Result(val report: TestReport?, val failure: ProbeFailure?)

    private var lastStabilitySeries: List<Double> = emptyList()

    fun run(
        onPhase: (Phase) -> Unit = {},
        onLive: (LiveState) -> Unit = {},
    ): Result = try {
        Result(measure(onPhase, onLive), null)
    } catch (e: TransportException) {
        Result(null, ProbeFailure("The test could not run", e.failure.explanation))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Result(null, ProbeFailure("The test was cancelled", "it was stopped before it finished"))
    }

    private fun measure(onPhase: (Phase) -> Unit, onLive: (LiveState) -> Unit): TestReport {
        emitTo = onLive
        val bufferBytes = com.krafttools.pulsekraft.core.Buffer
            .suggestReceiveBuffer(assumeBps, assumedRttMs)

        onPhase(Phase.CONNECTING)
        val idle = MeasuredConnection().apply {
            connect(bufferBytes, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        }
        val granted = idle.grantedReceiveBuffer

        try {
            onPhase(Phase.IDLE_LATENCY)
            // A few samples are taken and thrown away first, and the
            // reason is measured rather than assumed: on a real device
            // the first eleven requests on a cold TLS connection took
            // 400-2900 ms each and the twelfth onwards sat at 50-100 ms.
            val idleRtts = ArrayList<Double>(profile.idleSamples)
            val live = ArrayList<Double>()
            repeat(profile.idleSamples + WARMUP_SAMPLES) { index ->
                val rtt = oneRtt(idle)
                if (rtt > 0 && index >= WARMUP_SAMPLES) {
                    idleRtts += rtt
                    live += rtt
                    emit(
                        Phase.IDLE_LATENCY, Reading.LATENCY, rtt, live,
                        live.max(), LIVE_LATENCY_SCALE, 0L,
                    )
                }
                // A gap between samples, not a burst, and for the idle
                // baseline a wider one than the rest of the test uses.
                //
                // The published interval is 100ms and it is right for
                // the stability watch, where temporal resolution is the
                // whole point. It was wrong here. Twenty-two requests
                // 100ms apart is a flood, and it came back with regular
                // 300ms stalls that were the edge throttling us rather
                // than the path being slow — which is how a run reported
                // an unloaded median of 229ms against 76ms measured
                // under full download load, and a bufferbloat index of
                // 0.33x. A baseline worse than the load is not a
                // measurement of the line; it is a measurement of how
                // hard the probe was leaning on it.
                //
                // 500ms is long enough that consecutive samples are
                // independent and short enough that twenty-two of them
                // add eleven seconds, which a person will spend waiting.
                Thread.sleep(maxOf(profile.sampleIntervalMillis, IDLE_SPACING_MS))
            }

            onPhase(Phase.DOWNLOAD)
            val down = transfer(Reading.DOWNLOAD, onPhase, onLive)

            onPhase(Phase.UPLOAD)
            val up = transfer(Reading.UPLOAD, onPhase, onLive)

            onPhase(Phase.STABILITY)
            val stability = watchStability(idle, onLive)

            onPhase(Phase.DONE)
            return TestReport(
                download = down.throughput,
                upload = up.throughput,
                idleLatency = Latency.summarise(idleRtts),
                loadedDuringDownload = down.loaded,
                loadedDuringUpload = up.loaded,
                stability = stability,
                volume = volume,
                edgeHost = Edge.HOST,
                protocolNote = com.krafttools.pulsekraft.core.Method.AGGREGATION,
                grantedReceiveBufferBytes = granted,
                rejectedBecause = down.rejection ?: up.rejection,
                stabilitySeries = lastStabilitySeries,
            )
        } finally {
            idle.close()
        }
    }

    private var emitTo: (LiveState) -> Unit = {}

    private class TransferResult(
        val throughput: com.krafttools.pulsekraft.core.Throughput?,
        val loaded: com.krafttools.pulsekraft.core.LatencySummary?,
        val rejection: com.krafttools.pulsekraft.core.TransferRejection?,
    )

    private fun emit(
        phase: Phase,
        reading: Reading,
        current: Double,
        series: List<Double>,
        peak: Double,
        scaleMax: Double,
        bytes: Long,
        averageMbpsToDate: Double = 0.0,
    ) {
        runCatching {
            emitTo(
                LiveState(
                    phase, reading, current, ArrayList(series), peak,
                    scaleMax, bytes, averageMbpsToDate,
                ),
            )
        }
    }

    private fun transfer(reading: Reading, onPhase: (Phase) -> Unit, onLive: (LiveState) -> Unit): TransferResult {
        val bufferBytes = com.krafttools.pulsekraft.core.Buffer
            .suggestReceiveBuffer(assumeBps, assumedRttMs)
        val carrier = MeasuredConnection()
        carrier.connect(bufferBytes, CONNECT_TIMEOUT_MS, TRANSFER_TIMEOUT_MS)
        // The watcher is a second socket to the same edge. It is the
        // only reason the loaded-latency figure means anything.
        val watcher = MeasuredConnection()
        watcher.connect(bufferBytes, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)

        val samples: MutableList<com.krafttools.pulsekraft.core.RateSample> =
            Collections.synchronizedList(ArrayList())
        val loadedRtts: MutableList<Double> = Collections.synchronizedList(ArrayList())
        val series = Collections.synchronizedList(ArrayList<Double>())
        val phase = if (reading == Reading.DOWNLOAD) Phase.DOWNLOAD else Phase.UPLOAD
        var rejection: com.krafttools.pulsekraft.core.TransferRejection? = null

        val worker = Thread({
            try {
                rejection = when (reading) {
                    Reading.DOWNLOAD -> download(carrier, samples, series, phase)
                    else -> upload(carrier, samples, series, phase)
                }
            } catch (e: java.io.IOException) {
                rejection = com.krafttools.pulsekraft.core.TransferRejection.TIMED_OUT
            }
        }, "pulsekraft-transfer")

        try {
            val began = System.currentTimeMillis()
            worker.start()
            while (worker.isAlive) {
                val rtt = oneRtt(watcher)
                if (rtt > 0) loadedRtts += rtt
                if (System.currentTimeMillis() - began > TRANSFER_CEILING_MS) break
                Thread.sleep(profile.sampleIntervalMillis)
            }
            worker.join(JOIN_TIMEOUT_MS)
            if (worker.isAlive) {
                worker.interrupt()
                rejection = com.krafttools.pulsekraft.core.TransferRejection.TIMED_OUT
            }
        } finally {
            carrier.close()
            watcher.close()
        }

        val throughput = if (rejection == null) {
            com.krafttools.pulsekraft.core.Rate.fromSamples(samples, profile.graceMillis)
        } else {
            null
        }
        return TransferResult(
            throughput,
            com.krafttools.pulsekraft.core.Latency.summarise(loadedRtts.toList()),
            rejection,
        )
    }

    /**
     * Feeds the live instrument while a transfer runs.
     *
     * The instantaneous rate is the difference between the last two
     * cumulative readings. That is the right thing to point a needle at
     * and the wrong thing to report as a result, which is why the
     * headline is the average over the window and this only ever moves
     * the dial.
     */
    private fun publish(
        phase: Phase,
        reading: Reading,
        samples: List<com.krafttools.pulsekraft.core.RateSample>,
        series: MutableList<Double>,
    ) {
        if (samples.size < 2) return
        val last = samples[samples.size - 1]
        val previous = samples[samples.size - 2]
        val nanos = last.atNanos - previous.atNanos
        if (nanos <= 0L || last.bytes < previous.bytes) return
        val instant = com.krafttools.pulsekraft.core.Rate.mbpsFromNanos(
            last.bytes - previous.bytes, nanos,
        )
        if (instant <= 0.0) return
        series += instant
        // Goodput across every sample taken so far, not the last
        // interval's rate. Same arithmetic as the reported figure, so
        // the number on screen does not change when the phase ends.
        val windowNanos = last.atNanos - samples.first().atNanos
        val windowBytes = last.bytes - samples.first().bytes
        val toDate = if (windowNanos > 0L && windowBytes >= 0L) {
            com.krafttools.pulsekraft.core.Rate.mbpsFromNanos(windowBytes, windowNanos)
        } else {
            0.0
        }
        emit(
            phase, reading, instant, series, series.max(),
            LIVE_THROUGHPUT_SCALE, last.bytes, toDate,
        )
    }

    private fun download(
        connection: MeasuredConnection,
        samples: MutableList<com.krafttools.pulsekraft.core.RateSample>,
        series: MutableList<Double>,
        phase: Phase,
    ): com.krafttools.pulsekraft.core.TransferRejection? {
        val head = connection.sendHead("GET", Edge.downloadPath(profile.targetBytes))
        val began = System.nanoTime()
        val deadline = System.nanoTime() +
            (profile.graceMillis + profile.measureMillis) * 1_000_000L
        val counter = try {
            connection.readBody(
                head = head,
                onProgress = { total ->
                    samples.add(
                        com.krafttools.pulsekraft.core.RateSample(
                            System.nanoTime() - began, total,
                        ),
                    )
                    publish(phase, Reading.DOWNLOAD, samples, series)
                },
                stopAtNanos = deadline,
            )
        } catch (e: java.io.IOException) {
            android.util.Log.e(
                "PulseKraftProbe",
                "download failed after ${(System.nanoTime() - began) / 1_000_000}ms " +
                    "with ${samples.lastOrNull()?.bytes ?: 0} of " +
                    "${profile.targetBytes} bytes: ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
            throw e
        }
        val elapsed = (System.nanoTime() - began) / 1_000_000L
        android.util.Log.i(
            "PulseKraftProbe",
            "download ${counter.bytes} bytes in ${elapsed}ms, " +
                "${samples.size} samples, stoppedEarly=${counter.stoppedEarly}",
        )
        samples.add(
            com.krafttools.pulsekraft.core.RateSample(
                System.nanoTime() - began, counter.bytes,
            ),
        )
        return com.krafttools.pulsekraft.net.TransferCheck.reject(
            head = head,
            received = counter.bytes,
            timedOut = false,
            stoppedEarly = counter.stoppedEarly,
        )
    }

    private fun upload(
        connection: MeasuredConnection,
        samples: MutableList<com.krafttools.pulsekraft.core.RateSample>,
        series: MutableList<Double>,
        phase: Phase,
    ): com.krafttools.pulsekraft.core.TransferRejection? {
        // One buffer, filled once, written repeatedly. Allocating a fresh
        // multi-megabyte array per chunk would put the garbage collector
        // inside the measurement — which is precisely how a client ends
        // up the bottleneck instead of the network.
        val payload = ByteArray(UPLOAD_CHUNK) { (it * 31 + 7).toByte() }
        // NOT time-boxed, and the reason is worth recording because the
        // attempt was made and reverted.
        //
        // The download can be stopped at a deadline: the bytes already
        // arrived and the connection is simply closed. An upload cannot.
        // Content-Length is a promise made before the body, so stopping
        // early leaves the edge waiting for bytes that never come and the
        // transfer hangs. A version that declared 25 MB and then stopped
        // on a 3.5 second clock did exactly that.
        //
        // Time-boxing the upload needs chunked transfer encoding, where
        // the length is not declared in advance. Not built yet, so the
        // upload is bounded by bytes alone, and the cost is visible.
        val declared = minOf(profile.targetBytes, UPLOAD_HARD_CAP)
        connection.writeRequest("POST", Edge.UPLOAD_PATH, declared)
        val began = System.nanoTime()
        var sent = 0L
        while (sent < declared) {
            val chunk = minOf(payload.size.toLong(), declared - sent).toInt()
            connection.writeBody(payload, chunk)
            sent += chunk
            samples.add(
                com.krafttools.pulsekraft.core.RateSample(
                    System.nanoTime() - began, sent,
                ),
            )
            publish(phase, Reading.UPLOAD, samples, series)
        }
        connection.flush()
        val head = connection.readResponseHead()
        // The edge acknowledges a completed upload with an empty 200,
        // and that is the success case, not a truncated one. Verified
        // against the live endpoint rather than assumed.
        val received = connection.readBody(head).bytes
        android.util.Log.i(
            "PulseKraftProbe",
            "upload $sent bytes in ${(System.nanoTime() - began) / 1_000_000}ms, " +
                "status=${head.statusCode}",
        )
        return com.krafttools.pulsekraft.net.TransferCheck.reject(
            head = head,
            received = received,
            timedOut = false,
            emptyBodyIsSuccess = true,
        )
    }

    /**
     * One round trip on an already-open connection.
     *
     * The elapsed time is measured around the whole exchange, not just
     * the body. The head is read as part of sending, and on a keep-alive
     * connection it has usually already arrived by the time the request
     * bytes are flushed — so timing only the body measured the time to
     * lift one byte out of an already-full socket buffer and reported it
     * as the round trip. That is how the first real run reported an idle
     * latency of 0 ms on a connection to Singapore.
     *
     * Returns -1 rather than throwing, so one lost sample does not end a
     * run.
     */
    private fun oneRtt(connection: MeasuredConnection): Double {
        val timed = runCatching { connection.timedExchange(Edge.LATENCY_PATH) }
            .getOrNull() ?: return -1.0
        val millis = timed.nanos / 1_000_000.0
        return if (millis > 0.0) millis else -1.0
    }

    private fun watchStability(connection: MeasuredConnection, onLive: (LiveState) -> Unit): Latency.Stability? {
        val samples = ArrayList<Double>()
        val series = ArrayList<Double>()
        // Bounded. A stability watch that runs for a fixed number of
        // seconds cannot grow without limit, but the list is what
        // travels to the screen afterwards, and that one does.
        val until = System.currentTimeMillis() + profile.stabilitySeconds * 1000L
        while (System.currentTimeMillis() < until) {
            val rtt = oneRtt(connection)
            if (rtt > 0) {
                samples += rtt
                series += rtt
                emit(
                    Phase.STABILITY, Reading.LATENCY, rtt, series,
                    series.max(), LIVE_LATENCY_SCALE, 0L,
                )
            }
            Thread.sleep(profile.sampleIntervalMillis)
        }
        lastStabilitySeries = series.toList()
        return Latency.stability(samples)
    }

    private companion object {
        // Int for the three that reach Socket, whose timeouts are Int,
        // and Long for the two that reach Thread and System, whose are
        // not. Declared that way rather than converted at the call site
        // so the type follows the API instead of the compiler.
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 5_000
        const val TRANSFER_TIMEOUT_MS = 60_000
        const val JOIN_TIMEOUT_MS = 5_000L
        const val TRANSFER_CEILING_MS = 120_000L
        const val UPLOAD_CHUNK = 64 * 1024

        /**
         * What the edge will accept in one POST, and the number the
         * request declares up front.
         */
        const val UPLOAD_HARD_CAP = 25L * 1024 * 1024

        /**
         * Samples discarded at the start of a connection.
         *
         * Not a fudge. A freshly opened TLS connection is not the line:
         * the congestion window is cold, the route may be resolving, and
         * the first requests on a new path are slow for reasons that
         * have nothing to do with steady-state capacity. Ten leaves the
         * settled behaviour, and the count is a stated rule applied
         * identically to every run rather than a filter chosen to
         * flatter.
         */
        const val WARMUP_SAMPLES = 10

        /**
         * The top of the live scale, in Mbps, fixed for the whole run.
         *
         * Deliberately fixed, because a scale that adapts to the values
         * makes a rising number look flat and a falling one look
         * dramatic, and an instrument whose scale moves under the
         * needle is lying about the reading.
         *
         * The dial plots this logarithmically, so three decades fit
         * legibly: a 5 Mbps line sits near the bottom, a 100 Mbps line
         * near the middle, and a 1 Gbps line near the top. On a linear
         * dial the first would be at one percent and invisible, and a
         * measured peak of 1956 Mbps would sit off the end pretending
         * to be 600.
         */
        const val LIVE_THROUGHPUT_SCALE = 2_000.0

        /** And in ms, on the same reasoning. */
        const val LIVE_LATENCY_SCALE = 200.0
    }
}
