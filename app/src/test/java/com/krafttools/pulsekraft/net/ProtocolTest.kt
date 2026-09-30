package com.krafttools.pulsekraft.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A [ByteSource] over a byte array, so HTTP can be tested without a network. */
private class FakeSource(private val bytes: ByteArray, private val chunkSize: Int = 0) : ByteSource {
    private var at = 0
    var reads = 0
        private set

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (at >= bytes.size) return -1
        // A small chunk size simulates a socket handing over less than
        // was asked for, which real streams do constantly.
        val n = if (chunkSize > 0) minOf(length, chunkSize) else length
        val available = minOf(n, bytes.size - at)
        System.arraycopy(bytes, at, buffer, offset, available)
        at += available
        reads++
        return available
    }
}

private fun wire(text: String) = text.toByteArray(Charsets.US_ASCII)

class ProtocolTest {

    // ------------------------------------------------------------ head

    @Test
    fun `a status line and headers are parsed`() {
        val head = HeadReader(
            FakeSource(wire("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nContent-Type: application/octet-stream\r\n\r\n")),
        ).read()
        assertNotNull(head)
        assertEquals("HTTP/1.1", head!!.version)
        assertEquals(200, head.statusCode)
        assertEquals("OK", head.reason)
        assertEquals("1000", head.header("content-length"))
        assertTrue(head.isSuccess)
    }

    @Test
    fun `header lookup ignores case`() {
        val head = HeadReader(
            FakeSource(wire("HTTP/1.1 200 OK\r\nCoNtEnT-LeNgTh: 42\r\n\r\n")),
        ).read()!!
        assertEquals("42", head.header("CONTENT-LENGTH"))
        assertEquals("42", head.header("Content-Length"))
        assertNull(head.header("Transfer-Encoding"))
    }

    @Test
    fun `a head with no headers still parses`() {
        val head = HeadReader(FakeSource(wire("HTTP/1.1 204 No Content\r\n\r\n"))).read()!!
        assertEquals(204, head.statusCode)
        assertNull(head.contentLength)
        assertFalse(head.isChunked)
    }

    @Test
    fun `a peer that closes mid-head returns null rather than throwing`() {
        // A truncated handshake is a network condition to report, not a
        // programming error to crash a measurement over.
        assertNull(HeadReader(FakeSource(wire("HTTP/1.1 200 OK\r\nCont"))).read())
        assertNull(HeadReader(FakeSource(ByteArray(0))).read())
    }

    @Test
    fun `a status line that is not one returns null`() {
        assertNull(HeadReader(FakeSource(wire("garbage\r\n\r\n"))).read())
    }

    @Test
    fun `chunked is detected among several encodings`() {
        val head = HeadReader(
            FakeSource(wire("HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n")),
        ).read()!!
        assertTrue(head.isChunked)
    }

    // ---------------------------------------------------------- bodies

