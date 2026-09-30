package com.krafttools.pulsekraft.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request head, byte for byte.
 *
 * ## Why this needed extracting
 *
 * `writeRequest` built the head inline into a socket's output stream, so
 * there was no way to test it without a live TLS connection to the edge.
 * The head is the one part of the transport where a mistake produces no
 * error at all: a request missing its terminating blank line, or its
 * `Content-Length`, is one the server *waits* on rather than rejects.
 * Nothing is logged, nothing is thrown, and the transfer simply times out
 * sixty seconds later.
 *
 * That is not hypothetical. This app shipped an upload deadlock once
 * because the client waited for a response before sending the body, and
 * a missing length is the same failure wearing a smaller hat.
 */
class TransportHeadTest {

    @Test
    fun `the head ends with a blank line`() {
        // The one that fails silently. Without this the server waits.
        assertTrue(
            "the head must terminate with a blank line",
            requestHead("example", "GET", "/x").endsWith("\r\n\r\n"),
        )
    }

    @Test
    fun `the request line is correct`() {
        val first = requestHead("example", "GET", "/__down?bytes=10").lineSequence().first()
        assertEquals("GET /__down?bytes=10 HTTP/1.1", first)
    }

    @Test
    fun `a get carries no body headers`() {
        val head = requestHead("example", "GET", "/__down?bytes=0")
        assertFalse("a GET must not declare a body", head.contains("Content-Length"))
        assertFalse("a GET must not declare a type", head.contains("Content-Type"))
    }

    @Test
    fun `a post declares the body length`() {
        // Without this the edge does not know when the body ends, and the
        // upload hangs until the read timeout.
        val head = requestHead("example", "POST", "/__up", contentLength = 26_214_400L)
        assertTrue(head.contains("Content-Length: 26214400"))
        assertTrue(head.contains("Content-Type: application/octet-stream"))
    }

    @Test
    fun `a zero length post still declares it`() {
        // Cloudflare's upload endpoint answers a POST with
        // `Content-Length: 0`, so zero is a real body length here and not
        // an absent one. Distinguishing null from zero is the point.
        val head = requestHead("example", "POST", "/__up", contentLength = 0L)
        assertTrue("zero is a length", head.contains("Content-Length: 0"))
    }

    @Test
    fun `identity encoding is requested`() {
        // Without it a gzipping intermediary compresses a repetitive
        // upload to almost nothing and the transfer completes instantly at
        // a number with nothing to do with the line.
        assertTrue(
            requestHead("example", "POST", "/__up", 100L)
                .contains("Accept-Encoding: identity"),
        )
    }

    @Test
    fun `the connection is kept alive`() {
        // Every latency sample comes from one persistent connection, and
        // a reconnect between samples measures the handshake, not the
        // path. This app's whole latency claim rests on it.
        assertTrue(requestHead("example", "GET", "/x").contains("Connection: keep-alive"))
    }

    @Test
    fun `the host is the one asked for`() {
        assertTrue(requestHead("speed.example", "GET", "/x").contains("Host: speed.example"))
    }

    @Test
    fun `lines are separated by CRLF and never a bare newline`() {
        val head = requestHead("example", "POST", "/__up", 10L)
        // A bare LF is accepted by some servers and rejected by others,
        // which is the worst kind of difference to depend on.
        val withoutCr = head.replace("\r\n", "")
        assertFalse("a bare LF must not appear", withoutCr.contains('\n'))
    }

    @Test
    fun `the head is ascii only`() {
        val head = requestHead("example", "GET", "/__down?bytes=1000")
        assertTrue(
            "a request head must be ascii",
            head.all { it.code in 0..127 },
        )
    }
}