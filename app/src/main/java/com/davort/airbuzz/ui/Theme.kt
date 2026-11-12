package com.davort.airbuzz.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.davort.airbuzz.R
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.rules.ThemeMode

/** A named accent swatch for the Appearance screen. */
data class Accent(val name: String, val color: Color)

/**
 * The approved swatches: five per mode, each keeping blank text at least 4.5:1 on its tint. The first is the default
 * (Green and Lime, decision 44). Settings stores the index, so the default swapped places with Orange and Turquoise.
 */
val LightAccents = listOf(
    Accent("Green", Color(0xFF176B41)),
    Accent("Blue", Color(0xFF2B56CF)),
    Accent("Orange", Color(0xFFB5350E)),
    Accent("Magenta", Color(0xFFB0215F)),
    Accent("Teal", Color(0xFF0A6A63)),
)
val DarkAccents = listOf(
    Accent("Lime", Color(0xFFB9E15C)),
    Accent("Orange", Color(0xFFFF7A50)),
    Accent("Blue", Color(0xFF82AEFF)),
    Accent("Turquoise", Color(0xFF3FD9C9)),
    Accent("Pink", Color(0xFFFF85BD)),
)

/** The design tokens (brief decisions 28 and 30): two cool grey palettes with the chosen accent. */
@Immutable
data class AirColors(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val ink: Color,
    val heading: Color,
    val muted: Color,
    val line: Color,
    val outline: Color,
    val selected: Color,
    /** Ignored marks, the strap, the sheet handle. */
    val context: Color,
    val onInk: Color,
    val ok: Color,
    val warn: Color,
    /** The circle behind a warning icon on the Air shelf. */
    val warnTint: Color,
    val accent: Color,
    /** A blank at rest: the accent at 10% (light) or 16% (dark) over the surface. */
    val tint: Color,
    /** A pressed or open blank: 18% or 24%, the most that keeps blank text at 4.5:1 for every swatch. */
    val tintPressed: Color,
)

val LocalAirColors = staticCompositionLocalOf { airColors(false, LightAccents[0].color) }

val Pebble = Color(0xFF141414)

/**
 * Each grey takes a hint of the accent (decision 44): its OKLCH lightness stays, and it gains the accent's hue at a
 * small chroma, [HINT] times the role's weight. White cards in light mode stay white, so they keep their lift.
 */
private const val HINT_LIGHT = 0.007f
private const val HINT_DARK = 0.008f

private fun Color.hint(accent: Color, chroma: Float): Color {
    val a = accent.convert(ColorSpaces.Oklab)
    val hue = atan2(a.blue, a.green)
    val l = convert(ColorSpaces.Oklab).red
    return Color(l, chroma * cos(hue), chroma * sin(hue), 1f, ColorSpaces.Oklab).convert(ColorSpaces.Srgb)
}

fun airColors(dark: Boolean, accent: Color): AirColors {
    val k = if (dark) HINT_DARK else HINT_LIGHT
    fun Color.h(weight: Float) = hint(accent, k * weight)
    val surface = if (dark) Color(0xFF18191B).h(0.45f) else Color.White
    return if (dark) AirColors(
        dark = true, bg = Color(0xFF0E0F10).h(1f), surface = surface, ink = Color(0xFFECEDF0).h(0.35f), heading = Color.White.h(0.2f),
        muted = Color(0xFF9A9DA3).h(0.6f), line = Color(0xFF2A2B2E).h(0.9f), outline = Color(0xFF595C61).h(0.7f), selected = Color(0xFF313236).h(0.9f),
        context = Color(0xFF3D3F43).h(0.8f), onInk = Color(0xFF0E0F10).h(1f), ok = Color(0xFF5CC88A), warn = Color(0xFFF0A43A), warnTint = Color(0xFF3F3221),
        accent = accent, tint = accent.copy(alpha = 0.16f).compositeOver(surface), tintPressed = accent.copy(alpha = 0.24f).compositeOver(surface),
    ) else AirColors(
        dark = false, bg = Color(0xFFF1F2F4).h(1f), surface = surface, ink = Color(0xFF121418).h(0.35f), heading = Color(0xFF05070A).h(0.2f),
        muted = Color(0xFF62666E).h(0.6f), line = Color(0xFFDFE1E5).h(0.9f), outline = Color(0xFFAFB3BA).h(0.7f), selected = Color(0xFFE4E6EA).h(0.9f),
        context = Color(0xFFC6C9CF).h(0.8f), onInk = Color.White, ok = Color(0xFF2E8B57), warn = Color(0xFFA35E00), warnTint = Color(0xFFF6EEE0),
        accent = accent, tint = accent.copy(alpha = 0.10f).compositeOver(surface), tintPressed = accent.copy(alpha = 0.18f).compositeOver(surface),
    )
}

