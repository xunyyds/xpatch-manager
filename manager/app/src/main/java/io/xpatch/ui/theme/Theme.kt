package io.xpatch.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import io.xpatch.data.SettingsStore
import io.xpatch.data.ThemeColor
import io.xpatch.data.UiStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalMiuix = staticCompositionLocalOf { false }

@Composable
fun XPatchTheme(
    themeMode: Int = SettingsStore.THEME_SYSTEM,
    uiStyle: UiStyle = UiStyle.MATERIAL3,
    themeColor: ThemeColor = ThemeColor.GREEN,
    dynamicColor: Boolean = true,
    oledDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        SettingsStore.THEME_LIGHT -> false
        SettingsStore.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val miuix = uiStyle == UiStyle.MIUIX
    val context = LocalContext.current
    val animationQuality = rememberAnimationQuality()
    val colors = remember(context, dark, miuix, themeColor, dynamicColor, oledDark) {
        if (miuix) {
            if (dark) MiuixDark else MiuixLight
        } else {
            val scheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                schemeFromSeed(seedOf(themeColor), dark)
            }
            if (dark && oledDark) scheme.withOledDark() else scheme
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            window.isNavigationBarContrastEnforced = false
        }
    }

    CompositionLocalProvider(LocalAnimationQuality provides animationQuality) {
        if (miuix) {
            val miuixColors = remember(dark) {
                if (dark) {
                    top.yukonga.miuix.kmp.theme.darkColorScheme(
                        background = Color(0xFF1B1B1B),
                        surface = Color(0xFF1B1B1B),
                        surfaceContainer = Color(0xFF242424),
                        surfaceContainerHigh = Color(0xFF2D2D2D),
                        surfaceContainerHighest = Color(0xFF333333),
                        onSurfaceVariantSummary = Color(0xFF949AB2),
                    )
                } else {
                    top.yukonga.miuix.kmp.theme.lightColorScheme(
                        background = Color(0xFFF2F3F5),
                        surface = Color(0xFFF2F3F5),
                        surfaceVariant = Color(0xFFFFFFFF),
                        surfaceContainer = Color(0xFFFFFFFF),
                        surfaceContainerHigh = Color(0xFFF2F3F5),
                        surfaceContainerHighest = Color(0xFFE8E8E8),
                        onSurfaceVariantSummary = Color(0xFF6B7290),
                    )
                }
            }
            MiuixTheme(colors = miuixColors) {
                CompositionLocalProvider(LocalMiuix provides true) {
                    MaterialTheme(
                        colorScheme = colors,
                        typography = XpTypography,
                        shapes = XpShapes,
                        content = content,
                    )
                }
            }
        } else {
            CompositionLocalProvider(LocalMiuix provides false) {
                MaterialTheme(
                    colorScheme = colors,
                    typography = XpTypography,
                    shapes = XpShapes,
                    content = content,
                )
            }
        }
    }
}