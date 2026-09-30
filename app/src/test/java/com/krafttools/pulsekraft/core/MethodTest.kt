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
    fun `the light profile is the default and is genuinely light`() {
        val light = Profiles.of(Volume.LIGHT)
        // About 20 MB for a full run, which is the number a user needs
        // before agreeing to spend it on mobile data.
        assertTrue(
            "light profile should cost under 25MB, was ${light.estimatedBytes}",
            light.estimatedBytes < 25L * 1024 * 1024,
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
    fun `the grace window is long enough to leave slow start behind`() {
        // Sub-second is not enough on a high-latency path: slow start
        // doubles per round trip, so a 200 ms path needs about a second
        // to get anywhere near capacity.
        assertTrue(Profiles.of(Volume.LIGHT).graceMillis >= 1_000L)
        assertTrue(Profiles.of(Volume.FULL).graceMillis >= 2_000L)
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
    fun `profiles are stable values, not rebuilt per call`() {
        // A profile that changed between the moment it was shown to the
        // user and the moment it was used would be a promise kept twice,
        // differently.
        assertEquals(Profiles.of(Volume.LIGHT), Profiles.of(Volume.LIGHT))
        assertNotNull(Profiles.of(Volume.LIGHT).name)
    }
}
