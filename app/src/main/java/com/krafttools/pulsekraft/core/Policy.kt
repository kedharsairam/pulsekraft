package com.krafttools.pulsekraft.core

/** What kind of link is carrying the connection, as far as we can tell. */
enum class Link {
    NONE,
    WIFI,
    CELLULAR,
    OTHER,
    ;

    /** For the interface. Not `name`, which `Enum` already owns. */
    val label: String
        get() = when (this) {
            NONE -> "No connection"
            WIFI -> "Wi-Fi"
            CELLULAR -> "Mobile data"
            OTHER -> "Another network"
        }
}

/**
 * The facts about the current connection that bear on whether it is
 * sensible to spend data on it.
 *
 * Every field defaults to the cautious reading, because this type is
 * built from a platform API that can return nothing and a permission
 * that can be revoked between the check and the use. A field that
 * defaults to "fine" would fail open on exactly the paths where the
 * platform is least able to tell us anything.
 */
data class LinkState(
    val connected: Boolean = false,
    val link: Link = Link.NONE,
    val metered: Boolean = true,
    val roaming: Boolean = false,
)

/** What the app should do about a test, and what to tell the person. */
sealed interface Permission {

    /** Run it. Nothing needs saying. */
    data object Allow : Permission

    /**
     * Run it, but say this first.
     *
     * A warning is not a confirmation prompt. There is no dialog
     * anywhere in this app and adding one to say "are you sure" about a
     * button that already says what it costs would be the worse
     * interface: the cost is on the idle screen, in the same words, and
     * pressing the control is the confirmation.
     */
    data class Warn(val reason: String) : Permission

    /** Do not run it. */
    data class Refuse(val reason: String) : Permission
}

/**
 * Whether to spend this much data on this connection.
 *
 * This is the whole reason the app asks for `ACCESS_NETWORK_STATE`, and
 * it is pure so it can be tested without a device. The platform read
 * that produces a [LinkState] is a thin adapter over ConnectivityManager
 * with no logic of its own; every rule lives here where a unit test can
 * reach it.
 *
 * The rules are ordered from absolute to conditional, and the order is
 * the design:
 *
 *  1. **No connection.** Nothing to measure. There is no point where a
 *     zero would be a useful answer.
 *  2. **Roaming.** Refused outright. Not because a roaming test is
 *     inaccurate but because it is billed to whoever owns the network
 *     you are standing on, at rates you did not agree to, and this app
 *     has no way to ask that person. Twenty-five megabytes of somebody
 *     else's money is not this app's decision to make.
 *  3. **Metered, and the heavier profile.** Refused, with the way out
 *     named. A hundred megabytes on mobile data is a decision most
 *     people make once and are annoyed by afterwards.
 *  4. **Metered, and the light profile.** Allowed with the cost stated.
 *     Twenty-five megabytes is a real cost but a small one, the button
 *     says so before it is pressed, and refusing it would be paternalism
 *     about someone else's data plan.
 *
 * Unmetered is not mentioned in any rule because it needs none.
 */
object Policy {

    /** Megabytes the given profile will move in the worst case. */
    fun megabytesFor(volume: Volume): Double =
        Profiles.of(volume).targetBytes / 1_048_576.0

    fun decide(state: LinkState, volume: Volume): Permission {
        if (!state.connected) {
            return Permission.Refuse("No connection. Nothing to measure.")
        }
        if (state.roaming) {
            return Permission.Refuse(
                "You're roaming. A test would spend this network owner's data, " +
                    "so this app will not start one.",
            )
        }
        if (state.metered) {
            val cost = "%.0f MB".format(megabytesFor(volume))
            if (volume == Volume.FULL) {
                return Permission.Refuse(
                    "$cost of mobile data. Connect to Wi-Fi for the full test.",
                )
            }
            return Permission.Warn("This will use about $cost of mobile data.")
        }
        return Permission.Allow
    }
}