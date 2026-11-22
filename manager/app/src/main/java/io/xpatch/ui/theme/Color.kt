package io.xpatch.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.xpatch.data.ThemeColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal val GlassHighlight = Color(0xFFFFFFFF)
internal val GlassShade = Color(0xFF000000)
internal val MiuixButtonSurface = Color(0xFFFFFFFF)
internal val SwitchTrackOff = Color(0xFFC7CBD1)
internal val SwitchTrackOn = Color(0xFF34C759)
internal val SwitchKnob = Color(0xFFFFFFFF)
internal val LogError = Color(0xFFB3261E)
internal val LogWarn = Color(0xFFB26A00)
internal val LogInfo = Color(0xFF2F80ED)
internal val LogDebug = Color(0xFF4F5B66)
internal val LogAll = Color(0xFF6B7280)

internal val MiuixLight = lightColorScheme(
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
    onSurfaceVariant = Color(0xFF6B7290),
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

internal val MiuixDark = darkColorScheme(
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
    onSurfaceVariant = Color(0xFF949AB2),
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

internal fun seedOf(color: ThemeColor): Color = when (color) {
    ThemeColor.GREEN -> Color(0xFF4CAF50)
    ThemeColor.TEAL -> Color(0xFF009688)
    ThemeColor.BLUE -> Color(0xFF2196F3)
    ThemeColor.INDIGO -> Color(0xFF3F51B5)
    ThemeColor.VIOLET -> Color(0xFF6750A4)
    ThemeColor.PINK -> Color(0xFFE91E63)
    ThemeColor.YELLOW -> Color(0xFFFFEB3B)
    ThemeColor.ORANGE -> Color(0xFFFF9800)
    ThemeColor.DEEP_ORANGE -> Color(0xFFFF5722)
}

internal fun schemeFromSeed(seed: Color, dark: Boolean): ColorScheme {
    val hsl = seed.toHsl()
    val hue = hsl.hue
    val chroma = hsl.saturation.coerceIn(0.18f, 0.92f)
    val neutralHue = hue
    if (dark) {
        return darkColorScheme(
            primary = tone(hue, chroma, 80f),
            onPrimary = tone(hue, chroma, 20f),
            primaryContainer = tone(hue, chroma, 30f),
            onPrimaryContainer = tone(hue, chroma, 90f),
            inversePrimary = tone(hue, chroma, 40f),
            secondary = tone(hue, chroma * 0.34f, 80f),
            onSecondary = tone(hue, chroma * 0.34f, 20f),
            secondaryContainer = tone(hue, chroma * 0.24f, 30f),
            onSecondaryContainer = tone(hue, chroma * 0.24f, 90f),
            tertiary = tone(hue + 60f, chroma * 0.5f, 80f),
            onTertiary = tone(hue + 60f, chroma * 0.5f, 20f),
            tertiaryContainer = tone(hue + 60f, chroma * 0.3f, 30f),
            onTertiaryContainer = tone(hue + 60f, chroma * 0.3f, 90f),
            background = tone(neutralHue, 0.03f, 6f),
            onBackground = tone(neutralHue, 0.04f, 90f),
            surface = tone(neutralHue, 0.03f, 6f),
            onSurface = tone(neutralHue, 0.04f, 90f),
            surfaceVariant = tone(neutralHue, 0.08f, 30f),
            onSurfaceVariant = tone(neutralHue, 0.1f, 80f),
            surfaceTint = tone(hue, chroma, 80f),
            surfaceContainerLowest = tone(neutralHue, 0.02f, 4f),
            surfaceContainerLow = tone(neutralHue, 0.03f, 10f),
            surfaceContainer = tone(neutralHue, 0.04f, 12f),
            surfaceContainerHigh = tone(neutralHue, 0.05f, 17f),
            surfaceContainerHighest = tone(neutralHue, 0.06f, 22f),
            surfaceDim = tone(neutralHue, 0.03f, 6f),
            surfaceBright = tone(neutralHue, 0.04f, 24f),
            outline = tone(neutralHue, 0.1f, 60f),
            outlineVariant = tone(neutralHue, 0.08f, 30f),
            inverseSurface = tone(neutralHue, 0.04f, 90f),
            inverseOnSurface = tone(neutralHue, 0.04f, 20f),
            scrim = Color(0xFF000000),
            error = Color(0xFFFFB4AB),
            onError = Color(0xFF690005),
            errorContainer = Color(0xFF93000A),
            onErrorContainer = Color(0xFFFFDAD6),
        )
    }
    return lightColorScheme(
        primary = tone(hue, chroma, 40f),
        onPrimary = tone(hue, chroma, 100f),
        primaryContainer = tone(hue, chroma * 0.55f, 90f),
        onPrimaryContainer = tone(hue, chroma, 10f),
        inversePrimary = tone(hue, chroma, 80f),
        secondary = tone(hue, chroma * 0.34f, 40f),
        onSecondary = tone(hue, chroma * 0.34f, 100f),
        secondaryContainer = tone(hue, chroma * 0.24f, 90f),
        onSecondaryContainer = tone(hue, chroma * 0.24f, 10f),
        tertiary = tone(hue + 60f, chroma * 0.5f, 40f),
        onTertiary = tone(hue + 60f, chroma * 0.5f, 100f),
        tertiaryContainer = tone(hue + 60f, chroma * 0.3f, 90f),
        onTertiaryContainer = tone(hue + 60f, chroma * 0.3f, 10f),
        background = tone(neutralHue, 0.03f, 98f),
        onBackground = tone(neutralHue, 0.05f, 10f),
        surface = tone(neutralHue, 0.03f, 98f),
        onSurface = tone(neutralHue, 0.05f, 10f),
        surfaceVariant = tone(neutralHue, 0.1f, 90f),
        onSurfaceVariant = tone(neutralHue, 0.12f, 30f),
        surfaceTint = tone(hue, chroma, 40f),
        surfaceContainerLowest = tone(neutralHue, 0.02f, 100f),
        surfaceContainerLow = tone(neutralHue, 0.03f, 96f),
        surfaceContainer = tone(neutralHue, 0.045f, 94f),
        surfaceContainerHigh = tone(neutralHue, 0.06f, 92f),
        surfaceContainerHighest = tone(neutralHue, 0.075f, 90f),
        surfaceDim = tone(neutralHue, 0.04f, 87f),
        surfaceBright = tone(neutralHue, 0.03f, 98f),
        outline = tone(neutralHue, 0.1f, 50f),
        outlineVariant = tone(neutralHue, 0.12f, 80f),
        inverseSurface = tone(neutralHue, 0.05f, 20f),
        inverseOnSurface = tone(neutralHue, 0.04f, 95f),
        scrim = Color(0xFF000000),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
    )
}

internal fun ColorScheme.withOledDark(): ColorScheme = copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF000000),
    surfaceContainer = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFF0A0A0A),
    surfaceContainerHighest = Color(0xFF101010),
    onSurface = Color(0xFFFFFFFF),
)

private data class Hsl(val hue: Float, val saturation: Float, val lightness: Float)

private fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val lightness = (high + low) / 2f
    if (high == low) return Hsl(0f, 0f, lightness)
    val delta = high - low
    val saturation = if (lightness > 0.5f) {
        delta / (2f - high - low)
    } else {
        delta / (high + low)
    }
    val hue = when (high) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } * 60f
    return Hsl(hue, saturation, lightness)
}

private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r, g, b) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(r + m, g + m, b + m, 1f)
}

private fun tone(hue: Float, chroma: Float, tone: Float): Color {
    val target = toneLuminance(tone)
    var low = 0f
    var high = 1f
    repeat(22) {
        val mid = (low + high) / 2f
        if (hsl(hue, chroma, mid).luminance() < target) low = mid else high = mid
    }
    return hsl(hue, chroma, (low + high) / 2f)
}

private fun toneLuminance(tone: Float): Float {
    val l = tone.coerceIn(0f, 100f)
    if (l <= 8f) return l / 903.3f
    val f = (l + 16f) / 116f
    return f * f * f
}