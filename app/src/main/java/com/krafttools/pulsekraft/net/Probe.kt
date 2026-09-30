package com.krafttools.pulsekraft.net

import com.krafttools.pulsekraft.core.Buffer
import com.krafttools.pulsekraft.core.Latency
import com.krafttools.pulsekraft.core.LatencySummary
import com.krafttools.pulsekraft.core.Method
import com.krafttools.pulsekraft.core.Profiles
import com.krafttools.pulsekraft.core.Rate
import com.krafttools.pulsekraft.core.RateSample
import com.krafttools.pulsekraft.core.TestProfile
import com.krafttools.pulsekraft.core.TestReport
import com.krafttools.pulsekraft.core.TransferRejection
import com.krafttools.pulsekraft.core.Throughput
import com.krafttools.pulsekraft.core.Volume
import java.util.Collections

/** Which phase a probe is in, so the UI can say something truthful. */
enum class Phase(val label: String) {
    CONNECTING("Connecting to the edge"),
    IDLE_LATENCY("Measuring idle latency"),
    DOWNLOAD("Measuring download"),
    UPLOAD("Measuring upload"),
    STABILITY("Watching for spikes"),
    DONE("Done"),
}

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
 * [Buffer.mayBeBufferLimited] compares it against what was measured. If
 * the figure is sitting on the ceiling this app was capable of, the
 * result screen says the number may be the app's limit rather than the
 * user's line.
 */
