package com.krafttools.pulsekraft

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The README's test table, against the tests that exist.
 *
 * ## Why this file exists
 *
 * It exists because the table was already wrong once. It claimed ten
 * `MethodTest` cases and there were eleven, and it had been wrong long
 * enough to be written, committed, published in a release, and quoted
 * back. Nothing had failed. Every test in the suite passed.
 *
 * That is the exact failure this project keeps meeting in other forms:
 *
 *  - `Method.AGGREGATION` described a statistic the code had stopped
 *    using, and the test checked for the word "goodput" rather than
 *    for the claim
 *  - the README said a heavier test was opt-in and it could not be
 *  - the receive-buffer self-check was implemented and never called
 *  - `Method.KEEP_ALIVE` was tested and shown in no screen
 *
 * Every one of those was a sentence that had gone out of date without
 * anything noticing. This is the version that notices.
 *
 * ## What it does and does not do
 *
 * It checks that every suite is named, that each has at least the
 * number of cases claimed, and that the totals add up. It cannot count
 * the cases itself without reflection over the other suites, which
 * would make the tests depend on each other for no good reason — so the
 * counts are a floor, not an equality, and the *total* is checked
 * against the suites it knows about.
 *
 * A new suite has to be added to [SUITES] deliberately. That is the
 * point: a documentation table that updates itself is a documentation
 * table nobody has to think about.
 */
class ReadmeAccuracyTest {

    /**
     * The README as text.
     *
     * The unit-test working directory is the module, not the repository
     * root, so both are tried. A missing file is an error rather than
     * a skip: a test that quietly stops testing when a file moves is
     * how this project arrived here in the first place.
     */
    private val readme: String
        get() = sequenceOf("README.md", "../README.md")
            .map(::File)
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("README.md not found from ${File(".").absolutePath}")

    private companion object {
        /** Every suite, and the smallest number of cases it may have. */
        val SUITES = mapOf(
            "ProtocolTest" to 21,
            "TransportHeadTest" to 10,
            "TransportSocketTest" to 5,
            "EdgeContractTest" to 6,
            "RateTest" to 18,
            "BufferTest" to 17,
            "VerdictTest" to 16,
            "LatencyTest" to 14,
            "PolicyTest" to 10,
            "MethodTest" to 11,
            // This suite, counting itself. It caught its own author's
            // arithmetic the first time it ran — the README total was
            // bumped for these four tests without the table being
            // reconciled, which is precisely the drift it exists to
            // catch. It is listed so the sum is whole.
            "ReadmeAccuracyTest" to 4,
        )
    }

    @Test
    fun `every suite is named in the README`() {
        val text = readme
        SUITES.keys.forEach { suite ->
            assertTrue(
                "$suite is not mentioned in the README, so nobody can find it",
                text.contains("`$suite`"),
            )
        }
    }

    @Test
    fun `no suite is credited with fewer tests than it has`() {
        val text = readme
        SUITES.forEach { (suite, atLeast) ->
            val claimed = Regex("`$suite` \\((\\d+)\\)")
                .find(text)?.groupValues?.get(1)?.toIntOrNull()
            assertTrue("$suite has no count in the README", claimed != null)
            assertTrue(
                "the README credits $suite with $claimed tests, but it has at least $atLeast. " +
                    "A table that undercounts is worse than no table.",
                claimed!! >= atLeast,
            )
        }
    }

    @Test
    fun `the published totals are the sum of the suites`() {
        val text = readme
        val total = SUITES.values.sum()

        val unitTotal = Regex("(\\d+) JVM unit tests")
            .find(text)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals(
            "the README's unit total is not the sum of its own table",
            total,
            unitTotal,
        )
    }

    @Test
    fun `the README does not promise a port the app does not have`() {
        // Each of these was, at some point, a sentence describing
        // something the code did not do. If one reappears, this fails.
        val text = readme
        listOf(
            "opt-in only, and there is no way",
            "no instrumented tests",
            "no landscape",
        ).forEach { phrase ->
            assertTrue(
                "the README contains a stale claim: \"$phrase\"",
                !text.contains(phrase, ignoreCase = true),
            )
        }
    }
}