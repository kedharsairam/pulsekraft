package com.krafttools.pulsekraft.net

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The edge this app measures against, and why.
 *
 * Cloudflare's public speed-test endpoints are plain HTTP, need nothing
 * from us, and are what power their own browser test — which is the
 * whole reason the app has no backend. LibreSpeed needs a server we
 * host; ndt7 needs an M-Lab server. Choosing this one means there is
 * nothing to run, keep alive, or pay for.
 *
 * The obvious objection is that measuring to the nearest CDN edge is not
 * measuring the internet. That is true, and it is why
 * [com.krafttools.pulsekraft.core.Method.SCOPE] says so on the result
 * screen rather than leaving it implied. It is usually the fastest point
 * on a user's route, so the figure is close to a best case — which is a
 * fact worth knowing, not one worth hiding.
 */
object Edge {
    const val HOST = "speed.cloudflare.com"
    const val PORT = 443

    /** A transfer endpoint asking for [bytes] of payload. */
    fun downloadPath(bytes: Long): String = "/__down?bytes=$bytes"

    /** The upload endpoint. The body carries the bytes; there is no query. */
    const val UPLOAD_PATH = "/__up"

    /**
     * The path used for latency samples.
     *
     * One byte, on a connection opened before the test and held
     * throughout. Asking for a megabyte to measure a round trip would be
     * absurd, and opening a fresh connection per sample would measure
     * DNS, TCP and TLS instead of the path — LibreSpeed's maintainer
     * puts it at "at least twice as high if not more" without
     * keep-alive.
     */
    const val LATENCY_PATH = "/__down?bytes=1"
}

/** Why a connection could not be opened or used. */
enum class TransportFailure(val explanation: String) {
    NO_ROUTE("the edge could not be reached from this network"),
    TLS("the secure connection could not be established"),
    REFUSED("the edge refused the connection"),
    TIMED_OUT("the edge did not answer in time"),
}

/**
 * One HTTP/1.1 connection, with the receive buffer set before connect.
 *
 * The ordering in [connect] is the entire reason this class exists:
 *
 * ```
 * val socket = Socket()                    // unconnected
 * socket.receiveBufferSize = requested     // MUST be before connect
 * socket.connect(address, timeout)         // handshake negotiates the window
 * ```
 *
 * A receive window above 64 KB has to be *requested* before the
 * connection is made, because TCP negotiates the window scale option in
 * the handshake. Set it afterwards and the peer has already been told
 * what window to use. Since throughput is bounded by
 * `window x 8 / round_trip`, a request that misses this window reports
 * the app's buffer size while claiming to report the line's.
 *
 * TLS is layered over the connected socket rather than created from a
 * host name, because the alternative opens its own socket and the buffer
 * is then out of our hands.
 */
private const val TAG = "PulseKraftNet"

