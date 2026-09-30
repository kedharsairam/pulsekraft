package com.krafttools.pulsekraft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milliseconds as nanoseconds, so the fixtures below still read in ms. */
private fun ms(value: Long) = value * 1_000_000L

class RateTest {

    // ---------------------------------------------------------------- units

    @Test
    fun `mbps is decimal, not binary`() {
        // 12.5 MB in 1 second is 100 megabits per second exactly, and
        // only if the divisor is a million. Using 1_048_576 here would
        // give 95.4 and quietly make this app disagree with every other.
        assertEquals(100.0, Rate.mbps(12_500_000L, 1_000L), 0.001)
    }

    @Test
    fun `mbps is bits not bytes`() {
        // A common and embarrassing error: reporting bytes per second and
        // calling it Mbps, which understates by exactly eight. A
        // megabyte per second is 8 Mbps — so that is the sample here.
        assertEquals(8.0, Rate.mbps(1_000_000L, 1_000L), 0.001)
    }

    @Test
    fun `mebibits and megabits differ by about five percent`() {
        val decimal = Rate.mbps(1_000_000L, 1_000L)
        val binary = Rate.mebibitsPerSecond(1_000_000L, 1_000L)
        assertEquals(decimal, 8.0, 0.001)
        assertEquals(binary, 8.0 * 1_000_000.0 / 1_048_576.0, 1e-9)
        assertTrue("binary must be the smaller of the two", binary < decimal)
    }

    @Test
    fun `megabytes per second answers the data-plan question`() {
        // 20 MB in 10 seconds is what a data allowance is actually
        // charged against, and it is not the same number as Mbps.
        assertEquals(2.0, Rate.megabytesPerSecond(20_000_000L, 10_000L), 0.001)
    }

    @Test
    fun `a sub-millisecond interval is not truncated to zero`() {
        // 64 KB in 400 microseconds is 1,310 Mbps. Converting that to
        // whole milliseconds first gives 0 ms, and the interval is then
        // reported as 0 Mbps — which is how a real 100 Mbps download
        // came out with a median of 0.0 and a spread of infinity.
        val nanos = 400_000L
        assertEquals(1_310.72, Rate.mbpsFromNanos(65_536L, nanos), 0.01)
        // And the millisecond form must agree when the value is exact.
        assertEquals(
            Rate.mbps(65_536L, 1L),
            Rate.mbpsFromNanos(65_536L, 1_000_000L),
            1e-9,
        )
    }

    @Test
    fun `a zero or negative duration is zero, never a division by zero`() {
        assertEquals(0.0, Rate.mbps(1_000L, 0L), 0.0)
        assertEquals(0.0, Rate.mbpsFromNanos(1_000L, 0L), 0.0)
        assertEquals(0.0, Rate.mbps(1_000L, -5L), 0.0)
        assertEquals(0.0, Rate.mbps(0L, 1_000L), 0.0)
        assertEquals(0.0, Rate.megabytesPerSecond(1_000L, 0L), 0.0)
    }

    // ----------------------------------------------------------- percentiles

