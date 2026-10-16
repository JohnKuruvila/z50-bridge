package io.github.johnkuruvila.z50bridge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import io.github.johnkuruvila.z50bridge.data.ThemeMode

val LocalSignals = staticCompositionLocalOf { DarkSignals }

/** Pipeline semantic colors: `MaterialTheme.signals.sync` etc. */
val MaterialTheme.signals: SignalColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSignals.current

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Z50BridgeTheme(
    themeMode: ThemeMode = ThemeMode.Brand,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        else -> systemDark
    }
    val colorScheme = when {
        themeMode == ThemeMode.Dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> BrandDarkColors
        else -> BrandLightColors
    }
    val reducedMotion = rememberReducedMotion()
    val motionScheme = if (reducedMotion) MotionScheme.standard() else MotionScheme.expressive()

    CompositionLocalProvider(
        LocalReducedMotion provides reducedMotion,
        LocalSignals provides if (dark) DarkSignals else LightSignals,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = motionScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
