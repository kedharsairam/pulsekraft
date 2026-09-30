package com.krafttools.pulsekraft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTest {

    // ---------------------------------------------------- RFC 3550 jitter

    @Test
    fun `a perfectly steady connection has zero jitter`() {
        assertEquals(0.0, Latency.rfc3550Jitter(List(10) { 20.0 }), 1e-9)
    }

    @Test
    fun `jitter follows the RFC 3550 recurrence`() {
        // Worked by hand against J = J + (|D| - J) / 16, with transit
        // time taken as half the round trip.
        //
        //   transit: 5, 5, 10
        //   J0 = 0
        //   i=1: D = 0     -> J = 0 + (0 - 0)/16     = 0
        //   i=2: D = 5     -> J = 0 + (5 - 0)/16     = 0.3125
        assertEquals(0.3125, Latency.rfc3550Jitter(listOf(10.0, 10.0, 20.0)), 1e-9)
    }

    @Test
    fun `jitter rises on a second excursion and decays between them`() {
        // The /16 weighting is what makes this a running mean rather than
        // a plain average, so a single spike does not permanently inflate
        // the figure. Two excursions of the same size must give a
        // different answer from one.
        val oneSpike = Latency.rfc3550Jitter(listOf(20.0, 20.0, 40.0))
        val twoSpikes = Latency.rfc3550Jitter(listOf(20.0, 40.0, 20.0, 40.0))
        assertTrue(
            "a second excursion must raise the estimate",
            twoSpikes > oneSpike,
        )
    }

    @Test
    fun `jitter is direction-agnostic`() {
        // A dip below the baseline is as much a change as a spike above
        // it, because RFC 3550 takes the absolute difference.
        // Same magnitude of change, opposite direction. A dip below the
        // baseline moves a call exactly as much as a spike above it.
        val up = Latency.rfc3550Jitter(listOf(20.0, 40.0))
        val down = Latency.rfc3550Jitter(listOf(20.0, 0.0))
        assertEquals(up, down, 1e-9)
    }

    @Test
    fun `jitter needs at least two samples to mean anything`() {
        assertEquals(0.0, Latency.rfc3550Jitter(emptyList()), 0.0)
        assertEquals(0.0, Latency.rfc3550Jitter(listOf(20.0)), 0.0)
    }

    // --------------------------------------------------------- summarise

    @Test
    fun `summarise reports the distribution, not just its centre`() {
        val samples = listOf(10.0, 12.0, 11.0, 13.0, 100.0)
        val summary = Latency.summarise(samples)!!
        assertEquals(10.0, summary.minMs, 1e-9)
        assertEquals(100.0, summary.maxMs, 1e-9)
        assertEquals(12.0, summary.medianMs, 1e-9)
        // The 100 ms outlier is the whole point of showing a spread: the
        // median barely moves, and a mean would have been dragged to 47.2.
        assertEquals(5, summary.sampleCount)
        assertTrue("the outlier must be visible, not averaged away", summary.p95Ms > 50.0)
    }

    @Test
    fun `summarise of nothing is null`() {
        assertNull(Latency.summarise(emptyList()))
    }

    @Test
    fun `peak ratio is max over median`() {
        val summary = Latency.summarise(listOf(20.0, 20.0, 20.0, 60.0))!!
        assertEquals(3.0, summary.peakRatio, 1e-9)
    }

    // --------------------------------------------------------- stability

    @Test
    fun `a flat line is steady`() {
        val stability = Latency.stability(List(40) { 20.0 + (it % 2) * 0.4 })!!
        assertEquals(Latency.Verdict.STEADY, stability.verdict)
    }

    @Test
    fun `a line that wanders is varied`() {
        // p95 over median of 1.5: mostly 20 ms with a tail at 30 ms.
        val samples = listOf(20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 20.0, 30.0, 30.0)
        val stability = Latency.stability(samples)!!
        assertEquals(Latency.Verdict.VARIED, stability.verdict)
    }

    @Test
    fun `one large spike makes it spiky even when the rest is flat`() {
        // The hard-spike rule exists so a generally quiet line with one
        // disastrous outlier is not averaged into "merely noisy". A
        // single dropped packet every few seconds is exactly what makes
        // an otherwise fine call sound bad.
        val samples = MutableList(30) { 20.0 }
        samples[15] = 400.0
        val stability = Latency.stability(samples)!!
        assertEquals(Latency.Verdict.SPIKY, stability.verdict)
        assertTrue(stability.hardSpikeRatio >= Latency.HARD_SPIKE_RATIO)
    }

    @Test
    fun `a p95 over median above three is spiky without any single spike`() {
        // Majority low, minority high — so the median stays low and the
        // p95 lands in the tail. Half and half would put the median
        // between the clusters and understate the ratio.
        val samples = List(30) { 20.0 } + List(5) { 75.0 }
        val stability = Latency.stability(samples)!!
        assertEquals(Latency.Verdict.SPIKY, stability.verdict)
    }

    @Test
    fun `too few samples to judge returns null rather than a confident word`() {
        // Two samples cannot distinguish a steady line from a spiky one,
        // and a verdict on that basis would be a guess wearing a label.
        assertNull(Latency.stability(listOf(20.0)))
        assertNull(Latency.stability(listOf(20.0, 21.0)))
        assertNull(Latency.stability(emptyList()))
    }

    @Test
    fun `the thresholds are the ones documented`() {
        // If these move, the text on screen that explains the verdict
        // stops matching the app. That is the failure this pins.
        assertEquals(1.5, Latency.VARIED_ABOVE, 1e-9)
        assertEquals(3.0, Latency.SPIKY_ABOVE, 1e-9)
        assertEquals(10.0, Latency.HARD_SPIKE_RATIO, 1e-9)
    }
}
