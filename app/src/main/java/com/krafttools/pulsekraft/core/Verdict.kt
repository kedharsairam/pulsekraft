package com.krafttools.pulsekraft.core

/**
 * The one-sentence answer the result screen leads with.
 *
 * It lives here rather than in the UI because it is a claim the app
 * publishes, not a piece of presentation. A measurement app that prints
 * a judgement has to be as testable as the number it is judging, and a
 * test suite that cannot reach a `private fun` in a composable file
 * cannot hold that promise.
 *
 * It lives in `core` for a second reason: it is pure. No Android, no
 * Compose, no formatting of the current locale — it returns a word.
 * Everything about *how* it is drawn belongs to the screen.
 */
object Verdict {

    /**
     * One-way mouth-to-ear delay above which interactive use suffers.
     *
     * ITU-T G.114, the standard for that figure. A published number
     * rather than one picked to make a chart look interesting.
     */
    const val INTERACTIVE_LIMIT_MS = 150.0

    /** Beyond this, interactive use is impaired badly enough to say so. */
    const val UNUSABLE_MS = 400.0

    /**
     * What to tell this person about this connection.
     *
     * The absolute loaded latency is checked BEFORE the bufferbloat
     * ratio, and the order is the whole point.
     *
     * A ratio on its own cannot tell a fast line from a broken one.
     * 1.1x is an excellent bufferbloat score and means nothing at all
     * about quality if the unloaded latency is 342ms: 1.1x of that is
     * 376ms, and a verdict of "Good for calls" printed over 376ms is
     * simply wrong. Two of the test runs that produced this function
     * returned a ratio of 1.59 and an unloaded median of 342ms, and
     * the ratio-first version called that "Good for calls".
     *
     * So: is it fast enough at all, and only then does the question of
     * how much the line degrades under load become the interesting one.
     */
    fun for_(
        loadedMs: Double?,
        idleMs: Double?,
        index: Double?,
        spiky: Boolean = false,
    ): String {
        if (loadedMs == null || idleMs == null || index == null) return INCOMPLETE
        // Guarded rather than trusted: a single bad round trip can make
        // the ratio enormous, and no ratio should be able to outrank a
        // latency that is past the point of being useful.
        val ratio = index.coerceIn(0.0, 99.0)

        // A resting latency is a fact in its own right, checked before
        // the ratio for the same reason the loaded one is: a line that
        // sits at 229ms with nothing using it is slow, whatever the
        // index says. And the index here would have said "excellent",
        // because 76ms under load over a 229ms baseline is 0.33x — a
        // number that is not a good bufferbloat score but an inverted
        // measurement, which is exactly what it is when the baseline is
        // worse than the load.
        val idleSlow = idleMs > INTERACTIVE_LIMIT_MS

        return when {
            loadedMs > UNUSABLE_MS -> "Too slow for calls or video"
            loadedMs > INTERACTIVE_LIMIT_MS -> when {
                ratio > 2.0 -> "Calls will stutter while busy"
                else -> "Fine when idle, poor while busy"
            }
            // One message, not two. An earlier version branched here on
            // the ratio, and a test caught that the branch was
            // unreachable: this case is only reached when loaded is at or
            // under the interactive limit and idle is over it, so
            // loaded/idle is necessarily below 1. A "worse under load"
            // variant here can never print.
            idleSlow -> "Slow even when nothing is using it"
            // Spiky wins over the ratio, and it is checked here for the
            // same reason the absolute latencies are: the headline is
            // the sentence a person acts on, and this screen was
            // printing "Good for calls" directly above "Spiky, 41 to
            // 405 ms". A 405ms excursion IS the call stuttering. The
            // ratio measures how much a full-speed transfer inflates
            // the queue, which is a different question, and answering it
            // while the answer to the question that was asked sat on the
            // screen two lines below is worse than answering neither.
            spiky -> "Calls will stutter on spikes"
            ratio > 4.0 -> "Fine for browsing, poor for calls"
            ratio > 2.0 -> "Usable, but calls may stutter"
            else -> "Good for calls"
        }
    }

    const val INCOMPLETE = "Test incomplete"
}