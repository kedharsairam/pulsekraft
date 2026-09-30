package com.krafttools.pulsekraft.core

/**
 * How this app measures, written down.
 *
 * This file exists because the survey literature on internet-speed
 * measurement found that essentially every speed test hides its method.
 * One paper put it plainly: the calculation "varies widely; often this
 * method is not disclosed. Tests may discard some high and/or low
 * results, may use the median or the mean, may take only the highest
 * result and discard the rest." Disclosed on a help page somewhere,
 * never on the screen where the number is shown.
 *
 * A speed test is an instrument, and an instrument whose method is
 * invisible is not an instrument. So the method is a value in the app,
 * printed on the result screen, not a comment in a file. If this changes,
 * the string the user reads changes with it.
 */
object Method {

    /**
     * How the headline throughput number is arrived at.
     *
     * The alternatives were considered and rejected:
     *
     * - **Best of several runs.** Fastest run wins. This measures the
     *   best conditions the network happened to offer, not the line.
     * - **Discard the slowest quarter, keep the rest.** This is what
     *   Ookla does, stated on its own help page: sort by speed, remove
     *   the two fastest and the bottom quarter, average the rest. It is
     *   not dishonest — it is disclosed — but the discarded quarter is
     *   exactly the slow-start and congested part, so the number it
     *   produces is a property of the filter, not of the connection. A
     *   paired-test study found Ookla reporting up to 12% above ndt7 at
     *   200 ms RTT and up to 56% above at 500 ms, and attributed it to
     *   precisely this.
     * - **A fudge factor for protocol overhead.** LibreSpeed ships an
     *   `overheadCompensationFactor`, described as "fine tuned to run
     *   over a typical IPv4 internet connection", and its own docs admit
     *   results come out "slightly too optimistic" elsewhere. A
     *   correction applied silently is a number that is not a
     *   measurement.
     *
     * What we do instead: **discard nothing.** The grace window is
     * excluded because slow start is not capacity — and it is excluded
     * by time, which is a stated rule, not a filter chosen to flatter.
     * Everything after it is kept, and the spread across the transfer
     * (p10 to p90) is shown rather than collapsed into one figure.
     */
    const val AGGREGATION: String =
        "Median of per-interval rates, taken after a fixed grace window. " +
            "No samples are discarded as outliers, no best-of-N is taken, " +
            "and no protocol-overhead correction is applied. The figure is " +
            "application-level goodput: bytes the app received, not bytes " +
            "on the wire."

    /**
     * What the number is a measurement *of*, which is the part most apps
     * get wrong by omission.
     *
     * Borrowed in substance from the ndt7 specification, which is the
     * most honest statement in the field: ndt7 "answers the question of
     * how fast you could pull/push data from your device to a typically
     * nearby, well-provisioned web server... This is not necessarily a
     * measurement of your last mile speed."
     */
    const val SCOPE: String =
        "This measures your connection to Cloudflare's nearest edge. That " +
            "is usually the fastest point on your route, so it is close to " +
            "a best case — not a promise about every site you visit."

    /**
     * What the app cannot tell you.
     *
     * ndt7 distinguishes an app-limited test from a bottleneck-limited
     * one using TCP_INFO counters — BusyTime, RWndLimited, SndBufLimited
     * — which is how it knows whether the network or the client was the
     * limit. Android does not expose TCP_INFO. So on a phone, a slow
     * result cannot be attributed, and this app will not pretend
     * otherwise. It is stated on the result screen rather than left for
     * the user to infer.
     */
    const val LIMITS: String =
        "The app cannot tell whether a low result came from your line or " +
            "from the phone: the kernel counters that would settle it are " +
            "not available on this platform. It is also not a packet-loss " +
            "measurement — loss cannot be observed over HTTP, because TCP " +
            "hides the retransmissions."

    /**
     * Why a connection is kept alive for the whole test.
     *
     * Not an optimisation. LibreSpeed's maintainer reports that without
     * keep-alive "the ping test results will be at least twice as high if
     * not more" — a fresh connection pays DNS, TCP and TLS on every
     * sample, so the app measures handshakes rather than the path. Every
     * latency sample in this app comes from one persistent connection.
     */
    const val KEEP_ALIVE: String =
        "One connection is opened before the test and held for all of it. " +
            "Latency is sampled on that connection, never on a fresh one, " +
            "so the figure is the path's round trip rather than a handshake."
}

/**
 * How much data a test is allowed to spend.
 *
 * A plain choice of two, with nothing on it. Everything that describes
 * what a choice actually *does* lives in [Method.profile], because a
 * profile is a method, not a property of the amount.
 */
enum class Volume {
    /** The default. Sized for a phone on a metered connection. */
    LIGHT,

    /** Opt-in only, and the cost is stated before it starts. */
    FULL,
}

/**
 * What one size of test actually does: how long to ignore, how often to
 * sample, how much to move, and roughly what that will cost.
 */
data class TestProfile(
    val name: String,
    val graceMillis: Long,
    val sampleIntervalMillis: Long,
    /** Hard ceiling on bytes, so a fast link cannot spend a month of data. */
    val targetBytes: Long,
    /**
     * How long to keep measuring after the grace window closes.
     *
     * This, not the byte count, is what makes the profile work on a
     * fast link. The first light profile moved 8 MB, which on a 100 Mbps
     * connection takes 670 ms — less than the one-second grace window —
     * so every sample was correctly discarded as slow start and the app
     * reported no download figure at all. Measuring against a clock
     * instead means a fast link gets a real window and a slow one gets
     * fewer bytes, which is the opposite of what a byte cap does.
     */
    val measureMillis: Long,
    val idleSamples: Int,
    val loadedSamples: Int,
    val stabilitySeconds: Int,
) {
    /** Roughly what a full run will move, for showing the user first. */
    val estimatedBytes: Long
        get() = targetBytes * 2 + targetBytes / 4
}

object Profiles {

    private val LIGHT = TestProfile(
        name = "Light",
        // One second is enough to leave slow start behind on any
        // plausible path while costing little of a short test.
        // Half a second is ample to clear slow start on a 20 ms path,
        // which roughly doubles its rate every round trip, and it leaves
        // more of the transfer inside the measured window.
        graceMillis = 500,
        sampleIntervalMillis = 100,
        // Three seconds of steady state, and a ceiling so that a very
        // fast link cannot turn a light test into a very large one. When
        // the ceiling binds first, the result says how short the window
        // actually was rather than implying three seconds of it.
        measureMillis = 3_000,
        targetBytes = 25L * 1024 * 1024,
        idleSamples = 12,
        loadedSamples = 10,
        stabilitySeconds = 15,
    )

    private val FULL = TestProfile(
        name = "Full",
        // A longer window, because a gigabit link takes longer to reach
        // steady state than a 50 Mbps one and the test should not report
        // a number the link never actually sustained.
        graceMillis = 1_000,
        sampleIntervalMillis = 100,
        measureMillis = 8_000,
        targetBytes = 100L * 1024 * 1024,
        idleSamples = 20,
        loadedSamples = 20,
        stabilitySeconds = 30,
    )

    fun of(volume: Volume): TestProfile = when (volume) {
        Volume.LIGHT -> LIGHT
        Volume.FULL -> FULL
    }
}
