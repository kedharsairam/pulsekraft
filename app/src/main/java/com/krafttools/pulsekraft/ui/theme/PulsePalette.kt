package com.krafttools.pulsekraft.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * PulseKraft's own colours, measured rather than picked.
 *
 * Every value below was checked in two independent ways before it was
 * accepted, because a palette chosen by eye is a palette that fails on
 * somebody's screen:
 *
 *  - **WCAG contrast against the background.** Text needs 4.5:1 and
 *    every accent and trace line needs 3:1. All of them clear it, the
 *    tightest being [PrimaryDim] at 3.73:1.
 *  - **OKLab separation between neighbours.** OKLab is perceptually
 *    uniform, so a ΔE there means what it looks like. Below 0.02 two
 *    colours are the same colour; above 0.10 they are plainly different.
 *    Every pair that has to be told apart at a glance clears 0.10 —
 *    throughput against latency is 0.186, a number against a problem
 *    0.348.
 *
 * ## Why blue-violet, and not something else
 *
 * KraftTools is instrument-cyan. kraft-ui carries amber. Neither could
 * be reused without the two apps reading as the same product, and
 * identity is the whole reason a portfolio of separate apps is worth
 * having.
 *
 * Blue-violet is also the right *meaning*: this app is named for a
 * pulse, a waveform has a positive and a negative lobe, and the second
 * colour in a two-tone trace belongs on the negative one. The palette
 * argues for the name.
 *
 * ## The one colour that is deliberately absent
 *
 * There is no "good" green. Amber-for-problem next to green-for-fine is
 * the classic deuteranopia confusion, and a state that exists only as a
 * colour is a state that a screen reader user and a colourblind user
 * both lose entirely. So good news is unstyled: the absence of a
 * warning is the signal, and it cannot be misread.
 */
object PulsePalette {
    /** Violet-black. Deliberately not navy, so it never reads as KraftTools. */
    val Background = Color(0xFF100E17)
    val Surface = Color(0xFF191622)
    val SurfaceRaised = Color(0xFF221E2E)

    val OnSurface = Color(0xFFECE9F2)
    val OnSurfaceVariant = Color(0xFFA29BB5)

    /** The identity. Also the download figure, which is the headline. */
    val Primary = Color(0xFF9D8CFF)

    /** Upload: the same hue one rank down, not a second colour. */
    val PrimaryDim = Color(0xFF6E5FC4)

    /**
     * Latency — the thing this app is named for.
     *
     * Cool blue against the violet primary, 11.34:1 on the background
     * and ΔE 0.186 from it. A waveform's other lobe.
     */
    val Pulse = Color(0xFF6FD3FF)

    /**
     * Loaded latency has gone bad, or the line is spiking.
     *
     * The only state colour in the app, and it always ships with a word
     * beside it. Colour alone is never the message.
     */
    val Warning = Color(0xFFFFC53D)

    /** Gridlines and dividers. Present, never louder than the data. */
    val GridLine = Color(0xFF322C42)
}