    @Test
    fun `a content-length body is read to exactly that length`() {
        val body = ByteArray(10_000) { (it % 251).toByte() }
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "10000"))
        val counter = BodyCounter(FakeSource(body), head)
        assertEquals(10_000L, counter.drain())
        assertFalse(counter.truncated)
    }

    @Test
    fun `chunk framing is not counted as payload`() {
        // "HELLO WORLD" is 11 bytes. The chunk headers and CRLFs add 26
        // more that a naive reader would fold into the result — which on
        // a link slow enough to produce small chunks is a few percent
        // of overstatement, landing hardest on the worst connections.
        val raw = "5\r\nHELLO\r\n6\r\n WORLD\r\n0\r\n\r\n"
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Transfer-Encoding" to "chunked"))
        val counter = BodyCounter(FakeSource(wire(raw)), head)
        assertEquals(11L, counter.drain())
    }

    @Test
    fun `a chunk extension is ignored`() {
        val raw = "5;name=value\r\nHELLO\r\n0\r\n\r\n"
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Transfer-Encoding" to "chunked"))
        assertEquals(5L, BodyCounter(FakeSource(wire(raw)), head).drain())
    }

    @Test
    fun `a body cut short is reported as truncated`() {
        // The transfer stopped early. The bytes that did arrive divided
        // by the time they took is a rate the line never sustained, so
        // this must never be quietly reported as a result.
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "1000"))
        val counter = BodyCounter(FakeSource(ByteArray(400)), head)
        assertEquals(400L, counter.drain())
        assertTrue(counter.truncated)
    }

    @Test
    fun `a source that dribbles a few bytes at a time still reads fully`() {
        // Real sockets return short reads constantly. A reader that
        // assumed one read equals one buffer would undercount badly.
        val body = ByteArray(50_000) { 7 }
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "50000"))
        val counter = BodyCounter(FakeSource(body, chunkSize = 137), head)
        assertEquals(50_000L, counter.drain())
    }

    @Test
    fun `a large body is not allocated as one buffer`() {
        // 4 MB through a 64 KB window, to prove the reader streams.
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "4194304"))
        val counter = BodyCounter(FakeSource(ByteArray(4 * 1024 * 1024)), head)
        assertEquals(4L * 1024 * 1024, counter.drain())
    }

    // ------------------------------------------------------- validation

    @Test
    fun `a clean transfer is accepted`() {
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "1000"))
        assertNull(TransferCheck.reject(head, received = 1000, timedOut = false))
    }

    @Test
    fun `an error response is refused rather than measured`() {
        // This is the LibreSpeed upload bug, in one line: a failed
        // request that still let the client report a rate. The bytes in
        // an error page are not throughput and must never become a
        // number on this screen.
        for (code in listOf(400, 403, 404, 413, 429, 500, 503)) {
            val head = HttpHead("HTTP/1.1", code, "Error", mapOf("Content-Length" to "512"))
            val rejection = TransferCheck.reject(head, received = 512, timedOut = false)
            assertEquals(
                "status $code must be refused",
                Rejection.NOT_SUCCESS,
                rejection,
            )
        }
    }

    @Test
    fun `a 413 is refused, which is the case a big upload hits`() {
        val head = HttpHead("HTTP/1.1", 413, "Payload Too Large", mapOf("Content-Length" to "9"))
        assertEquals(Rejection.NOT_SUCCESS, TransferCheck.reject(head, 9, false))
    }

    @Test
    fun `a short body is refused`() {
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "1000"))
        assertEquals(Rejection.TRUNCATED, TransferCheck.reject(head, 999, false))
    }

    @Test
    fun `a zero-length success is refused as a refusal in disguise`() {
        // A data endpoint answering 200 with no body has handed back
        // nothing to divide. Reporting 0 Mbps would be a claim.
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "0"))
        assertEquals(Rejection.TRUNCATED, TransferCheck.reject(head, 0, false))
    }

    @Test
    fun `a body with no declared length and no chunking cannot be timed`() {
        val head = HttpHead("HTTP/1.1", 200, "OK", emptyMap())
        assertEquals(Rejection.UNFRAMED, TransferCheck.reject(head, 4096, false))
    }

    @Test
    fun `a timeout outranks everything else`() {
        val head = HttpHead("HTTP/1.1", 200, "OK", mapOf("Content-Length" to "1000"))
        assertEquals(Rejection.TIMED_OUT, TransferCheck.reject(head, 100, timedOut = true))
    }

    @Test
    fun `no head at all is a connection failure`() {
        assertEquals(Rejection.NO_CONNECTION, TransferCheck.reject(null, 0, false))
    }

    @Test
    fun `every rejection explains itself in a sentence`() {
        // These strings go on the screen. A rejection the user cannot
        // read is a rejection they will not believe.
        for (rejection in Rejection.entries) {
            assertTrue(
                "${rejection.name} has no explanation",
                rejection.explanation.split(" ").count { it.isNotBlank() } >= 5,
            )
        }
    }
}
