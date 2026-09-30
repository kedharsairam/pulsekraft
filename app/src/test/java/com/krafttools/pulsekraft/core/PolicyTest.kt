package com.krafttools.pulsekraft.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The data-cost rules, pinned.
 *
 * These are the only rules in the app that can talk somebody out of
 * using it, so they are the ones most worth being sure about. Every case
 * below was a decision, and several were not obvious ones.
 */
class PolicyTest {

    private fun unmetered() = LinkState(
        connected = true, link = Link.WIFI, metered = false,
    )

    @Test
    fun `unmetered is allowed without comment`() {
        assertEquals(
            Permission.Allow,
            Policy.decide(unmetered(), Volume.LIGHT),
        )
        assertEquals(
            Permission.Allow,
            Policy.decide(unmetered(), Volume.FULL),
        )
    }

    @Test
    fun `no connection is refused because there is nothing to measure`() {
        val decision = Policy.decide(LinkState(), Volume.LIGHT)
        assertTrue(decision is Permission.Refuse)
        assertEquals(
            "No connection. Nothing to measure.",
            (decision as Permission.Refuse).reason,
        )
    }

    @Test
    fun `a platform that tells us nothing is treated as offline`() {
        // Every LinkState field defaults to the cautious reading. A
        // permission can be revoked between the check and the use, and
        // ConnectivityManager can return no capabilities at all on some
        // devices. Defaulting to "connected and unmetered" would fail
        // open on precisely the paths where the platform knows least.
        val decision = Policy.decide(LinkState(), Volume.LIGHT)
        assertTrue(
            "an unknown link must not be allowed to spend data",
            decision !is Permission.Allow,
        )
    }

    @Test
    fun `roaming is refused outright`() {
        // The reason this is a refusal and not a warning: the cost lands
        // on whoever owns the network, at rates this app cannot show
        // and the person did not agree to.
        val decision = Policy.decide(
            LinkState(connected = true, link = Link.CELLULAR, metered = true, roaming = true),
            Volume.LIGHT,
        )
        assertTrue(decision is Permission.Refuse)
        assertTrue(
            "the reason must say whose data this is",
            (decision as Permission.Refuse).reason.contains("roaming", ignoreCase = true),
        )
    }

    @Test
    fun `roaming is refused even when the network reports itself unmetered`() {
        // Some carriers mark a roaming connection as unmetered. The
        // roaming fact is the one that matters and is checked first, so
        // a mislabelled network cannot wave this app through.
        val decision = Policy.decide(
            LinkState(connected = true, link = Link.CELLULAR, metered = false, roaming = true),
            Volume.FULL,
        )
        assertTrue(decision is Permission.Refuse)
    }

    @Test
    fun `the full profile on mobile data is refused and names the way out`() {
        val decision = Policy.decide(
            LinkState(connected = true, link = Link.CELLULAR, metered = true),
            Volume.FULL,
        )
        assertTrue(decision is Permission.Refuse)
        val reason = (decision as Permission.Refuse).reason
        assertTrue("must name the cost", reason.contains("100 MB"))
        assertTrue("must name the way out", reason.contains("Wi-Fi"))
    }

    @Test
    fun `the light profile on mobile data is allowed with the cost stated`() {
        val decision = Policy.decide(
            LinkState(connected = true, link = Link.CELLULAR, metered = true),
            Volume.LIGHT,
        )
        assertTrue(decision is Permission.Warn)
        assertTrue(
            "the warning must carry the number",
            (decision as Permission.Warn).reason.contains("25 MB"),
        )
    }

    @Test
    fun `the stated cost is the profile's own byte ceiling`() {
        // Not a number written beside the rule. If a profile's ceiling
        // changes, the warning changes with it, or the warning is a lie
        // on exactly the run where being wrong costs the user money.
        assertEquals(25.0, Policy.megabytesFor(Volume.LIGHT), 0.001)
        assertEquals(100.0, Policy.megabytesFor(Volume.FULL), 0.001)
    }

    @Test
    fun `a refused decision never mentions a cost it did not reach`() {
        // Offline is decided before any volume is considered, so it must
        // not quote a megabyte figure the person cannot act on.
        val reason = (Policy.decide(LinkState(), Volume.FULL) as Permission.Refuse).reason
        assertTrue("must not quote a cost it never reached", !reason.contains("MB"))
    }

    @Test
    fun `every link has a name for the interface`() {
        Link.entries.forEach {
            assertTrue("${it.label} has no label", it.label.isNotBlank())
        }
    }
}