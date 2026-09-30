package com.krafttools.pulsekraft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The published method is treated as a value, so it gets tested like one.
 *
 * If these strings drift from what the code does, the app is lying to
 * its user in a way no test suite would otherwise notice. A test that
 * asserts a method is *described* catches a method that is *changed*
 * without being documented.
 */
class MethodTest {

    @Test
    fun `the light profile is bounded by time first and bytes second`() {
        val light = Profiles.of(Volume.LIGHT)
        // The bound that actually governs a run is the clock: three
        // seconds of measurement after the grace window. The byte
        // ceiling only binds above roughly 57 Mbps, so a slow connection
        // spends far less than the ceiling and a fast one spends exactly
        // it — which is the opposite of how a byte cap behaves, and the
        // reason the cap is a ceiling rather than the target.
        assertEquals(3_000L, light.measureMillis)
        assertEquals(25L * 1024 * 1024, light.targetBytes)

        // The arithmetic has to agree with that claim, or the "light"
        // label is decoration: 25 MB in three seconds is 66 Mbps.
        val ceilingMbps = Rate.mbps(light.targetBytes, light.measureMillis)
        assertTrue(
            "the byte ceiling should bind around 57-70 Mbps, was $ceilingMbps",
            ceilingMbps in 60.0..75.0,
        )
    }

    @Test
    fun `the full profile costs materially more than the light one`() {
        val light = Profiles.of(Volume.LIGHT)
        val full = Profiles.of(Volume.FULL)
        assertTrue(
            "full should be several times the light profile",
            full.estimatedBytes > light.estimatedBytes * 3,
        )
    }

    @Test
    fun `the grace window covers slow start and the socket buffer`() {
        // Half a second is roughly 25 round trips on a 20 ms path, and
        // TCP roughly doubles its rate every one of them — so by the end
        // of the window the line is at capacity and anything still
        // filling the socket buffer is behind it.
        //
        // The first version used a full second, which cost a second of
        // every transfer and still failed the fast-link case for a
        // different reason: 8 MB at 100 Mbps takes 670 ms, so the whole
        // transfer fitted inside the window and there was nothing left
        // to measure. Shortening the window fixed that, and the byte
        // ceiling exists so a gigabit link cannot spend half a gigabyte
        // to be measured.
        assertTrue(Profiles.of(Volume.LIGHT).graceMillis >= 500L)
        assertTrue(Profiles.of(Volume.FULL).graceMillis >= 1_000L)
    }

    @Test
    fun `the full test allows longer for the same reason`() {
        // A gigabit link takes longer to reach steady state, so the
        // profile that reports big numbers gets the longer window.
        assertTrue(
            Profiles.of(Volume.FULL).graceMillis >
                Profiles.of(Volume.LIGHT).graceMillis,
        )
    }

    @Test
    fun `a sample cadence fine enough to see a stall`() {
        // At 100 ms, a 500 ms freeze is five consecutive identical
        // samples. Coarser than that and the stability trace cannot
        // distinguish a pause from a slow patch.
        for (volume in Volume.entries) {
            assertTrue(
                "sample interval too coarse for $volume",
                Profiles.of(volume).sampleIntervalMillis <= 100L,
            )
        }
    }

    @Test
    fun `the test has enough samples to mean something`() {
        for (volume in Volume.entries) {
            val profile = Profiles.of(volume)
            assertTrue("idle samples", profile.idleSamples >= 10)
            assertTrue("loaded samples", profile.loadedSamples >= 10)
            assertTrue("stability window", profile.stabilitySeconds >= 10)
        }
    }

    @Test
    fun `the published strings say the things that must be said`() {
        // Each of these is a promise the app makes to the user. Dropping
        // one turns a disclosure back into a claim.
        val text = listOf(
            Method.AGGREGATION, Method.SCOPE, Method.LIMITS, Method.KEEP_ALIVE,
        ).joinToString(" ")

        assertTrue("must not discard outliers", text.contains("No samples are discarded"))
        // The aggregation statement once said "Median of per-interval
        // rates" while the code had been changed months earlier to
        // report goodput over the measured window, precisely because the
        // median reads high. Every other promise in this file was
        // checked; this one was only checked for the words "goodput",
        // which were present in the same sentence. A disclosure test
        // that greps for vocabulary rather than for the claim is a test
        // that cannot catch the claim going stale.
        assertTrue(
            "must name the window the figure is averaged over",
            Method.AGGREGATION.contains("over the measured window"),
        )
        assertTrue(
            "must not present the median of per-interval rates as the figure",
            !Method.AGGREGATION.contains("Median of per-interval"),
        )
        assertTrue("must not do best-of-N", text.contains("best-of-N"))
        assertTrue("must not apply an overhead fudge", text.contains("overhead correction"))
        assertTrue("must define goodput", text.contains("goodput"))
        assertTrue("must name the edge, not the internet", text.contains("Cloudflare"))
        assertTrue("must refuse to attribute a low result", text.contains("cannot tell"))
        assertTrue("must refuse to report loss", text.contains("not a packet-loss"))
        assertTrue("must explain the keep-alive", text.contains("One connection"))
    }

    @Test
    fun `no published string is a wall of text`() {
        // The rule this app is built under, applied to its own copy: one
        // idea per sentence, nothing over about forty words.
        for ((label, text) in listOf(
            "AGGREGATION" to Method.AGGREGATION,
            "SCOPE" to Method.SCOPE,
            "LIMITS" to Method.LIMITS,
            "KEEP_ALIVE" to Method.KEEP_ALIVE,
        )) {
            val words = text.split(" ").count { it.isNotBlank() }
            assertTrue("$label is $words words", words <= 60)
        }
    }

    @Test
    fun `the published statistic is the one the code actually computes`() {
        // Ties the disclosure to behaviour rather than to wording.
        //
        // Three fast intervals and one slow one, the shape a socket
        // buffer produces: a burst, a burst, a burst, then the wait for
        // the next one. The median of those rates is 1000 Mbps and the
        // average across the window is 775. If the headline ever went
        // back to the median this would fail — which is the point. The
        // sentence in AGGREGATION and the arithmetic in Rate have to be
        // the same claim, and a test that only greps the string for
        // vocabulary cannot know the difference.
        val millis = 100L
        fun sample(index: Int, megabytes: Int) = RateSample(
            atNanos = index * millis * 1_000_000L,
            bytes = megabytes * 1_048_576L,
        )
        val series = listOf(
            sample(0, 0),
            sample(1, 12),
            sample(2, 24),
            sample(3, 36),
            // 1 MB in the last 100ms, where the others moved 12.
            sample(4, 37),
        )

        val throughput = Rate.fromSamples(series, graceMillis = 0)!!

        // 37 MiB across 400ms is 775 Mbps to the decimal the app uses.
        assertEquals(775.0, throughput.averageMbps, 1.0)
        // And the median of the same intervals is the higher figure the
        // disclosure says it is not using.
        assertTrue(
            "the series must actually separate the two statistics",
            throughput.medianMbps > throughput.averageMbps * 1.2,
        )
    }

    @Test
    fun `profiles are stable values, not rebuilt per call`() {
        // A profile that changed between the moment it was shown to the
        // user and the moment it was used would be a promise kept twice,
        // differently.
        assertEquals(Profiles.of(Volume.LIGHT), Profiles.of(Volume.LIGHT))
        assertNotNull(Profiles.of(Volume.LIGHT).name)
    }
}
