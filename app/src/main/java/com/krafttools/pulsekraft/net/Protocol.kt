package com.krafttools.pulsekraft.net

/**
 * A source of bytes, so the protocol layer can be tested without a
 * network.
 *
 * Deliberately the shape of [java.io.InputStream] and nothing more. The
 * reader, the header parser and the body counter all sit on this, which
 * means the parts of an HTTP response that are easy to get wrong — a
 * status line, a chunk boundary, a body that is shorter than its
 * declared length — can be exercised from a unit test with a byte array
 * and a cursor.
 */
interface ByteSource {
    /** Reads up to [length] bytes, or returns -1 at end of stream. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** A parsed status line and header block. */
data class HttpHead(
    val version: String,
    val statusCode: Int,
    val reason: String,
    val headers: Map<String, String>,
) {
    /** Headers are case-insensitive; this is how you actually read one. */
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * Declared body length, or null when the body is framed another way.
     *
     * Null is not the same as zero. A chunked body has no length here,
     * and a response with no body at all legitimately has a length of
     * zero. Conflating them would make "the server sent nothing" and
     * "the server framed it differently" look identical, which is how a
     * measurement ends up dividing by the wrong thing.
     */
    val contentLength: Long?
        get() = header("Content-Length")?.trim()?.toLongOrNull()

    val isChunked: Boolean
        get() = header("Transfer-Encoding")
            ?.split(',')
            ?.any { it.trim().equals("chunked", ignoreCase = true) } == true

    val isSuccess: Boolean
        get() = statusCode in 200..299
}

/**
 * The one definition of a rejected transfer lives in core, next to the
 * report that carries it. This is a re-export so the protocol layer can
 * name it without the two drifting apart.
 */
typealias Rejection = com.krafttools.pulsekraft.core.TransferRejection

/**
 * Reads a body, decoding chunked framing, and counts payload bytes.
 *
 * The head is passed in rather than read here, so the framing decision
 * is made from what the server actually said and not from what a
 * previous response happened to look like.
 */
class BodyCounter(
    private val source: ByteSource,
    private val head: HttpHead,
    /**
     * Called with the running byte total after each read.
     *
     * The sampling a measurement needs, without coupling the reader to
     * any particular clock. The reader stays a counter; the probe
     * decides when to look.
     */
    private val onProgress: ((Long) -> Unit)? = null,
    /**
     * Stop reading at this time, deliberately.
     *
     * A transfer measured against a clock rather than a byte count: a
     * slow link is given the same wall-clock window as a fast one, and
     * therefore moves far fewer bytes. The remaining bytes are left
     * unread, which desynchronises the connection — so the caller must
     * close it rather than reuse it, and must tell [TransferCheck] the
     * short body was chosen rather than suffered.
     */
    private val stopAtNanos: Long? = null,
) {

    /** Payload bytes seen so far. Excludes all chunk framing. */
    var bytes: Long = 0L
        private set

    /** True when the drain ended at the deadline rather than at the end. */
    var stoppedEarly: Boolean = false
        private set

    /** True if the transfer was cut short of what it promised. */
    val truncated: Boolean
        get() {
            val declared = head.contentLength ?: return false
            return bytes < declared
        }

    /**
     * Reads the whole body and returns the payload byte count.
     *
     * Chunk headers and their trailing CRLFs are framing, not data.
     * Counting them would inflate the result by a few percent on a
     * transfer made of many small chunks — which is exactly the shape a
     * slow link produces, so the error would land hardest on the
     * connections that can least afford it.
     */
    fun drain(): Long {
        return if (head.isChunked) drainChunked() else drainUntilLength()
    }

    private fun drainUntilLength(): Long {
        val buffer = ByteArray(READ_CHUNK)
        val declared = head.contentLength
        while (true) {
            val remaining = declared?.let { it - bytes }
            val want = if (remaining != null) {
                if (remaining <= 0L) return bytes
                minOf(buffer.size.toLong(), remaining).toInt()
            } else {
                buffer.size
            }
            if (stopAtNanos != null && System.nanoTime() >= stopAtNanos) {
                stoppedEarly = true
                return bytes
            }
            val read = source.read(buffer, 0, want)
            if (read <= 0) return bytes
            bytes += read
            onProgress?.invoke(bytes)
        }
    }

    private fun drainChunked(): Long {
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val size = readChunkSize() ?: return bytes
            if (size == 0L) return bytes
            var remaining = size
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = source.read(buffer, 0, want)
                if (read <= 0) return bytes
                bytes += read
                remaining -= read
            }
            // The CRLF closing each chunk. Read past, never counted.
            readLine()
        }
    }

    /** A chunk size in hex, or null if the stream ended mid-header. */
    private fun readChunkSize(): Long? {
        val line = readLine() ?: return null
        return line.substringBefore(';').trim().toLongOrNull(16)
    }

    private fun readLine(): String? {
        val out = StringBuilder()
        while (true) {
            val b = source.readByte()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == CR) {
                val next = source.readByte()
                if (next == LF || next < 0) return out.toString()
                out.append('\r').append(next.toChar())
            } else {
                out.append(b.toChar())
            }
        }
    }
}

