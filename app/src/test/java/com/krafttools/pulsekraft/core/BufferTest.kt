package com.krafttools.pulsekraft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferTest {

    // ---------------------------------------------------------------- BDP

    @Test
    fun `bandwidth-delay product is bandwidth times round trip over eight`() {
        // 100 Mbps, 20 ms: 100e6 * 0.020 / 8 = 250,000 bytes in flight.
        assertEquals(250_000L, Buffer.bdpBytes(100e6, 20.0))
    }

    @Test
    fun `a longer path needs proportionally more in flight`() {
        // This is why a satellite or intercontinental link needs a much
        // larger window for the same speed, and why the same 64 KB buffer
        // that is ample on a nearby edge is crippling on a long one.
        val near = Buffer.bdpBytes(100e6, 5.0)
        val far = Buffer.bdpBytes(100e6, 200.0)
        assertEquals(40.0, far.toDouble() / near, 0.001)
    }

    @Test
    fun `bdp of nothing is zero`() {
        assertEquals(0L, Buffer.bdpBytes(0.0, 20.0))
        assertEquals(0L, Buffer.bdpBytes(100e6, 0.0))
        assertEquals(0L, Buffer.bdpBytes(-1.0, 20.0))
    }

    // ------------------------------------------------------------- ceiling

    @Test
    fun `a 64KB window on a 200ms path cannot exceed about 2_6 Mbps`() {
        // The number that motivates this entire file. Read as a speed
        // test result it looks like a terrible line; it is a small
        // buffer, and the line may be two orders of magnitude faster.
        assertEquals(2.62144, Buffer.ceilingMbps(65_536, 200.0), 1e-4)
    }

    @Test
    fun `ceiling is window over round trip`() {
        val window = 1_000_000
        val rtt = 50.0
        assertEquals(
            window * 8.0 / (rtt / 1000.0) / 1_000_000.0,
            Buffer.ceilingMbps(window, rtt),
            1e-9,
        )
    }

    @Test
    fun `ceiling of a zero window or zero round trip is zero, not infinity`() {
        assertEquals(0.0, Buffer.ceilingMbps(0, 50.0), 0.0)
        assertEquals(0.0, Buffer.ceilingMbps(65_536, 0.0), 0.0)
    }

    // ------------------------------------------------------------- sizing

    @Test
    fun `the suggested buffer is the product plus headroom`() {
        assertEquals(500_000, Buffer.suggestReceiveBuffer(100e6, 20.0, headroom = 2.0))
    }

    @Test
    fun `the suggested buffer is capped, and the cap is reachable`() {
        // A gigabit link at 200 ms wants 25 MB. Asking for it is pointless
        // — the kernel will not grant it — so the cap binds and the app
        // reports the granted value rather than the requested one.
        val gigabitFar = Buffer.suggestReceiveBuffer(1e9, 200.0)
        assertEquals(Buffer.MAX_REQUEST_BYTES, gigabitFar)
    }

    @Test
    fun `a buffer sized to the product can carry the rate it was sized for`() {
        // The property that has to hold, or the sizing is decoration:
        // for a range of real paths, the suggested window's ceiling is
        // comfortably above the bandwidth the window was derived from.
        for ((mbps, rtt) in listOf(10.0 to 10.0, 100.0 to 20.0, 500.0 to 30.0, 1_000.0 to 10.0)) {
            val window = Buffer.suggestReceiveBuffer(mbps * 1e6, rtt)
            val ceiling = Buffer.ceilingMbps(window, rtt)
            assertTrue(
                "at $mbps Mbps / ${rtt}ms the ${window}B window only " +
                    "carries $ceiling Mbps",
                ceiling >= mbps,
            )
        }
    }

    @Test
    fun `sizing of nothing asks for nothing`() {
        assertEquals(0, Buffer.suggestReceiveBuffer(0.0, 20.0))
        assertEquals(0, Buffer.suggestReceiveBuffer(100e6, 0.0))
    }

    // ------------------------------------------------- the self-check

    @Test
    fun `a result sitting on the ceiling is flagged as possibly our fault`() {
        // The whole point of the check. Reporting 2.5 Mbps when the
        // window could only ever carry 2.62 Mbps means the number may be
        // reporting this app's buffer rather than the user's line, and
        // saying so is better than publishing it unqualified.
        assertTrue(Buffer.mayBeBufferLimited(2.5, 65_536, 200.0))
        assertTrue(Buffer.mayBeBufferLimited(2.62, 65_536, 200.0))
    }

    @Test
    fun `a result well under the ceiling is not flagged`() {
        // 100 Mbps measured where the window could carry 2.6 is
        // impossible; here the window is 4 MB and 100 is well inside it.
        assertFalse(Buffer.mayBeBufferLimited(100.0, 4 * 1024 * 1024, 200.0))
    }

    @Test
    fun `the self-check needs a real window to say anything`() {
        assertFalse(Buffer.mayBeBufferLimited(100.0, 0, 200.0))
        assertFalse(Buffer.mayBeBufferLimited(100.0, 65_536, 0.0))
    }

    // ------------------------------------------------------- report wiring

    @Test
    fun `bufferbloat index is loaded over idle`() {
        val report = report(idle = 20.0, loaded = 60.0)
        assertEquals(3.0, report.bufferbloatIndex!!, 1e-9)
        assertEquals(Bufferbloat.Grade.MODERATE, report.bufferbloatGrade)
    }

    @Test
    fun `no idle baseline means no index, not a ratio of zero`() {
        // A loaded figure with nothing to compare against is a latency,
        // not a bufferbloat diagnosis. Showing 0.00 would be inventing one.
        val report = report(idle = null, loaded = 60.0)
        assertNull(report.bufferbloatIndex)
        assertNull(report.bufferbloatGrade)
    }

    @Test
    fun `a clean line grades as clean`() {
        val report = report(idle = 20.0, loaded = 22.0)
        assertEquals(Bufferbloat.Grade.NONE, report.bufferbloatGrade)
    }

    @Test
    fun `an extreme ratio still grades rather than falling off the end`() {
        assertEquals(Bufferbloat.Grade.SEVERE, Bufferbloat.grade(40.0))
        assertNull(Bufferbloat.index(60.0, 0.0))
    }

    private fun report(idle: Double?, loaded: Double) = TestReport(
        download = null,
        upload = null,
        // Named, not `it`: inside List(5) { ... } the implicit `it` is
        // the element index, so the outer value has to be captured
        // explicitly or every sample becomes 0,1,2,3,4.
        idleLatency = idle?.let { ms -> Latency.summarise(List(5) { ms }) },
        loadedDuringDownload = Latency.summarise(List(5) { loaded }),
        loadedDuringUpload = null,
        stability = null,
        volume = Volume.LIGHT,
        edgeHost = "speed.cloudflare.com",
        protocolNote = Method.AGGREGATION,
    )
}
