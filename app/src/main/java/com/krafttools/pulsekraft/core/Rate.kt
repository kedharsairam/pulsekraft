package com.krafttools.pulsekraft.core

import kotlin.math.max
import kotlin.math.min

/**
 * A cumulative reading: how many bytes had arrived, and when — in
 * **nanoseconds**, from [System.nanoTime].
 *
 * Cumulative rather than per-interval on purpose. Rate is a derivative,
 * and a derivative of a rounded counter is noisy. Differencing inside
 * the engine means one place decides how a rate is formed, and the
 * samples can be stored exactly as a reader loop produces them.
 *
 * The unit is not a detail. The first upload measurement on a real
 * connection came out at 524.3 Mbps, which is precisely 65,536 bytes
 * moving in one millisecond — the rate of a 64 KB buffer handed to
 * [System.currentTimeMillis], whose resolution is a millisecond. Every
 * sub-millisecond interval was being quantised to that one figure and
 * then reported as a network speed. A clock with a coarser tick than
 * the event cannot measure the event.
 *
 * [System.nanoTime] rather than elapsedRealtime: monotonic, unaffected
 * by the user or the network changing the wall clock mid-test, and
 * ticking at nanosecond resolution on every device this runs on.
 */
data class RateSample(val atNanos: Long, val bytes: Long)

/**
 * A throughput figure, and everything needed to argue with it.
 *
 * [p10Mbps] and [p90Mbps] are here because a single number cannot be
 * trusted on its own. A transfer that ran at 400 Mbps for four seconds
 * and 40 Mbps for one is not "a 320 Mbps connection" — it is a
 * connection that was mostly fast and briefly not, and the second fact
 * is usually the one a person is trying to discover. [spreadRatio] is
 * p90 over p10, a single number for "how uneven was this".
 */
data class Throughput(
    /**
     * Goodput over the measured window. **This is the headline.**
     *
     * Not the median of the per-interval rates, which is what the first
     * version used, and the difference is not academic. With an 8 MB
     * socket buffer the reads come back in bursts — a full buffer hands
     * over 64 KB instantly, then the next read waits for the network —
     * so the interval rates are bimodal and their median sits far above
     * what the line actually delivered. A real transfer on this device
     * produced interval rates from 56 to 901 Mbps with a median of 414,
     * while the whole 26 MB arrived in 2.3 seconds, which is 91 Mbps.
     *
     * Total bytes over total time is the definition of throughput. The
     * spread is still reported, because the *shape* of a transfer is
     * worth knowing; it is just not the answer to "how fast".
     */
    val averageMbps: Double,
    val medianMbps: Double,
    val meanMbps: Double,
    val p10Mbps: Double,
    val p90Mbps: Double,
    val sampleCount: Int,
    val totalBytes: Long,
    val measuredMillis: Long,
    val discardedByGraceMillis: Long,
) {
    /** p90 over p10. 1.0 is perfectly even; 10 is wildly uneven. */
    val spreadRatio: Double
        get() = if (p10Mbps > 0.0) p90Mbps / p10Mbps else Double.POSITIVE_INFINITY

    /** Whole-transfer average, for reference. Not the headline. */
    val overallMbps: Double
        get() = Rate.mbps(totalBytes, measuredMillis)
}

/**
 * Goodput, and how to aggregate it without flattering yourself.
 *
 * "Goodput" is ndt7's word for the payload a client actually received,
 * excluding the framing, TLS, IP and link-layer overhead around it. It
 * is the right unit for a speed test because it is the only one a user
 * can do anything with — a figure inflated by protocol overhead
 * describes the protocol, not the connection.
 */
object Rate {

    /** Decimal megabits per second, from bytes over milliseconds. */
    fun mbps(bytes: Long, millis: Long): Double = mbpsFromNanos(bytes, millis * 1_000_000L)

    /**
     * Decimal megabits per second, from bytes over nanoseconds.
     *
     * The nanosecond form is the one the engine actually uses, and it
     * exists because the millisecond form is not safe to reach by
     * dividing. Converting a 400-microsecond interval to whole
     * milliseconds truncates it to zero, `mbps` rejects a zero duration,
     * and the interval is reported as 0 Mbps — which dragged a real
     * 100 Mbps download down to a median of 0.0 with a p10 of 0 and a
     * spread of infinity. Truncation that only shows up as a plausible
     * looking wrong number is the worst kind.
     */
    fun mbpsFromNanos(bytes: Long, nanos: Long): Double {
        if (bytes <= 0L || nanos <= 0L) return 0.0
        // bytes * 8 bits, over nanos/1e9 seconds, in units of 1e6.
        return bytes.toDouble() * 8.0 * 1_000.0 / nanos.toDouble()
    }

    /**
     * Mebibits per second, for comparison with the odd tool that uses it.
     *
     * Binary and decimal differ by about 5%, which is enough to make two
     * apps disagree while both look correct. Offered so the difference
     * can be seen rather than guessed at, not because it is the headline.
     */
    fun mebibitsPerSecond(bytes: Long, millis: Long): Double {
        if (bytes <= 0L || millis <= 0L) return 0.0
        return bytes.toDouble() * 8.0 / millis.toDouble() * 1000.0 / 1_048_576.0
    }

    /** Megabytes per second, which is what a data plan actually counts. */
    fun megabytesPerSecond(bytes: Long, millis: Long): Double {
        if (millis <= 0L) return 0.0
        return bytes.toDouble() * 1000.0 / millis.toDouble() / 1_000_000.0
    }

