package com.krafttools.pulsekraft.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The edge's contract, against the real edge.
 *
 * ## What this is, and what it deliberately is not
 *
 * I previously wrote that the edge "cannot be tested honestly from here"
 * because its real behaviour and latency vary. That was too broad, and
 * it hid a real gap.
 *
 * A performance figure cannot be asserted — nobody knows what a phone on
 * someone else's network will measure, and a test that asserted a number
 * would be a test that fails for reasons that have nothing to do with
 * the code. That is the part I meant, and it is real.
 *
 * But the *shape* of the edge can be asserted, and this app's method is
 * built entirely on that shape:
 *
 *  - `__down?bytes=N` answers 200 with `Content-Length: N` and exactly N
 *    bytes, unchunked. Every measured figure depends on the body being
 *    exactly the size that was asked for — a chunked or short body would
 *    make the byte count, and therefore the goodput, a different
 *    quantity.
 *  - `__up` answers 200 with `Content-Length: 0`, and that empty reply
 *    is the success. Reading it as a failed transfer would report every
 *    upload as refused.
 *  - A large POST draws a `100 Continue` first, which is not the answer.
 *
 * If Cloudflare changed any of that, this app would report the wrong
 * thing or report nothing, and **nothing else in the suite would notice
 ***. That is what this file is for.
 *
 * ## Why it skips rather than fails when there is no network
 *
 * A build machine without a route should not go red for it. The suite
 * skips, and the absence of the assertion is stated rather than hidden.
 */
class EdgeContractTest {

    private var connection: MeasuredConnection? = null

    @Before
    fun connect() {
        try {
            val probe = MeasuredConnection()
            probe.connect(
                requestedReceiveBuffer = 4 * 1024 * 1024,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 15_000,
            )
            connection = probe
        } catch (e: Exception) {
            // No route, DNS failure, captive portal, aeroplane mode. Any
            // of those and the contract is simply not checkable here.
            assumeNoException("the edge is not reachable from this machine", e)
        }
    }

    @After
    fun close() {
        connection?.close()
    }

    private fun live(): MeasuredConnection = connection!!

    // ---------------------------------------------------------------- download

    @Test
    fun `a download returns exactly the bytes it was asked for`() {
        val asked = 512 * 1024L
        live().writeRequest("GET", "/__down?bytes=$asked")
        val head = live().readResponseHead()

        assertEquals(
            "the edge did not answer a download with 200",
            200,
            head.statusCode,
        )
        assertEquals(
            "the body was not the size that was asked for",
            asked,
            head.contentLength,
        )
        assertTrue(
            "the download came back chunked, which changes what the byte count means",
            !head.isChunked,
        )
        assertNull(
            "a well-formed download was rejected: ${TransferCheck.reject(head, asked, false)}",
            TransferCheck.reject(head, asked, false),
        )
    }

    @Test
    fun `a small download is not chunked either`() {
        // The edge's shape at both ends of the range, because a server
        // that chunks only small responses would pass the test above.
        val asked = 1_024L
        live().writeRequest("GET", "/__down?bytes=$asked")
        val head = live().readResponseHead()
        assertEquals(200, head.statusCode)
        assertEquals(asked, head.contentLength)
        assertTrue("a 1KB body arrived chunked", !head.isChunked)
    }

    // ------------------------------------------------------------------ upload

    @Test
    fun `an upload is answered with an empty success, not a refusal`() {
        // This is the shape the app was verified against by hand before
        // any test existed, and it is exactly the kind of thing that
        // changes without anyone noticing.
        live().writeRequest("POST", "/__up", contentLength = 0L)
        val head = live().readResponseHead()

        assertEquals("an empty upload was not accepted", 200, head.statusCode)
        assertEquals(
            "the upload's reply is not the empty one the app assumes",
            0L,
            head.contentLength,
        )
        // And the check that matters: the empty body is a success, which
        // is why the transfer path passes this flag.
        assertNull(
            "an empty successful reply was read as a failed transfer",
            TransferCheck.reject(head, 0, false, emptyBodyIsSuccess = true),
        )
    }

    @Test
    fun `a large upload draws an interim reply that is not the answer`() {
        // A 1xx is a real reply the edge sends before a large POST's
        // final one. Treating it as the answer yields a 1xx status,
        // which is not a success, and an upload reported as refused
        // after it had in fact been accepted.
        val body = ByteArray(1 shl 20) { (it % 251).toByte() }
        // The body goes out before the answer is read, because the edge
        // will not answer a POST until the body has arrived. Reading the
        // answer first is the deadlock this app shipped once.
        live().writeRequest("POST", "/__up", contentLength = body.size.toLong())
        live().writeBody(body)

        val head = live().readResponseHead()
        assertEquals(
            "the interim 1xx was treated as the final answer",
            200,
            head.statusCode,
        )
        assertNull(
            "a large accepted upload was read as a failure",
            TransferCheck.reject(head, body.size.toLong(), false, emptyBodyIsSuccess = true),
        )
    }

    // ------------------------------------------------------------- the buffer

    @Test
    fun `the receive buffer the edge granted is a real one`() {
        // The number the result screen's self-check is computed from. If
        // the kernel silently refused the request, the app would be
        // checking its results against a figure it never had.
        assertTrue(
            "the granted receive buffer was not read back",
            live().grantedReceiveBuffer > 0,
        )
    }

    @Test
    fun `a rejection names a reason rather than failing silently`() {
        // The path a broken edge would take. A null rejection with a
        // non-200 would be the worst outcome: a figure published for a
        // transfer that never happened.
        val head = HttpHead(
            version = "HTTP/1.1",
            statusCode = 403,
            reason = "Forbidden",
            headers = mapOf("content-length" to "17"),
        )
        assertEquals(
            Rejection.NOT_SUCCESS,
            TransferCheck.reject(head, 17, false),
        )
        assertNotNull(TransferCheck.reject(head, 17, false))
    }
}