    @Test
    fun `percentile interpolates between order statistics`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        assertEquals(3.0, Rate.percentile(values, 0.50), 1e-9)
        // Pinned deliberately: rank 0.4, between 1.0 and 2.0, 40% of the
        // way across. A nearest-rank implementation would say 1.0, and
        // that difference has to be a decision rather than an accident.
        assertEquals(1.4, Rate.percentile(values, 0.10), 1e-9)
        assertEquals(4.6, Rate.percentile(values, 0.90), 1e-9)
    }

    @Test
    fun `percentile does not care about input order`() {
        val sorted = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val shuffled = listOf(4.0, 1.0, 5.0, 3.0, 2.0)
        assertEquals(
            Rate.percentile(sorted, 0.90),
            Rate.percentile(shuffled.sorted(), 0.90),
            1e-9,
        )
    }

    @Test
    fun `percentile survives empty, single and out-of-range input`() {
        assertEquals(0.0, Rate.percentile(emptyList(), 0.5), 0.0)
        assertEquals(7.0, Rate.percentile(listOf(7.0), 0.5), 0.0)
        // A caller passing 1.5 should get the maximum, not an index crash.
        assertEquals(5.0, Rate.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 1.5), 1e-9)
        assertEquals(1.0, Rate.percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), -1.0), 1e-9)
    }

    // -------------------------------------------------------- grace window

    @Test
    fun `the grace window discards slow start and nothing else`() {
        // 0-1000ms crawls (slow start), then the line runs at a steady
        // 100 Mbps. The median must be 100, not something dragged down by
        // the ramp, because slow start is not the line's capacity.
        // bytes = Mbps x 125 x milliseconds.
        val samples = listOf(
            RateSample(ms(0L), 0L),
            RateSample(ms(500L), 1_250_000L),     // 20 Mbps, still ramping
            RateSample(ms(1_000L), 2_500_000L),   // 20 Mbps, still ramping
            RateSample(ms(1_100L), 3_750_000L),   // 100 Mbps
            RateSample(ms(1_200L), 5_000_000L),   // 100 Mbps
        )
        val result = Rate.fromSamples(samples, graceMillis = 1_000L)
        assertNotNull(result)
        assertEquals(100.0, result!!.medianMbps, 0.5)
        assertEquals(1_000L, result.discardedByGraceMillis)
    }

    @Test
    fun `an interval that ends inside the grace window is dropped whole`() {
        // The interval 900->1000 straddles nothing: it ends exactly on
        // the boundary and belongs to the ramp. The one after it counts.
        val samples = listOf(
            RateSample(ms(0L), 0L),
            RateSample(ms(1_000L), 1_000L),       // dropped: starts before grace
            RateSample(ms(1_100L), 1_250_000L),   // 100 Mbps, kept
        )
        val result = Rate.fromSamples(samples, graceMillis = 1_000L)
        assertNotNull(result)
        assertEquals(1, result!!.sampleCount)
    }

    @Test
    fun `nothing is discarded once the grace window has passed`() {
        // The whole point. A single slow interval after the window is a
        // fact about the connection, and dropping it is how a speed test
        // reports a number its user never saw.
        val samples = listOf(
            RateSample(ms(0L), 0L),
            RateSample(ms(1_100L), 13_750_000L),  // 100 Mbps across 1100ms
            RateSample(ms(1_200L), 15_000_000L),  // +1,250,000 in 100ms = 100 Mbps
            RateSample(ms(1_300L), 15_225_000L),  // +   225,000 in 100ms = 18 Mbps, stalled
            RateSample(ms(1_400L), 16_475_000L),  // +1,250,000 in 100ms = 100 Mbps
        )
        val result = Rate.fromSamples(samples, graceMillis = 1_000L)
        assertNotNull(result)
        assertEquals("the stall must remain in the sample set", 3, result!!.sampleCount)
        assertEquals("and the fast intervals must too", 100.0, result.medianMbps, 0.5)
        assertTrue("and it must widen the spread", result.spreadRatio > 2.0)
    }

    @Test
    fun `a test that never leaves the grace window reports nothing`() {
        // Null, not zero. Zero is a claim about the line; null is a
        // statement that the test did not measure one.
        val samples = listOf(RateSample(ms(0L), 0L), RateSample(ms(400L), 1_000L))
        assertNull(Rate.fromSamples(samples, graceMillis = 1_000L))
    }

    @Test
    fun `a test with too few samples reports nothing`() {
        assertNull(Rate.fromSamples(emptyList(), 0L))
        assertNull(Rate.fromSamples(listOf(RateSample(ms(0L), 0L)), 0L))
    }

    @Test
    fun `a stalled clock or a counter that goes backwards is skipped`() {
        // Both happen in the wild: a reader that misses its deadline
        // yields two samples with the same timestamp, and a connection
        // that resets yields a counter below the previous one. Either one
        // divides to nonsense, and one bad sample must not poison the run.
        val samples = listOf(
            RateSample(ms(0L), 0L),
            RateSample(ms(1_000L), 12_500L),
            RateSample(ms(1_000L), 15_000L),   // same instant: no elapsed time
            RateSample(ms(1_100L), 5_000L),    // counter went backwards
            RateSample(ms(1_200L), 28_750L),   // 100 Mbps
        )
        val result = Rate.fromSamples(samples, graceMillis = 0L)
        assertNotNull(result)
        assertEquals(2, result!!.sampleCount)
    }

    @Test
    fun `spread ratio is p90 over p10`() {
        // Four intervals, so the interpolated percentiles land on real
        // observations rather than halfway between two samples.
        val samples = listOf(
            RateSample(ms(0L), 0L),
            RateSample(ms(100L), 1_250_000L),     // 100 Mbps
            RateSample(ms(200L), 11_250_000L),    // 800 Mbps
            RateSample(ms(300L), 12_500_000L),    // 100 Mbps
            RateSample(ms(400L), 22_500_000L),    // 800 Mbps
        )
        val result = Rate.fromSamples(samples, graceMillis = 0L)!!
        assertEquals(100.0, result.p10Mbps, 1e-6)
        assertEquals(800.0, result.p90Mbps, 1e-6)
        assertEquals(8.0, result.spreadRatio, 1e-6)
    }

    @Test
    fun `the average is over the measured window and is the headline`() {
        // The shape a large socket buffer produces: most reads are
        // served instantly from a full buffer, and a few block waiting
        // for the network. That is left-skewed, so the median of the
        // interval rates sits *above* the mean of them — and it is the
        // mean that answers "how fast".
        //
        // On the real device this was the whole difference between 414
        // Mbps reported and 91 Mbps delivered.
        val samples = ArrayList<RateSample>()
        var at = ms(0)
        var bytes = 0L
        samples.add(RateSample(at, bytes))
        // 45 instant reads of 64 KB, each in 100 microseconds.
        repeat(45) {
            at += 100_000L
            bytes += 65_536L
            samples.add(RateSample(at, bytes))
        }
        // 5 blocking reads of 400 KB, each taking 50 milliseconds.
        repeat(5) {
            at += 50_000_000L
            bytes += 400_000L
            samples.add(RateSample(at, bytes))
        }

        val result = Rate.fromSamples(samples, graceMillis = 0L)!!
        // 4,949,120 bytes in 254.5 ms is about 155 Mbps.
        assertEquals(155.6, result.averageMbps, 1.0)
        // The median is the 25th of 50, which is one of the instant
        // reads: 64 KB in 100us is 5,242 Mbps.
        assertTrue(
            "median ${result.medianMbps} should sit above the mean",
            result.medianMbps > result.averageMbps * 10,
        )
    }

    @Test
    fun `overall is the whole-transfer average and is not the headline`() {
        val samples = listOf(
            RateSample(ms(0L), 0L),
            RateSample(ms(1_000L), 12_500_000L),   // 100 Mbps over a second
        )
        val result = Rate.fromSamples(samples, graceMillis = 0L)!!
        assertEquals(100.0, result.overallMbps, 0.5)
    }
}
