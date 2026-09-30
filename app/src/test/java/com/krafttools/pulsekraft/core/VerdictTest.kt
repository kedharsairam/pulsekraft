package com.krafttools.pulsekraft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict is the sentence the app leads with, so it is pinned here
 * rather than trusted.
 *
 * The case that matters most is the first one tested below: a good
 * bufferbloat ratio on a slow line. It passed through two working
 * versions of this app, both of which printed "Good for calls" over an
 * unloaded median of 342ms, because the ratio was consulted on its own
 * and 1.59x is an excellent score in isolation.
 */
class VerdictTest {

    @Test
    fun `a fast line with a good ratio is good for calls`() {
        assertEquals(
            "Good for calls",
            Verdict.for_(loadedMs = 77.0, idleMs = 49.0, index = 1.57),
        )
    }

    @Test
    fun `an excellent ratio on a slow line is not good for calls`() {
        // 342ms unloaded, 1.59x loaded. The ratio is the best of any run
        // recorded on this device and the sentence must still be right.
        val sentence = Verdict.for_(loadedMs = 545.0, idleMs = 342.0, index = 1.59)
        assertEquals("Too slow for calls or video", sentence)
        assertTrue(
            "must not describe a 545ms line as good for calls",
            !sentence.contains("Good for calls"),
        )
    }

    @Test
    fun `the ratio never outranks an unusable latency`() {
        // A single bad round trip can make the index enormous. Even at
        // 99x, a 900ms line is not something to recommend a call on.
        val sentence = Verdict.for_(loadedMs = 900.0, idleMs = 800.0, index = 99.0)
        assertEquals("Too slow for calls or video", sentence)
    }

    @Test
    fun `past the interactive limit but a good ratio reads as idle-only good`() {
        assertEquals(
            "Fine when idle, poor while busy",
            Verdict.for_(loadedMs = 210.0, idleMs = 150.0, index = 1.4),
        )
    }

    @Test
    fun `past the interactive limit and a bad ratio names the stutter`() {
        assertEquals(
            "Calls will stutter while busy",
            Verdict.for_(loadedMs = 180.0, idleMs = 55.0, index = 3.27),
        )
    }

    @Test
    fun `a fast line with a poor ratio is fine for browsing`() {
        // 130 over 18 is 7.2. Loaded stays under the interactive limit,
        // so the ratio is what decides — and 7.2 means the line is fine
        // right up until something uses it.
        assertEquals(
            "Fine for browsing, poor for calls",
            Verdict.for_(loadedMs = 130.0, idleMs = 18.0, index = 7.2),
        )
    }

    @Test
    fun `a latency past the interactive limit never reports as merely wobbly`() {
        // 300ms unloaded 40ms is 7.5x, and it is not a browsing problem:
        // the line is over twice the interactive limit whatever the
        // ratio says. The ratio branch must not be reachable here.
        assertEquals(
            "Calls will stutter while busy",
            Verdict.for_(loadedMs = 300.0, idleMs = 40.0, index = 7.5),
        )
    }

    @Test
    fun `a fast line with a middling ratio warns about wobble`() {
        assertEquals(
            "Usable, but calls may stutter",
            Verdict.for_(loadedMs = 95.0, idleMs = 30.0, index = 2.6),
        )
    }

    @Test
    fun `the boundary is the published standard and not a rounded number`() {
        // Exactly at the limit is still acceptable; one hundredth past
        // it is not. If this test ever needs changing, the constant
        // changed, and that is a decision about a standard rather than
        // about this app.
        assertEquals(
            "Good for calls",
            Verdict.for_(loadedMs = Verdict.INTERACTIVE_LIMIT_MS, idleMs = 40.0, index = 1.2),
        )
        assertEquals(
            "Fine when idle, poor while busy",
            Verdict.for_(
                loadedMs = Verdict.INTERACTIVE_LIMIT_MS + 0.01,
                idleMs = 40.0,
                index = 1.2,
            ),
        )
    }

    @Test
    fun `an inverted index cannot outrank a slow resting latency`() {
        // Observed on device: unloaded 229ms, loaded 76ms, index 0.33x.
        // Load makes this line FASTER, which is not a property of a
        // network — it is a baseline measured worse than the load. The
        // index looks excellent and the line is not.
        assertEquals(
            "Slow even when nothing is using it",
            Verdict.for_(loadedMs = 76.0, idleMs = 229.0, index = 0.33),
        )
        assertEquals(
            "Slow even when nothing is using it",
            Verdict.for_(loadedMs = 130.0, idleMs = 260.0, index = 0.5),
        )
    }

    @Test
    fun `loaded under the limit with idle over it can only be one message`() {
        // Arithmetic, not preference: whenever loaded <= 150 and idle >
        // 150, loaded/idle is below 1, so no "worse under load" variant
        // can ever be reached. Pinned so a future branch cannot be added
        // back on the assumption that it is reachable.
        val message = Verdict.for_(loadedMs = 140.0, idleMs = 400.0, index = 0.35)
        assertEquals("Slow even when nothing is using it", message)
    }

    @Test
    fun `a slow resting line is never called good for calls`() {
        val sentence = Verdict.for_(loadedMs = 76.0, idleMs = 229.0, index = 0.33)
        assertTrue(
            "an inverted index must not produce a compliment",
            !sentence.contains("Good"),
        )
    }

    @Test
    fun `a spiky watch is not good for calls at any ratio`() {
        // Observed on device: 62ms unloaded, 62ms under load, 1.01x —
        // an excellent result on both counts — printed directly above
        // "Spiky, 41 to 405 ms". The 405ms excursion IS the stutter.
        val sentence = Verdict.for_(
            loadedMs = 62.0, idleMs = 62.0, index = 1.01, spiky = true,
        )
        assertEquals("Calls will stutter on spikes", sentence)
    }

    @Test
    fun `spikiness does not outrank a line that is simply too slow`() {
        // A latency past the interactive limit is a bigger problem than
        // a jump in one that is already unusable, and the sentence has
        // to name the bigger one.
        assertEquals(
            "Too slow for calls or video",
            Verdict.for_(loadedMs = 900.0, idleMs = 800.0, index = 1.1, spiky = true),
        )
    }

    @Test
    fun `a steady watch with a middling ratio still warns`() {
        assertEquals(
            "Usable, but calls may stutter",
            Verdict.for_(loadedMs = 95.0, idleMs = 30.0, index = 2.6, spiky = false),
        )
    }

    @Test
    fun `a missing figure yields incomplete rather than a guess`() {
        assertEquals(Verdict.INCOMPLETE, Verdict.for_(null, 40.0, 1.2))
        assertEquals(Verdict.INCOMPLETE, Verdict.for_(80.0, null, 1.2))
        assertEquals(Verdict.INCOMPLETE, Verdict.for_(80.0, 40.0, null))
    }
}