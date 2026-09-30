package com.krafttools.pulsekraft.core

import kotlin.math.abs

/**
 * One round trip, in milliseconds.
 *
 * Round trip rather than one-way. One-way needs synchronised clocks at
 * both ends, and any clock skew lands directly in the number. A round
 * trip has no such requirement and is what every latency figure in this
 * app actually is.
 */
data class Rtt(val millis: Double)

/**
 * What a set of round trips says about a connection.
 *
 * The median leads, not the mean. A single 400 ms outlier from a
 * retransmission drags a mean far more than it moves a median, and on a
 * consumer connection those outliers are the interesting part — but
 * they should be shown, not allowed to redefine the typical case.
 */
data class LatencySummary(
    val minMs: Double,
    val medianMs: Double,
    val p95Ms: Double,
    val maxMs: Double,
    val jitterMs: Double,
    val sampleCount: Int,
) {
    /** Max over median. The single most useful "is this steady" number. */
    val peakRatio: Double
        get() = if (medianMs > 0.0) maxMs / medianMs else Double.POSITIVE_INFINITY
}

/**
 * Latency, jitter, and whether a connection holds still.
 */
object Latency {

    /**
     * Jitter, by the formula in RFC 3550.
     *
     * Not an ad-hoc standard deviation, and not the mean absolute
     * deviation, both of which are also called jitter and disagree with
     * this one. RFC 3550 defines it for real-time traffic — the jitter
     * that matters for a voice call — as an exponentially weighted
     * running mean of the change in transit delay:
     *
     *     J = J + ( |D(i-1, i)| - J ) / 16
     *
     * The constant 16 is the specification's, not a tuning choice. Using
     * a different definition would make this number quietly
     * incomparable with every other tool that reports jitter honestly.
     */
    fun rfc3550Jitter(roundTripMillis: List<Double>): Double {
        if (roundTripMillis.size < 2) return 0.0
        var jitter = 0.0
        var previousTransit = roundTripMillis[0] / 2.0
        for (i in 1 until roundTripMillis.size) {
            val transit = roundTripMillis[i] / 2.0
            val delta = abs(transit - previousTransit)
            jitter += (delta - jitter) / 16.0
            previousTransit = transit
        }
        return jitter
    }

    /** Null rather than zero when there is nothing to summarise. */
    fun summarise(roundTripMillis: List<Double>): LatencySummary? {
        if (roundTripMillis.isEmpty()) return null
        val sorted = roundTripMillis.sorted()
        return LatencySummary(
            minMs = sorted.first(),
            medianMs = Rate.percentile(sorted, 0.50),
            p95Ms = Rate.percentile(sorted, 0.95),
            maxMs = sorted.last(),
            jitterMs = rfc3550Jitter(roundTripMillis),
            sampleCount = sorted.size,
        )
    }

    /**
     * Whether a connection holds still while you use it.
     *
     * This is the verdict the stability trace exists to support, and it
     * is deliberately coarse: *steady*, *varied* or *spiky*. A person
     * asking "is my WiFi healthy" wants a word, not a second decimal
     * place.
     *
     * The thresholds are **chosen, not derived** — there is no
     * authority for 1.5 and 3.0 — and saying so is part of the method.
     * They are set where the two symptoms diverge: a ratio of 1.5 means
     * the slowest tenth of samples are half again as slow as typical,
     * which nobody notices; 3.0 means the line is three times worse
     * sometimes, which is what makes calls break.
     *
     * The hard spike rule overrides the ratio, because a single 10x
     * outlier is a different failure from a generally noisy line and
     * averaging them together hides it.
     */
    enum class Verdict(val label: String, val meaning: String) {
        STEADY("Steady", "Latency barely varies. Fine for calls and games."),
        VARIED("Varied", "Latency moves around. Calls may pick up the occasional wobble."),
        SPIKY("Spiky", "Latency jumps. Expect dropped audio and stutter."),
    }

    data class Stability(
        val verdict: Verdict,
        val summary: LatencySummary,
        val thresholdP95OverMedian: Double,
        val hardSpikeRatio: Double,
    )

    // Internal rather than private so the test suite can pin them. They
    // are part of the method this app publishes on screen; a test that
    // asserts their values is asserting that the words on the result
    // screen still describe what the app does.
    internal const val VARIED_ABOVE = 1.5
    internal const val SPIKY_ABOVE = 3.0
    internal const val HARD_SPIKE_RATIO = 10.0

    /** Null when there are too few samples to say anything. */
    fun stability(roundTripMillis: List<Double>): Stability? {
        val summary = summarise(roundTripMillis) ?: return null
        if (summary.sampleCount < 3) return null
        val p95Ratio =
            if (summary.medianMs > 0.0) summary.p95Ms / summary.medianMs else Double.POSITIVE_INFINITY
        val verdict = when {
            summary.peakRatio >= HARD_SPIKE_RATIO -> Verdict.SPIKY
            p95Ratio >= SPIKY_ABOVE -> Verdict.SPIKY
            p95Ratio >= VARIED_ABOVE -> Verdict.VARIED
            else -> Verdict.STEADY
        }
        return Stability(
            verdict = verdict,
            summary = summary,
            thresholdP95OverMedian = p95Ratio,
            hardSpikeRatio = summary.peakRatio,
        )
    }
}
