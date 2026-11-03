package pk.qist.device.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF0B1F2A)
private val Teal = Color(0xFF0B6E6E)
private val Mint = Color(0xFF5FD0C8)
private val Amber = Color(0xFFFFB020)
private val Paper = Color(0xFFF5F8F8)
private val InkRaised = Color(0xFF14303D)

/** Teal on paper for the light theme. Amber is only ever a background behind Ink text, for contrast. */
val QistLightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = Ink,
    tertiaryContainer = Amber,
    onTertiaryContainer = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink
)

/** Mint on ink for the dark theme. */
val QistDarkColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    primaryContainer = Teal,
    onPrimaryContainer = Color.White,
    tertiaryContainer = Amber,
    onTertiaryContainer = Ink,
    background = Ink,
    onBackground = Color.White,
    surface = InkRaised,
    onSurface = Color.White
)