    /**
     * Collapse cumulative samples into a [Throughput], or null if there
     * is nothing honest to report.
     *
     * The rule, stated once so it can be tested:
     *
     * 1. Difference consecutive samples into per-interval bit rates.
     * 2. Drop every interval that *begins* before [graceMillis], so that
     *    only intervals lying wholly inside the steady-state window are
     *    counted.
     * 3. Keep everything after, unweighted, and take the median.
     *
     * On (2): the first version of this dropped intervals that *ended*
     * at or before the boundary, which left one interval straddling the
     * edge in the result — and a straddling interval carries slow-start
     * bytes in its numerator and steady-state time in its denominator,
     * so it is a rate the connection never ran at. A test caught it, and
     * the rule is now the stricter one: an interval must start inside
     * the window to count at all.
     *
     * Nothing else is dropped. A stalled interval stays in, because
     * that is precisely the fact a user running this app wants — a
     * connection that briefly fell over is a different connection from
     * one that did not, and filtering the stall out is how a speed test
     * ends up reporting a number the user never experienced.
     *
     * Null rather than zero when the grace window swallowed everything:
     * a test that never reached steady state has no throughput figure,
     * and reporting 0 Mbps would be a claim rather than an absence.
     */
    fun fromSamples(
        samples: List<RateSample>,
        graceMillis: Long,
    ): Throughput? {
        if (samples.size < 2) return null

        val graceNanos = graceMillis * 1_000_000L
        val intervals = ArrayList<Double>(samples.size - 1)
        // Accumulated across the kept intervals only, so the average is
        // taken over exactly the window the grace window defined and
        // not over the transfer that was thrown away.
        var windowBytes = 0L
        var windowNanos = 0L
        for (i in 1 until samples.size) {
            val previous = samples[i - 1]
            val current = samples[i]
            val elapsedNanos = current.atNanos - previous.atNanos
            // A non-advancing clock or a non-advancing counter yields no
            // information. Skipping rather than dividing keeps one bad
            // sample from poisoning the whole run.
            if (elapsedNanos <= 0L || current.bytes < previous.bytes) continue
            if (previous.atNanos < graceNanos) continue
            val delta = current.bytes - previous.bytes
            intervals += mbpsFromNanos(delta, elapsedNanos)
            windowBytes += delta
            windowNanos += elapsedNanos
        }
        if (intervals.isEmpty() || windowNanos <= 0L) return null

        val sorted = intervals.sorted()
        val first = samples.first()
        val last = samples.last()
        val totalBytes = (last.bytes - first.bytes).coerceAtLeast(0L)

        return Throughput(
            averageMbps = mbpsFromNanos(windowBytes, windowNanos),
            medianMbps = percentile(sorted, 0.50),
            meanMbps = sorted.average(),
            p10Mbps = percentile(sorted, 0.10),
            p90Mbps = percentile(sorted, 0.90),
            sampleCount = sorted.size,
            totalBytes = totalBytes,
            measuredMillis = ((last.atNanos - first.atNanos) / 1_000_000L).coerceAtLeast(1L),
            discardedByGraceMillis = graceMillis,
        )
    }

    /**
     * A percentile by linear interpolation between order statistics.
     *
     * The choice is not free — nearest-rank gives a different answer at
     * small sample counts — so it is pinned here and covered by tests
     * rather than left to whichever library was on the classpath. A
     * percentile that moves when a dependency is upgraded is not a
     * measurement.
     */
    fun percentile(sortedAscending: List<Double>, p: Double): Double {
        if (sortedAscending.isEmpty()) return 0.0
        if (sortedAscending.size == 1) return sortedAscending[0]
        val clamped = min(1.0, max(0.0, p))
        val rank = clamped * (sortedAscending.size - 1)
        val lower = kotlin.math.floor(rank).toInt()
        val upper = kotlin.math.ceil(rank).toInt()
        if (lower == upper) return sortedAscending[lower]
        val weight = rank - lower
        return sortedAscending[lower] * (1.0 - weight) + sortedAscending[upper] * weight
    }
}

/**
 * Bufferbloat: what a connection does to your latency while it is busy.
 *
 * The reason this is the app's most important number. A line can
 * advertise 300 Mbps and still be useless for a video call, because
 * the extra speed was bought by queueing packets in a buffer somewhere,
 * and queued packets are packets that arrive late. Throughput measures
 * the pipe; loaded latency measures what the pipe does to you.
 *
 * The index is a ratio, and the ratio is the whole idea: 1.0 means the
 * line added no latency under load, 2.0 means latency doubled the
 * moment it got busy.
 */
object Bufferbloat {

    /** The bands, and where they come from. */
    enum class Grade(val maxRatio: Double, val label: String, val advice: String) {
        NONE(1.25, "Clean", "Latency barely moves when the line is busy."),
        SLIGHT(2.0, "Mild", "Some queueing, usually harmless."),
        MODERATE(4.0, "Noticeable", "Busy latency is climbing. Calls may stutter."),
        SEVERE(8.0, "Severe", "The line is fast and useless at once."),
        ;
    }

    fun grade(ratio: Double): Grade =
        Grade.entries.firstOrNull { ratio <= it.maxRatio } ?: Grade.SEVERE

    /**
     * Loaded latency over idle latency.
     *
     * Null when there is no idle baseline, because a loaded figure with
     * nothing to compare against is not a ratio — it is a latency, and
     * labelling it as bufferbloat would be inventing a diagnosis.
     */
    fun index(loadedMs: Double, idleMs: Double): Double? {
        if (idleMs <= 0.0) return null
        return loadedMs / idleMs
    }
}
