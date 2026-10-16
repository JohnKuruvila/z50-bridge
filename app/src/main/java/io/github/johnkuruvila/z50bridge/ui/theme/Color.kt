package io.github.johnkuruvila.z50bridge.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette: a graphite instrument cluster lit by signal amber. Neutrals are
// slightly cool so amber (sync) and cyan (offload) read as the only "lights".
private val Amber10 = Color(0xFF2B1600)
private val Amber20 = Color(0xFF472A00)
private val Amber30 = Color(0xFF663E00)
private val Amber40 = Color(0xFF875300)
private val Amber80 = Color(0xFFFFB866)
private val Amber90 = Color(0xFFFFDDB8)

private val Cyan10 = Color(0xFF001F25)
private val Cyan20 = Color(0xFF00363F)
private val Cyan30 = Color(0xFF004E5A)
private val Cyan40 = Color(0xFF006877)
private val Cyan80 = Color(0xFF7BD6E8)
private val Cyan90 = Color(0xFFA5EEFF)

private val Green10 = Color(0xFF00210E)
private val Green20 = Color(0xFF00391C)
private val Green30 = Color(0xFF00522B)
private val Green40 = Color(0xFF1C6B3F)
private val Green80 = Color(0xFF8FD8A4)
private val Green90 = Color(0xFFAAF5BF)

val BrandLightColors = lightColorScheme(
    primary = Amber40,
    onPrimary = Color.White,
    primaryContainer = Amber90,
    onPrimaryContainer = Amber10,
    secondary = Cyan40,
    onSecondary = Color.White,
    secondaryContainer = Cyan90,
    onSecondaryContainer = Cyan10,
    tertiary = Green40,
    onTertiary = Color.White,
    tertiaryContainer = Green90,
    onTertiaryContainer = Green10,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2E8),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Amber40,
    inverseSurface = Color(0xFF2E3136),
    inverseOnSurface = Color(0xFFEFF0F4),
    inversePrimary = Amber80,
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CE),
    surfaceBright = Color(0xFFF6F7F9),
    surfaceDim = Color(0xFFD7D9DD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F1F4),
    surfaceContainer = Color(0xFFEAEBEF),
    surfaceContainerHigh = Color(0xFFE4E6EA),
    surfaceContainerHighest = Color(0xFFDEE0E4),
)

val BrandDarkColors = darkColorScheme(
    primary = Amber80,
    onPrimary = Amber20,
    primaryContainer = Amber30,
    onPrimaryContainer = Amber90,
    secondary = Cyan80,
    onSecondary = Cyan20,
    secondaryContainer = Cyan30,
    onSecondaryContainer = Cyan90,
    tertiary = Green80,
    onTertiary = Green20,
    tertiaryContainer = Green30,
    onTertiaryContainer = Green90,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE3E5EA),
    surface = Color(0xFF0B0D10),
    onSurface = Color(0xFFE3E5EA),
    surfaceVariant = Color(0xFF2A2F37),
    onSurfaceVariant = Color(0xFFB9BEC8),
    surfaceTint = Amber80,
    inverseSurface = Color(0xFFE3E5EA),
    inverseOnSurface = Color(0xFF2D3035),
    inversePrimary = Amber40,
    outline = Color(0xFF878C96),
    outlineVariant = Color(0xFF3A3F47),
    surfaceBright = Color(0xFF30343B),
    surfaceDim = Color(0xFF0B0D10),
    surfaceContainerLowest = Color(0xFF060709),
    surfaceContainerLow = Color(0xFF111418),
    surfaceContainer = Color(0xFF161A1F),
    surfaceContainerHigh = Color(0xFF1D2127),
    surfaceContainerHighest = Color(0xFF252A31),
)

/**
 * Fixed semantic colors for the camera → phone → homelab pipeline. They do not
 * follow dynamic color, so "amber means sync, cyan means offload" holds on any
 * wallpaper.
 */
data class SignalColors(
    val sync: Color,
    val offload: Color,
    val online: Color,
    val offline: Color,
    val error: Color,
)

val LightSignals = SignalColors(
    sync = Amber40,
    offload = Cyan40,
    online = Green40,
    offline = Color(0xFF8A8F98),
    error = Color(0xFFB3261E),
)

val DarkSignals = SignalColors(
    sync = Amber80,
    offload = Cyan80,
    online = Green80,
    offline = Color(0xFF6B717B),
    error = Color(0xFFFFB4AB),
)
