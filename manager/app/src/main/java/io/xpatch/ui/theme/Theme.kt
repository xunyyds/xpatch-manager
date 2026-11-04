package io.xpatch.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.xpatch.data.SettingsStore
import io.xpatch.data.UiStyle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

val LocalMiuix = staticCompositionLocalOf { false }

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF38618C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3F8),
    onPrimaryContainer = Color(0xFF001D34),
    secondary = Color(0xFF7A5C00),
    secondaryContainer = Color(0xFFFFDF9B),
    background = Color(0xFFF8F9FC),
    surface = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFE1E2EC),
    surfaceContainer = Color(0xFFEDEEF3),
)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA2C9F5),
    onPrimary = Color(0xFF003353),
    primaryContainer = Color(0xFF1F4875),
    onPrimaryContainer = Color(0xFFD3E3F8),
    secondary = Color(0xFFE9C46A),
    secondaryContainer = Color(0xFF5C4300),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474E),
    surfaceContainer = Color(0xFF1D2024),
)

private val MiuixLight = lightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EEFF),
    onPrimaryContainer = Color(0xFF0B4FA8),
    secondary = Color(0xFF6E7A8A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF2F6),
    onSecondaryContainer = Color(0xFF313A45),
    tertiary = Color(0xFF3482FF),
    tertiaryContainer = Color(0xFFE4EEFF),
    background = Color(0xFFF2F3F5),
    onBackground = Color(0xFF0F1114),
    surface = Color.White,
    onSurface = Color(0xFF0F1114),
    surfaceVariant = Color(0xFFEFF2F6),
    onSurfaceVariant = Color(0xFF8C93B0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF2F3F5),
    surfaceContainerHighest = Color(0xFFE7EAEE),
    outline = Color(0xFFD5D9DF),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFE03B3B),
    onError = Color.White,
    errorContainer = Color(0xFFFFE7E7),
    onErrorContainer = Color(0xFF8C1D18),
)

private val MiuixDark = darkColorScheme(
    primary = Color(0xFF277AF7),
    onPrimary = Color(0xFF06203F),
    primaryContainer = Color(0xFF1C3A63),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFF9AA6B5),
    onSecondary = Color(0xFF14171B),
    secondaryContainer = Color(0xFF24262A),
    onSecondaryContainer = Color(0xFFDDE2E9),
    tertiary = Color(0xFF277AF7),
    tertiaryContainer = Color(0xFF1C3A63),
    background = Color(0xFF242424),
    onBackground = Color(0xFFEDEFF2),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFEDEFF2),
    surfaceVariant = Color(0xFF24262A),
    onSurfaceVariant = Color(0xFF787E96),
    surfaceContainerLowest = Color(0xFF1B1B1B),
    surfaceContainerLow = Color(0xFF1F1F1F),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceContainerHighest = Color(0xFF333333),
    outline = Color(0xFF3A3F45),
    outlineVariant = Color(0xFF393939),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0908),
    errorContainer = Color(0xFF5C1A17),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun XPatchTheme(
    themeMode: Int = SettingsStore.THEME_SYSTEM,
    uiStyle: UiStyle = UiStyle.MATERIAL3,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        SettingsStore.THEME_LIGHT -> false
        SettingsStore.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val miuix = uiStyle == UiStyle.MIUIX
    val context = LocalContext.current
    val colors = when {
        miuix -> if (dark) MiuixDark else MiuixLight
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> FallbackDark
        else -> FallbackLight
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    if (miuix) {
        val miuixColors = remember(dark) {
            if (dark) {
                top.yukonga.miuix.kmp.theme.darkColorScheme(
                    background = Color(0xFF1B1B1B),
                    surface = Color(0xFF1B1B1B),
                    surfaceContainer = Color(0xFF242424),
                    surfaceContainerHigh = Color(0xFF2D2D2D),
                    surfaceContainerHighest = Color(0xFF333333),
                )
            } else {
                top.yukonga.miuix.kmp.theme.lightColorScheme(
                    background = Color(0xFFF2F3F5),
                    surface = Color(0xFFF2F3F5),
                    surfaceVariant = Color(0xFFFFFFFF),
                    surfaceContainer = Color(0xFFFFFFFF),
                    surfaceContainerHigh = Color(0xFFF2F3F5),
                    surfaceContainerHighest = Color(0xFFE8E8E8),
                )
            }
        }
        MiuixTheme(colors = miuixColors) {
            CompositionLocalProvider(LocalMiuix provides true) {
                MaterialTheme(
                    colorScheme = colors,
                    shapes = MiuixShapes,
                    content = content,
                )
            }
        }
    } else {
        CompositionLocalProvider(LocalMiuix provides false) {
            MaterialTheme(
                colorScheme = colors,
                content = content,
            )
        }
    }
}