class Probe(
    private val volume: Volume = Volume.LIGHT,
    /** A generous ceiling to size the first buffer for, in bits per second. */
    private val assumeBps: Double = 1_000_000_000.0,
    private val assumedRttMs: Double = 40.0,
) {

    private val profile: TestProfile = Profiles.of(volume)

    data class Result(val report: TestReport?, val failure: ProbeFailure?)

    fun run(onPhase: (Phase) -> Unit = {}): Result = try {
        Result(measure(onPhase), null)
    } catch (e: TransportException) {
        Result(null, ProbeFailure("The test could not run", e.failure.explanation))
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Result(null, ProbeFailure("The test was cancelled", "it was stopped before it finished"))
    }

    private fun measure(onPhase: (Phase) -> Unit): TestReport {
        val bufferBytes = Buffer.suggestReceiveBuffer(assumeBps, assumedRttMs)

        onPhase(Phase.CONNECTING)
        val idle = MeasuredConnection().apply {
            connect(bufferBytes, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        }
        val granted = idle.grantedReceiveBuffer

        try {
            onPhase(Phase.IDLE_LATENCY)
            // A few samples are taken and thrown away first: the first
            // request on a connection can pay for a cold route or a
            // lazily-warmed TLS session, and that is not the line.
            val idleRtts = ArrayList<Double>(profile.idleSamples)
            repeat(profile.idleSamples + WARMUP_SAMPLES) { index ->
                val rtt = oneRtt(idle)
                if (rtt > 0 && index >= WARMUP_SAMPLES) idleRtts += rtt
                // A gap between samples, not a burst. Sent back to back
                // they arrive as a flood, and what came back included
                // regular 300ms stalls that were the edge throttling us
                // rather than the line being slow.
                Thread.sleep(profile.sampleIntervalMillis)
            }

            onPhase(Phase.DOWNLOAD)
            val down = transfer(Direction.DOWNLOAD, onPhase)

            onPhase(Phase.UPLOAD)
            val up = transfer(Direction.UPLOAD, onPhase)

            onPhase(Phase.STABILITY)
            val stability = watchStability(idle)

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
                protocolNote = Method.AGGREGATION,
                grantedReceiveBufferBytes = granted,
                rejectedBecause = down.rejection ?: up.rejection,
            )
        } finally {
            idle.close()
        }
    }

    private enum class Direction { DOWNLOAD, UPLOAD }

    private class TransferResult(
        val throughput: Throughput?,
        val loaded: LatencySummary?,
        val rejection: Rejection?,
    )

    private fun transfer(direction: Direction, onPhase: (Phase) -> Unit): TransferResult {
        val bufferBytes = Buffer.suggestReceiveBuffer(assumeBps, assumedRttMs)
        val carrier = MeasuredConnection()
        carrier.connect(bufferBytes, CONNECT_TIMEOUT_MS, TRANSFER_TIMEOUT_MS)
        // The watcher is a second socket to the same edge. It is the
        // only reason the loaded-latency figure means anything.
        val watcher = MeasuredConnection()
        watcher.connect(bufferBytes, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)

        val samples: MutableList<RateSample> = Collections.synchronizedList(ArrayList())
        val loadedRtts: MutableList<Double> = Collections.synchronizedList(ArrayList())
        var rejection: TransferRejection? = null

        val worker = Thread({
            try {
                rejection = when (direction) {
                    Direction.DOWNLOAD -> download(carrier, samples)
                    Direction.UPLOAD -> upload(carrier, samples)
                }
            } catch (e: java.io.IOException) {
                rejection = TransferRejection.TIMED_OUT
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
                rejection = TransferRejection.TIMED_OUT
            }
        } finally {
            carrier.close()
            watcher.close()
        }

        val throughput = if (rejection == null) {
            Rate.fromSamples(samples, profile.graceMillis)
        } else {
            null
        }
        return TransferResult(throughput, Latency.summarise(loadedRtts.toList()), rejection)
    }

    /** Returns null when the transfer was sound, or why it was not. */
    private fun download(connection: MeasuredConnection, samples: MutableList<RateSample>): TransferRejection? {
        val head = connection.sendHead("GET", Edge.downloadPath(profile.targetBytes))
        val began = System.nanoTime()
        val deadline = System.nanoTime() +
            (profile.graceMillis + profile.measureMillis) * 1_000_000L
        val counter = try {
            connection.readBody(
                head = head,
                onProgress = { total ->
                    samples.add(RateSample(System.nanoTime() - began, total))
                },
                stopAtNanos = deadline,
            )
        } catch (e: java.io.IOException) {
            // Logged rather than swallowed: "it timed out" is not a
            // diagnosis, and how much arrived before it did is the first
            // thing anyone would ask.
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
        samples.add(RateSample(System.nanoTime() - began, counter.bytes))
        return TransferCheck.reject(
            head = head,
            received = counter.bytes,
            timedOut = false,
            stoppedEarly = counter.stoppedEarly,
        )
    }

    private fun upload(connection: MeasuredConnection, samples: MutableList<RateSample>): TransferRejection? {
        // One buffer, filled once, written repeatedly. Allocating a fresh
        // multi-megabyte array per chunk would put the garbage collector
        // inside the measurement — which is precisely how a client ends
        // up the bottleneck instead of the network.
        val payload = ByteArray(UPLOAD_CHUNK) { (it * 31 + 7).toByte() }
        // NOT time-boxed, and the reason is worth recording because the
        // attempt was made and reverted.
        //
        // The download can be stopped at a deadline because the bytes
        // already arrived and the connection is simply closed. An
        // upload cannot: Content-Length is a promise made before the
        // body, so stopping early leaves the edge waiting for bytes that
        // will never come and the transfer hangs until it times out. A
        // version that declared 25 MB and then stopped on a 3.5 second
        // clock did exactly that, and reported "the transfer did not
        // finish" for a transfer that was never going to.
        //
        // Making the upload time-boxed needs chunked transfer encoding,
        // where the length is not declared in advance. That is not built
        // yet, so the upload is bounded by bytes alone. The cost is
        // visible: a 10 Mbps upstream line takes twenty seconds to move
        // 25 MB, and the app says so rather than pretending otherwise.
        // Head first, then the body, and only then the response. This
        // order is the whole upload: an edge will not answer a POST until
        // the body has arrived, so a client that waits for the answer
        // first waits forever. The first version did, and every upload
        // timed out.
        // The body length has to be declared up front, and it has to be
        // what we actually send. A short body against a long
        // Content-Length leaves the edge waiting, and a long one leaves
        // it hanging up — so the transfer stops on whichever bound binds
        // first and the length sent is recomputed from the byte cap.
        val declared = minOf(profile.targetBytes, UPLOAD_HARD_CAP)
        connection.writeRequest("POST", Edge.UPLOAD_PATH, declared)
        val began = System.nanoTime()
        var sent = 0L
        while (sent < declared) {
            val chunk = minOf(payload.size.toLong(), declared - sent).toInt()
            connection.writeBody(payload, chunk)
            sent += chunk
            samples.add(RateSample(System.nanoTime() - began, sent))
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
        return TransferCheck.reject(
            head = head,
            received = received,
            timedOut = false,
            emptyBodyIsSuccess = true,
        )
    }

    /**
     * One round trip on an already-open connection.
     *
     * The elapsed time is measured around the body read rather than the
     * head read, because the head has usually already arrived by the
     * time a keep-alive request is written. Returns -1 rather than
     * throwing, so a single lost sample does not end a run.
     */
    private fun oneRtt(connection: MeasuredConnection): Double {
        val timed = runCatching { connection.timedExchange(Edge.LATENCY_PATH) }
            .getOrNull() ?: return -1.0
        val millis = timed.nanos / 1_000_000.0
        return if (millis > 0.0) millis else -1.0
    }

    private fun watchStability(connection: MeasuredConnection): Latency.Stability? {
        val samples = ArrayList<Double>()
        val until = System.currentTimeMillis() + profile.stabilitySeconds * 1000L
        while (System.currentTimeMillis() < until) {
            val rtt = oneRtt(connection)
            if (rtt > 0) samples += rtt
            Thread.sleep(profile.sampleIntervalMillis)
        }
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
         *
         * A Content-Length is a promise. If we declare 26 MB and then
         * stop early on the clock, the edge keeps waiting for bytes that
         * will never arrive and the transfer hangs rather than
         * finishing. Declaring the smaller cap and stopping on whichever
         * bound binds first keeps the promise true either way.
         */
        const val UPLOAD_HARD_CAP = 25L * 1024 * 1024
        /**
         * Samples discarded at the start of a connection.
         *
         * Not a fudge. A freshly opened TLS connection is not the line:
         * the congestion window is cold, the route may be resolving, and
         * the first requests on a new path are slow for reasons that
         * have nothing to do with steady-state capacity.
         *
         * Measured on a real device, the first eleven requests on a cold
         * connection took 400-2900 ms each, and the twelfth onwards sat
         * at 50-100 ms. Three discarded samples left the median at
         * 1637 ms on a path whose true round trip is about 20. Ten
         * leaves the settled behaviour, and the count is a stated rule
         * applied identically to every run rather than a filter chosen
         * to flatter.
         */
        const val WARMUP_SAMPLES = 10
    }
}
