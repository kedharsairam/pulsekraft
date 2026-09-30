package com.krafttools.pulsekraft.core

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Socket buffers, and the ceiling they impose on what this app can see.
 *
 * This file is the reason PulseKraft does not simply add an HTTP client
 * and call [androidx.compose.runtime.Provable]. A receive buffer is not
 * a performance tweak. On a path with a meaningful round trip, the
 * kernel can only keep the pipe full if the receive window is at least
 * the bandwidth-delay product, and if the window is smaller than the
 * product then **the app is the bottleneck, not the network** — the
 * transfer stalls waiting for the receiver to acknowledge, and the
 * resulting "throughput" is a measurement of the buffer, dressed up as a
 * measurement of the line.
 *
 * RFC 6349 puts the ceiling in one line:
 *
 *     TCP Throughput = max FPS x (MTU - 40) x 8
 *
 * Rearranged into the form that matters here, because a fixed receive
 * window `W` and a round trip `t` can move at most `W` bytes per round
 * trip:
 *
 *     ceiling bytes/sec = W / t
 *     ceiling Mbps      = W * 8 / t_seconds / 1e6
 *
 * On a 200 ms path with a 64 KB window that is 2.6 Mbps. A 2.6 Mbps
 * result on a gigabit line is not a slow line; it is a small buffer.
 *
 * ## The two platform facts that make this awkward
 *
 * **The buffer must be set before the connection is made.** A receive
 * window above 64 KB has to be *requested* prior to connect, because the
 * window scale option is negotiated in the handshake. A stock HTTP
 * client that opens the socket for us will not let us do that, which is
 * why there is no HTTP dependency in this project yet.
 *
 * **It is only a hint.** The kernel may refuse, and the kernel
 * allocates twice the value requested. So the value read back with
 * `getReceiveBufferSize()` is the only one that means anything, and it
 * is the one this app reports.
 */
object Buffer {

    /** Per-packet header and trailer cost, from RFC 6349's (MTU - 40). */
    const val OVERHEAD_BYTES = 40

    /** The overwhelmingly common path MTU. Ethernet, and most mobile. */
    const val DEFAULT_MTU = 1500

    /** Usable payload per frame at the default MTU. */
    const val DEFAULT_PAYLOAD_BYTES = DEFAULT_MTU - OVERHEAD_BYTES

    /**
     * Bytes in flight needed to keep a path of this bandwidth and round
     * trip full.
     */
    fun bdpBytes(bandwidthBps: Double, rttMillis: Double): Long {
        if (bandwidthBps <= 0.0 || rttMillis <= 0.0) return 0L
        return (bandwidthBps * rttMillis / 1000.0 / 8.0).roundToLong()
    }

    /**
     * The fastest rate this receive window could possibly sustain on
     * this path.
     *
     * Anything above this cannot be observed by this app regardless of
     * how fast the line is.
     */
    fun ceilingMbps(receiveWindowBytes: Int, rttMillis: Double): Double {
        if (receiveWindowBytes <= 0 || rttMillis <= 0.0) return 0.0
        val seconds = rttMillis / 1000.0
        return receiveWindowBytes * 8.0 / seconds / 1_000_000.0
    }

    /**
     * How large a receive buffer this app should ask for.
     *
     * [headroom] exists because the product alone is not enough. The
     * congestion window grows and shrinks, the path may re-route mid
     * test, and a window sized exactly to the product leaves no room for
     * a transient. ndt7's own diagnostics include a `RWndLimited`
     * counter for exactly this failure — "the receiver does not have
     * enough buffering to go faster and it is limiting our performance"
     * — and we cannot read that counter here, so the buffer is sized
     * generously instead.
     *
     * Capped at 4 MiB. That is a platform ceiling rather than a
     * measured one: the kernel's `net.core.rmem_max` bounds what
     * `SO_RCVBUF` can become, and asking for far more is wasted effort.
     * What the kernel actually granted is read back and reported, so a
     * cap that binds is visible rather than silent.
     */
    const val DEFAULT_HEADROOM = 2.0
    const val MAX_REQUEST_BYTES = 4 * 1024 * 1024

    fun suggestReceiveBuffer(
        bandwidthBps: Double,
        rttMillis: Double,
        headroom: Double = DEFAULT_HEADROOM,
    ): Int {
        val wanted = bdpBytes(bandwidthBps, rttMillis) * headroom
        if (wanted <= 0.0) return 0
        return minOf(wanted, MAX_REQUEST_BYTES.toDouble()).toInt()
    }

