package com.krafttools.pulsekraft.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark only, and not by accident.
 *
 * A network instrument is used in a room, at night, next to a bed, and
 * a white screen at 1am is a genuine harm. A light theme here would be
 * a feature nobody asked for, so there isn't one — the same decision
 * KraftTools made, for the same reason.
 *
 * The scheme is also pinned to the palette rather than to Material's
 * dynamic colour. Dynamic colour would hand the app's identity to
 * whatever wallpaper the user has, and this app has exactly one job,
 * which is to look like a measuring instrument belonging to a family.
 */
private val PulseColors = darkColorScheme(
    primary = PulsePalette.Primary,
    onPrimary = PulsePalette.Background,
    primaryContainer = PulsePalette.PrimaryDim,
    onPrimaryContainer = PulsePalette.OnSurface,
    secondary = PulsePalette.Pulse,
    onSecondary = PulsePalette.Background,
    background = PulsePalette.Background,
    onBackground = PulsePalette.OnSurface,
    surface = PulsePalette.Surface,
    onSurface = PulsePalette.OnSurface,
    surfaceVariant = PulsePalette.SurfaceRaised,
    onSurfaceVariant = PulsePalette.OnSurfaceVariant,
    outline = PulsePalette.GridLine,
    outlineVariant = PulsePalette.GridLine,
    error = PulsePalette.Warning,
    onError = PulsePalette.Background,
)

@Composable
fun PulseKraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulseColors,
        typography = Typography(),
        content = content,
    )
}