class MeasuredConnection(
    private val host: String = Edge.HOST,
    private val port: Int = Edge.PORT,
) : AutoCloseable {

    private var raw: Socket? = null
    private var secure: SSLSocket? = null
    private var input: java.io.InputStream? = null
    private var output: java.io.OutputStream? = null

    /**
     * The buffer the kernel actually granted, not the one requested.
     *
     * `SO_RCVBUF` is a hint. The kernel may refuse, and it allocates
     * twice the value requested, so the number that comes back is the
     * only one that means anything — and it is what
     * [com.krafttools.pulsekraft.core.Buffer.mayBeBufferLimited] needs
     * in order to tell the user when a low result is this app's fault.
     */
    var grantedReceiveBuffer: Int = 0
        private set

    val isOpen: Boolean get() = raw?.isConnected == true

    var grantedSendBuffer: Int = 0
        private set

    fun connect(
        requestedReceiveBuffer: Int,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        requestedSendBuffer: Int = 64 * 1024,
    ) {
        val plain = Socket()
        try {
            // Both buffers, and both before connect. See the class
            // comment; this ordering is the whole point.
            plain.receiveBufferSize = requestedReceiveBuffer
            // The send buffer matters for a reason that is easy to miss:
            // the first writes of an upload land in it and never touch
            // the network, so a large send buffer produces a burst of
            // very fast intervals at the start of the transfer that are
            // measuring memcpy rather than the line. Sizing it
            // explicitly, and reporting it, keeps that visible.
            plain.sendBufferSize = requestedSendBuffer
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(host, port), connectTimeoutMs)
            grantedReceiveBuffer = plain.receiveBufferSize
            grantedSendBuffer = plain.sendBufferSize
            android.util.Log.i(
                TAG,
                "connected local=${plain.localSocketAddress} " +
                    "remote=${plain.remoteSocketAddress} " +
                    "rcv requested=$requestedReceiveBuffer " +
                    "granted=$grantedReceiveBuffer | " +
                    "snd requested=$requestedSendBuffer " +
                    "granted=$grantedSendBuffer",
            )
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw TransportException(classify(e), e)
        }

        val tls = try {
            // getDefault() is declared to return SocketFactory, so the
            // narrowing cast is what exposes the SSL methods.
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // Wrapping the socket we already configured, not connecting
            // by name: a fresh connect would negotiate its own window,
            // and the buffer we set before it would be ignored.
            factory.createSocket(plain, host, port, true) as SSLSocket
        } catch (e: IOException) {
            runCatching { plain.close() }
            throw TransportException(TransportFailure.TLS, e)
        }

        try {
            val handshakeStart = System.nanoTime()
            tls.startHandshake()
            android.util.Log.i(
                TAG,
                "tls ${tls.session?.protocol} in " +
                    "${(System.nanoTime() - handshakeStart) / 1_000_000}ms",
            )
        } catch (e: IOException) {
            runCatching { tls.close() }
            throw TransportException(TransportFailure.TLS, e)
        }

        tls.soTimeout = readTimeoutMs
        raw = plain
        secure = tls
        input = tls.getInputStream()
        output = tls.getOutputStream()
    }

    private fun classify(e: IOException): TransportFailure = when (e) {
        is SocketTimeoutException -> TransportFailure.TIMED_OUT
        is ConnectException -> TransportFailure.REFUSED
        is UnknownHostException -> TransportFailure.NO_ROUTE
        else -> TransportFailure.NO_ROUTE
    }

    /**
     * Writes a request. Does not read anything.
     *
     * Splitting this from [readResponseHead] is not tidiness. Doing both
     * in one call deadlocks every upload: the edge will not answer a
     * POST until the body has arrived, so a client that waits for the
     * response before sending the body waits forever. The first version
     * of this did exactly that, and every upload timed out at sixty
     * seconds for no visible reason.
     */
    fun writeRequest(method: String, path: String, contentLength: Long? = null) {
        val builder = StringBuilder()
        builder.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        builder.append("Host: ").append(host).append("\r\n")
        // Not politeness. Without it a proxy or a server that gzips will
        // compress a highly repetitive upload to almost nothing, and the
        // transfer completes instantly at a number that has nothing to
        // do with the line. If a Content-Encoding comes back anyway, the
        // header is right there to be noticed.
        builder.append("Accept-Encoding: identity\r\n")
        builder.append("Connection: keep-alive\r\n")
        if (contentLength != null) {
            builder.append("Content-Type: application/octet-stream\r\n")
            builder.append("Content-Length: ").append(contentLength).append("\r\n")
        }
        builder.append("\r\n")
        val out = output ?: throw TransportException(TransportFailure.NO_ROUTE)
        try {
            out.write(builder.toString().toByteArray(Charsets.US_ASCII))
        } catch (e: IOException) {
            throw TransportException(TransportFailure.REFUSED, e)
        }
    }

    /**
     * Reads the response head, skipping interim 1xx replies.
     *
     * `100 Continue` is a real response the edge may send before the
     * final answer to a large POST, and it is not the answer. Treating
     * it as one yields a 1xx status, which is not a success, and an
     * upload reported as refused after it had in fact been accepted.
     */
    fun readResponseHead(): HttpHead {
        val reader = HeadReader(bodySource())
        while (true) {
            val head = reader.read() ?: throw TransportException(TransportFailure.REFUSED)
            if (head.statusCode in 100..199) continue
            return head
        }
    }

    /** Writes a request and reads its head. For bodies sent in full up front. */
    fun sendHead(method: String, path: String, contentLength: Long? = null): HttpHead {
        writeRequest(method, path, contentLength)
        flush()
        return readResponseHead()
    }

    fun writeBody(buffer: ByteArray, length: Int = buffer.size) {
        val out = output ?: throw TransportException(TransportFailure.NO_ROUTE)
        try {
            out.write(buffer, 0, length)
        } catch (e: IOException) {
            throw TransportException(TransportFailure.REFUSED, e)
        }
    }

    fun flush() {
        try {
            output?.flush()
        } catch (e: IOException) {
            throw TransportException(TransportFailure.REFUSED, e)
        }
    }

    /** A [ByteSource] over the response body, for the readers above. */
    fun bodySource(): ByteSource = object : ByteSource {
        private val stream = input
            ?: throw TransportException(TransportFailure.NO_ROUTE)
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            stream.read(buffer, offset, length)
    }

    /**
     * Reads one response body, timing it, and returning what arrived.
     *
     * The timestamp is taken by the caller's clock around this call. The
     * read returns as soon as a single byte is available, which is what
     * makes the same call usable for time-to-first-byte on a latency
     * probe and for a whole transfer on a throughput probe.
     */
    fun readBody(
        head: HttpHead,
        onProgress: ((Long) -> Unit)? = null,
        stopAtNanos: Long? = null,
    ): BodyCounter = BodyCounter(bodySource(), head, onProgress, stopAtNanos).also {
        it.drain()
    }

    /**
     * One request and its whole response, timed end to end.
     *
     * The timing has to enclose the request *and* the head, not just
     * the body. The head is read inside [sendHead], and on a keep-alive
     * connection it has usually already arrived by the time the request
     * bytes are flushed — so timing only the body measured the time to
     * lift one byte out of a socket buffer that was already full, and
     * reported it as the round trip. That is how the first real run
     * reported an idle latency of 0 ms on a connection to Singapore.
     */
    fun timedExchange(path: String): TimedBody {
        val started = System.nanoTime()
        val head = sendHead("GET", path)
        val afterHead = System.nanoTime()
        val bytes = readBody(head).bytes
        val done = System.nanoTime()
        android.util.Log.i(
            TAG,
            "exchange $path status=${head.statusCode} declared=" +
                "${head.contentLength} chunked=${head.isChunked} " +
                "got=$bytes headMs=${(afterHead - started) / 1_000_000} " +
                "bodyMs=${(done - afterHead) / 1_000_000}",
        )
        return TimedBody(bytes, done - started)
    }

    override fun close() {
        runCatching { secure?.close() }
        runCatching { raw?.close() }
        secure = null
        raw = null
        input = null
        output = null
    }
}

data class TimedBody(val bytes: Long, val nanos: Long) {
    val millis: Long get() = nanos / 1_000_000
}

/** A transport failure with a sentence a person can read. */
class TransportException(
    val failure: TransportFailure,
    cause: Throwable? = null,
) : java.io.IOException("${failure.explanation}", cause)