/**
 * A 64 KB read buffer, allocated once per transfer and reused.
 *
 * Not a micro-optimisation for its own sake. M-Lab's own ndt7 bug log
 * records a case where a client "was allocating memory for incoming
 * WebSocket messages during the download test, causing unnecessary
 * garbage collection and CPU usage, which ultimately" made the client
 * rather than the network the bottleneck. On a phone, that is the
 * difference between measuring a line and measuring a garbage
 * collector.
 */
internal const val READ_CHUNK = 64 * 1024

/** Carriage return and line feed as the ints a socket hands back. */
private const val CR = '\r'.code
private const val LF = '\n'.code

/**
 * One byte as an unsigned int, or -1 at end of stream.
 *
 * The `and 0xFF` is not decoration. A Java byte is signed, so a byte
 * whose value is 0xFF reads back as -1, and -1 is exactly the code every
 * caller here uses for "end of stream". Without the mask, a payload or
 * a header containing any high byte looks like EOF, and a chunked body
 * quietly decodes to nothing.
 */
internal fun ByteSource.readByte(): Int {
    val one = ByteArray(1)
    val read = read(one, 0, 1)
    return if (read <= 0) -1 else one[0].toInt() and 0xFF
}

/**
 * Reads a status line and header block off a socket.
 *
 * Returns null rather than throwing on a truncated head, because a peer
 * that closes mid-handshake is a network condition to report, not a
 * programming error to crash on.
 */
class HeadReader(private val source: ByteSource) {

    fun read(): HttpHead? {
        val statusLine = readLine() ?: return null
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2) return null
        val version = parts[0]
        val code = parts[1].toIntOrNull() ?: return null
        val reason = if (parts.size > 2) parts[2] else ""

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return HttpHead(version, code, reason, headers)
    }

    private fun readLine(): String? {
        val out = StringBuilder()
        while (true) {
            val b = source.readByte()
            if (b < 0) return if (out.isEmpty()) null else out.toString()
            if (b == CR) {
                val next = source.readByte()
                if (next == LF || next < 0) return out.toString()
                out.append('\r').append(next.toChar())
            } else {
                out.append(b.toChar())
            }
        }
    }
}

/** Validates a completed transfer, and says which way it failed. */
object TransferCheck {

    fun reject(
        head: HttpHead?,
        received: Long,
        timedOut: Boolean,
        /**
         * True where an empty 200 is the *expected* success.
         *
         * A download endpoint that answers 200 with no body has handed
         * back nothing to divide, and reporting 0 Mbps would be a claim.
         * An upload endpoint is the opposite: the edge acknowledges a
         * completed upload with "200, Content-Length: 0" and nothing
         * else, because the bytes went up rather than down. Treating
         * that as a truncated transfer failed every upload on the first
         * real run.
         */
        emptyBodyIsSuccess: Boolean = false,
        /** True when a short body is the plan, not a failure. */
        stoppedEarly: Boolean = false,
    ): Rejection? {
        if (head == null) return Rejection.NO_CONNECTION
        if (timedOut) return Rejection.TIMED_OUT
        if (!head.isSuccess) return Rejection.NOT_SUCCESS
        // Bound to a local. contentLength re-reads and re-parses a header
        // on every access, so the compiler will not smart-cast the
        // property — and calling it four times to check one number is
        // four header lookups.
        val declared = head.contentLength
        if (emptyBodyIsSuccess) return null
        if (declared == 0L) return Rejection.TRUNCATED
        if (declared != null && received < declared && !stoppedEarly) {
            return Rejection.TRUNCATED
        }
        if (declared == null && !head.isChunked) return Rejection.UNFRAMED
        return null
    }
}