// Fonts (decision 29), all under the SIL Open Font License.

@OptIn(ExperimentalTextApi::class)
private fun sans(weight: Int) = Font(R.font.google_sans_flex, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private fun code(weight: Int) = Font(R.font.google_sans_code, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Sans = FontFamily(sans(400), sans(500), sans(560))
val Code = FontFamily(code(400), code(500))
val Serif = FontFamily(Font(R.font.instrument_serif))
val Pixel = FontFamily(Font(R.font.geist_pixel))

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Int, line: Int, weight: Int = 400) =
    TextStyle(fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight), lineHeightStyle = Trim)

/** The type scale from the design pass, in sp on a 360 dp phone. */
object Type {
    val heading = style(Serif, 34, 40)
    val title = style(Sans, 24, 32, 500)
    val sentence = style(Sans, 20, 34)
    val body = style(Sans, 16, 24)
    val bodyMedium = style(Sans, 16, 24, 500)
    val small = style(Sans, 14, 20)
    val smallMedium = style(Sans, 14, 20, 500)
    /** Small uppercase labels, times and small numbers, with tabular figures. */
    val label = style(Code, 12, 16).copy(letterSpacing = 0.06.em, fontFeatureSettings = "tnum")
    fun number(size: Int, line: Int) = style(Pixel, size, line)
    /** The On / Paused / Off word next to the Home switch: a readout, in the big numbers' pixel face. */
    val status = style(Pixel, 24, 28)
}

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.s() = copy(fontFamily = Sans, fontFeatureSettings = "tnum")
    return Typography(
        displayLarge = base.displayLarge.s(), displayMedium = base.displayMedium.s(), displaySmall = base.displaySmall.s(),
        headlineLarge = base.headlineLarge.s(), headlineMedium = base.headlineMedium.s(), headlineSmall = base.headlineSmall.s(),
        titleLarge = Type.title, titleMedium = Type.bodyMedium, titleSmall = Type.smallMedium,
        bodyLarge = Type.body, bodyMedium = Type.small, bodySmall = Type.small.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = Type.smallMedium, labelMedium = base.labelMedium.s(), labelSmall = base.labelSmall.s(),
    )
}

fun isDark(settings: Settings, system: Boolean) = when (settings.theme) {
    ThemeMode.SYSTEM -> system
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

fun accentOf(settings: Settings, dark: Boolean): Color =
    if (dark) DarkAccents.getOrElse(settings.darkAccent) { DarkAccents[0] }.color
    else LightAccents.getOrElse(settings.lightAccent) { LightAccents[0] }.color

/** Two hand-tuned palettes, not Android's dynamic colour; the chosen accent replaces the primary colour. */
@Composable
fun AirBuzzTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDark(settings, isSystemInDarkTheme())
    val a = airColors(dark, accentOf(settings, dark))
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = a.accent, onPrimary = a.onInk, primaryContainer = a.tint, onPrimaryContainer = a.accent,
        secondary = a.ink, onSecondary = a.onInk, secondaryContainer = a.selected, onSecondaryContainer = a.ink,
        background = a.bg, onBackground = a.ink, surface = a.bg, onSurface = a.ink, surfaceVariant = a.surface, onSurfaceVariant = a.muted,
        surfaceContainerLowest = a.surface, surfaceContainerLow = a.surface, surfaceContainer = a.surface,
        surfaceContainerHigh = a.surface, surfaceContainerHighest = a.selected, surfaceTint = Color.Transparent,
        outline = a.outline, outlineVariant = a.line, error = a.warn, scrim = Color.Black,
    )
    CompositionLocalProvider(LocalAirColors provides a) {
        MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
    }
}
