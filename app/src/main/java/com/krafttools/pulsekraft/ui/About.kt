package com.krafttools.pulsekraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.krafttools.pulsekraft.BuildConfig
import com.krafttools.pulsekraft.core.Method
import com.krafttools.pulsekraft.ui.theme.PulsePalette

/**
 * The about sheet.
 *
 * It exists because of one fact. Every other app in this family runs
 * entirely offline, and this one asks for `INTERNET`, which means the
 * person installing it is handing over something none of the others
 * ask for. That has to be said plainly, in the app, by the app — not
 * left to a store listing or a privacy policy, and not taken on faith
 * from the fact that the name has a nice mark on it.
 *
 * It is a sheet and not a screen because it is a document. Same
 * reasoning as the method panel on the result screen: a long-form
 * disclosure is longer than a phone, and the no-scroll rule exists to
 * stop a *view* hiding its own priorities, which is a different
 * problem from a page of text that genuinely has to be read.
 */
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // The scrim is a scrim, not a dimmer. It has to make the
            // screen behind unmistakably not-interactive, because a
            // sheet that can be tapped through is not a sheet.
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize(0.86f)
                .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                .background(PulsePalette.Surface)
                // Taps inside the sheet must not reach the scrim, or the
                // sheet closes when you try to read it.
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            // The grabber. Conventional, and the only chrome on the
            // sheet — there is no close button because dragging is the
            // gesture and a second affordance for the same action is
            // one more thing to look at.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(PulsePalette.GridLine),
                )
            }

            Text(
                text = "PulseKraft",
                style = MaterialTheme.typography.headlineSmall,
                color = PulsePalette.OnSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium.merge(Tabular),
                color = PulsePalette.OnSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))
            Lead(
                "Measures how fast this connection is, and how well it holds up " +
                    "while something else is using it.",
            )

            Spacer(Modifier.height(28.dp))
            // The section that matters most, so it comes first. Not at
            // the bottom under a licence, which is where a permission
            // explanation goes to be skimmed past.
            Section("The one thing to know") {
                Paragraph(
                    "This is the only app in this family that asks for access to " +
                        "the internet. Every other one works entirely offline and " +
                        "never sees a network at all.",
                )
                Paragraph(
                    "It needs it because a speed test that does not touch the " +
                        "network cannot measure the network. The connection goes " +
                        "to the nearest edge run by Cloudflare and nowhere else — " +
                        "no account, no server of ours, no analytics, no crash " +
                        "reporting, nothing sent anywhere.",
                )
                Paragraph(
                    "Results are never written down. Not to a file, not to a " +
                        "database, not off the device. Close the app and the " +
                        "measurement is gone, which is why there is no history.",
                )
            }

            Section("What it will not tell you") {
                Paragraph(
                    "It does not report packet loss. Loss cannot be observed over " +
                        "HTTP, because TCP retransmits it invisibly — the packets " +
                        "do arrive, just late, and a figure for it would be invented.",
                )
                Paragraph(
                    "It does not discard outliers. Nothing is thrown away as " +
                        "spiky, no best-of-N is taken, and no figure is smoothed " +
                        "into looking steadier than the line beneath it.",
                )
                Paragraph(
                    "It does not claim your line reaches the edge. What it measures " +
                        "is the path to that edge, and the difference between the " +
                        "two is exactly where most of the disappointment lives.",
                )
            }

            Section("How it is measured") {
                Paragraph(Method.AGGREGATION)
                // KEEP_ALIVE was written, tested and shown nowhere. It is
                // the sentence that answers the objection a sceptical
                // reader has first — "you are measuring handshakes" — and
                // it is now in the one place in the app where a
                // disclosure belongs, rather than only in a test that
                // checks it says something.
                Paragraph(Method.KEEP_ALIVE)
                Paragraph(Method.SCOPE)
                Paragraph(Method.LIMITS)
            }

            Section("The data it uses") {
                Paragraph(
                    "INTERNET, to measure. ACCESS_NETWORK_STATE, to say what the " +
                        "measurement will cost before spending it — the app will " +
                        "not start a hundred-megabyte test on mobile data, and will " +
                        "not start any test while roaming.",
                )
                Paragraph(
                    "Nothing else. No camera, location, microphone, storage, " +
                        "notifications, accounts or background work.",
                )
            }

            Section("Licence") {
                Paragraph(
                    "MIT. The source is public and you can read every line of " +
                        "how this app measures before you trust a figure from it.",
                )
            }

            Spacer(Modifier.height(26.dp))
            PrimaryAction("Done", onDismiss)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A letter-spaced section header. Reads as a heading, not as a line of copy. */
@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium.merge(Tabular),
        color = PulsePalette.Primary,
        letterSpacing = 1.3.sp,
    )
    Spacer(Modifier.height(10.dp))
    body()
    Spacer(Modifier.height(22.dp))
}

/** The opening statement, set larger than the body it introduces. */
@Composable
private fun Lead(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = PulsePalette.OnSurface,
    )
}

/**
 * Body text.
 *
 * Bounded measure. A sheet is 411dp wide and full-bleed body text at
 * this size is a very long line, which is the least readable shape a
 * paragraph can take — this is set to leave a margin at wide widths so
 * it never gets there.
 */
@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        // bodyMedium, not bodyLarge. At bodyLarge this sheet held six
        // words a line and reading a paragraph took a full screen of
        // scrolling, which is the opposite of what a disclosure is for.
        // The lead above stays a size larger so the entry point is still
        // obvious.
        style = MaterialTheme.typography.bodyMedium,
        color = PulsePalette.OnSurfaceVariant,
        lineHeight = 21.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    )
}