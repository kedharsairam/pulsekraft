package com.krafttools.pulsekraft

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.krafttools.pulsekraft.core.Method
import com.krafttools.pulsekraft.core.Profiles
import com.krafttools.pulsekraft.core.Volume
import com.krafttools.pulsekraft.ui.theme.PulseKraftTheme
import com.krafttools.pulsekraft.ui.theme.PulsePalette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PulseKraftTheme {
                Scaffold { padding ->
                    FirstSlice(padding)
                }
            }
        }
    }
}

/**
 * A placeholder, and it says so.
 *
 * The first slice of this app is the measurement engine, and it is
 * finished and tested. What is not built yet is the part a person would
 * actually use, so this screen states that rather than showing a fake
 * number — a speed test that renders a plausible-looking result before
 * it can measure one is the exact failure this project exists to avoid.
 *
 * It does show the method, because the method is real, decided, and
 * already written down in [Method]. Showing it now is free, and it is
 * the part of the app that will not change.
 */
@Composable
private fun FirstSlice(padding: androidx.compose.foundation.layout.PaddingValues) {
    val profile = Profiles.of(Volume.LIGHT)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "PulseKraft",
            style = MaterialTheme.typography.headlineMedium,
            color = PulsePalette.Primary,
        )
        Text(
            text = "The engine is built. The test is not wired to it yet.",
            style = MaterialTheme.typography.bodyLarge,
            color = PulsePalette.OnSurface,
        )
        Text(
            text = "No number is shown, because none has been measured. " +
                "This app will not print a figure it has not taken.",
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
        Text(
            text = "Method",
            style = MaterialTheme.typography.titleSmall,
            color = PulsePalette.Pulse,
        )
        Text(
            text = Method.AGGREGATION,
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurface,
        )
        Text(
            text = "Light profile: " +
                "${profile.targetBytes / (1024 * 1024)} MB each way, " +
                "${profile.graceMillis / 1000}s grace, " +
                "${profile.stabilitySeconds}s stability trace.",
            style = MaterialTheme.typography.bodyMedium,
            color = PulsePalette.OnSurfaceVariant,
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = PulsePalette.OnSurfaceVariant,
            modifier = Modifier.align(Alignment.Start),
        )
    }
}