    /**
     * Whether a measured result might be this app's buffer rather than
     * the user's line.
     *
     * The honest self-check, and the reason this app can be believed
     * when it reports a low number: if the ceiling implied by the
     * granted buffer sits at or just above what was measured, the
     * transfer may have been window-limited, and the true capacity of
     * the line could be higher. Saying so is better than publishing a
     * number the app caused.
     *
     * [headroomMultiple] is how close counts as suspicious. 1.0 means the
     * measurement reached the ceiling exactly.
     */
    fun mayBeBufferLimited(
        measuredMbps: Double,
        grantedBufferBytes: Int,
        rttMillis: Double,
        headroomMultiple: Double = 1.25,
    ): Boolean {
        val ceiling = ceilingMbps(grantedBufferBytes, rttMillis)
        if (ceiling <= 0.0) return false
        return measuredMbps >= ceiling / headroomMultiple
    }
}

/**
 * Why a transfer cannot be turned into a number.
 *
 * Every case produces a confident wrong answer if it is ignored:
 *
 * - **A non-2xx status.** LibreSpeed shipped years with an upload bug
 *   where an HTTP error response still let the client start a
 *   replacement upload, and the reported speed was inflated. An error
 *   body is not throughput.
 * - **A body shorter than it declared.** The transfer was cut. The bytes
 *   that arrived divided by the time they took is a rate the line never
 *   sustained.
 * - **A missing length and no chunking.** There is no way to know the
 *   transfer is finished, so there is no way to time it honestly.
 */
enum class TransferRejection(val explanation: String) {
    NOT_SUCCESS("the edge refused the transfer, and an error body is not throughput"),
    TRUNCATED("the transfer ended before the length it declared"),
    UNFRAMED("the response did not say how long it was, so it cannot be timed honestly"),
    TIMED_OUT("the transfer did not finish in the time allowed"),
    NO_CONNECTION("the edge could not be reached"),
}

/** A whole test's result, and the honesty that comes with it. */
data class TestReport(
    val download: Throughput?,
    val upload: Throughput?,
    val idleLatency: LatencySummary?,
    val loadedDuringDownload: LatencySummary?,
    val loadedDuringUpload: LatencySummary?,
    val stability: Latency.Stability?,
    val volume: Volume,
    val edgeHost: String,
    val protocolNote: String,
    /**
     * The receive buffer the kernel actually granted, in bytes.
     *
     * Not the one requested. `SO_RCVBUF` is a hint the kernel may
     * refuse, and it allocates twice what is asked for, so this is the
     * only figure that describes what the app was actually capable of
     * — and therefore the only one that can be compared honestly
     * against a measured result.
     */
    val grantedReceiveBufferBytes: Int = 0,
    /** Set when a transfer was refused or cut short. Null when sound. */
    val rejectedBecause: TransferRejection? = null,
    /**
     * The latency samples from the stability watch, in order.
     *
     * Carried so the result screen can draw the trace that produced the
     * verdict. A verdict of "spiky" with nothing to look at is a
     * conclusion the user is asked to take on trust, and this app does
     * not ask for that anywhere else — a number without its shape is
     * the wall-of-text problem wearing a chart's clothes.
     *
     * Bounded at the source, because an unbounded sample list on a phone
     * is a slow leak.
     */
    val stabilitySeries: List<Double> = emptyList(),
) {
    /**
     * Whether the download figure may be this app's buffer rather than
     * the user's line.
     *
     * Only answerable once there is both a result and an idle latency to
     * compare it against, which is why it is a method and not a field.
     * Null means "cannot tell", which is different from "no".
     */
    fun downloadMayBeBufferLimited(): Boolean? {
        val measured = download?.medianMbps ?: return null
        val rtt = idleLatency?.medianMs ?: return null
        return Buffer.mayBeBufferLimited(measured, grantedReceiveBufferBytes, rtt)
    }

    /** The same question of the upload figure. */
    fun uploadMayBeBufferLimited(): Boolean? {
        val measured = upload?.medianMbps ?: return null
        val rtt = idleLatency?.medianMs ?: return null
        return Buffer.mayBeBufferLimited(measured, grantedReceiveBufferBytes, rtt)
    }
    /**
     * The bufferbloat index, preferring the download phase.
     *
     * A download saturates the link in the direction that carries the
     * most traffic in normal use, so it is the phase whose loaded
     * latency matters most. Null when there is no idle baseline, which
     * is different from a ratio of 1.0 and must not be shown as zero.
     */
    val bufferbloatIndex: Double?
        get() {
            val idle = idleLatency?.medianMs ?: return null
            val loaded = (loadedDuringDownload ?: loadedDuringUpload)?.medianMs ?: return null
            return Bufferbloat.index(loaded, idle)
        }

    val bufferbloatGrade: Bufferbloat.Grade?
        get() = bufferbloatIndex?.let { Bufferbloat.grade(it) }
}

/** Small helpers kept together so the engine and the UI agree on them. */
internal object Guard {
    fun requireFinite(value: Double, fallback: Double = 0.0): Double =
        if (value.isNaN() || value.isInfinite()) fallback else value

    fun positiveOrNull(value: Double): Double? =
        if (value.isNaN() || value.isInfinite() || value <= 0.0) null else max(value, 0.0)
}
