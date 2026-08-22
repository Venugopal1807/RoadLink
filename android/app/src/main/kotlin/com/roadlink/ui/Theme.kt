package com.roadlink.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A deliberately restrained palette.
 *
 * Colour carries meaning in this app rather than decoration: it is how a
 * responder reads delivery state at a glance, and how anyone looking at the
 * screen can tell a simulated event from a real one without reading text.
 * Those two jobs are the only reasons a colour exists here.
 */

val RlRed = Color(0xFFE5484D)        // confirmed emergency, needs attention
val RlAmber = Color(0xFFF5A524)      // queued, waiting
val RlOrange = Color(0xFFF76B15)     // retrying after a failure
val RlBlue = Color(0xFF3B82F6)       // actively being delivered
val RlGreen = Color(0xFF30A46C)      // delivered, backend has it
val RlViolet = Color(0xFF8E4EC6)     // SIMULATED marker - never used for anything else
val RlSlate = Color(0xFF8B98A5)      // secondary text

private val DarkColors = darkColorScheme(
    primary = RlRed,
    onPrimary = Color.White,
    secondary = RlBlue,
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF11161D),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF1A222C),
    onSurfaceVariant = Color(0xFFB6C2CF),
    error = RlRed,
)

private val LightColors = lightColorScheme(
    primary = RlRed,
    onPrimary = Color.White,
    secondary = RlBlue,
    background = Color(0xFFF7F9FB),
    onBackground = Color(0xFF10161D),
    surface = Color.White,
    onSurface = Color(0xFF10161D),
    surfaceVariant = Color(0xFFE8EDF2),
    onSurfaceVariant = Color(0xFF44515F),
    error = RlRed,
)

@Composable
fun RoadLinkTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
