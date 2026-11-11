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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.davort.airbuzz.R
import com.davort.airbuzz.rules.Settings
import com.davort.airbuzz.rules.ThemeMode

/** A named accent swatch for the Appearance screen. */
data class Accent(val name: String, val color: Color)

/** The approved palettes: five accents per mode, each readable on its own tint (at least 4.5:1). */
val LightAccents = listOf(
    Accent("Orange", Color(0xFFB5350E)),
    Accent("Blue", Color(0xFF2B56CF)),
    Accent("Green", Color(0xFF176B41)),
    Accent("Magenta", Color(0xFFB0215F)),
    Accent("Teal", Color(0xFF0A6A63)),
)
val DarkAccents = listOf(
    Accent("Turquoise", Color(0xFF3FD9C9)),
    Accent("Orange", Color(0xFFFF7A50)),
    Accent("Blue", Color(0xFF82AEFF)),
    Accent("Lime", Color(0xFFB9E15C)),
    Accent("Pink", Color(0xFFFF85BD)),
)

/** Colours outside Material's scheme: the blank tint, status dots and the buzz strip's quiet marks. */
@Immutable
data class AirColors(
    val dark: Boolean,
    val accent: Color,
    val tint: Color,
    val tintStrong: Color,
    val ok: Color,
    val warn: Color,
    val quietMark: Color,
    val line: Color,
    val muted: Color,
)

val LocalAirColors = staticCompositionLocalOf { airColors(false, LightAccents[0].color) }

fun airColors(dark: Boolean, accent: Color): AirColors {
    val surface = if (dark) Color(0xFF191919) else Color.White
    return AirColors(
        dark = dark,
        accent = accent,
        tint = accent.copy(alpha = if (dark) 0.16f else 0.10f).compositeOver(surface),
        tintStrong = accent.copy(alpha = if (dark) 0.28f else 0.20f).compositeOver(surface),
        ok = if (dark) Color(0xFF5CC88A) else Color(0xFF2E8B57),
        warn = if (dark) Color(0xFFF0A43A) else Color(0xFFB86E00),
        quietMark = if (dark) Color(0xFF3E3E3C) else Color(0xFFC9C8C3),
        line = if (dark) Color(0xFF2B2B2A) else Color(0xFFE2E1DD),
        muted = if (dark) Color(0xFF9B9B97) else Color(0xFF6A6A67),
    )
}

@OptIn(ExperimentalTextApi::class)
private fun flex(weight: Int) = Font(R.font.roboto_flex, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

@OptIn(ExperimentalTextApi::class)
private fun mono(weight: Int) = Font(R.font.roboto_mono, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Flex = FontFamily(flex(400), flex(500), flex(600))
val Mono = FontFamily(mono(400), mono(500))

/** Small uppercase mono label with tabular numbers, as in the mockup. */
val MonoLabel = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.06.em, fontFeatureSettings = "tnum")

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.f() = copy(fontFamily = Flex, fontFeatureSettings = "tnum")
    return Typography(
        displayLarge = base.displayLarge.f(), displayMedium = base.displayMedium.f(), displaySmall = base.displaySmall.f(),
        headlineLarge = base.headlineLarge.f(), headlineMedium = base.headlineMedium.f(), headlineSmall = base.headlineSmall.f(),
        titleLarge = base.titleLarge.f().copy(fontWeight = FontWeight(500)), titleMedium = base.titleMedium.f(), titleSmall = base.titleSmall.f(),
        bodyLarge = base.bodyLarge.f(), bodyMedium = base.bodyMedium.f(), bodySmall = base.bodySmall.f(),
        labelLarge = base.labelLarge.f(), labelMedium = base.labelMedium.f(), labelSmall = base.labelSmall.f(),
    )
}

fun isDark(settings: Settings, system: Boolean) = when (settings.theme) {
    ThemeMode.SYSTEM -> system
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Two hand-tuned palettes, not Android's dynamic colour; the chosen accent replaces the primary colour. */
@Composable
fun AirBuzzTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = isDark(settings, isSystemInDarkTheme())
    val accent = if (dark) DarkAccents.getOrElse(settings.darkAccent) { DarkAccents[0] }.color
        else LightAccents.getOrElse(settings.lightAccent) { LightAccents[0] }.color
    val air = airColors(dark, accent)
    val scheme = if (dark) darkColorScheme(
        primary = accent, onPrimary = Color(0xFF0E0E0E), primaryContainer = air.tint, onPrimaryContainer = accent,
        secondary = accent, onSecondary = Color(0xFF0E0E0E), secondaryContainer = air.tintStrong, onSecondaryContainer = Color(0xFFECECEA),
        background = Color(0xFF0E0E0E), onBackground = Color(0xFFECECEA),
        surface = Color(0xFF0E0E0E), onSurface = Color(0xFFECECEA), surfaceVariant = Color(0xFF191919), onSurfaceVariant = air.muted,
        surfaceContainerLowest = Color(0xFF191919), surfaceContainerLow = Color(0xFF191919), surfaceContainer = Color(0xFF191919),
        surfaceContainerHigh = Color(0xFF222221), surfaceContainerHighest = Color(0xFF2B2B2A),
        outline = air.muted, outlineVariant = air.line, error = Color(0xFFFF8A80),
    ) else lightColorScheme(
        primary = accent, onPrimary = Color.White, primaryContainer = air.tint, onPrimaryContainer = accent,
        secondary = accent, onSecondary = Color.White, secondaryContainer = air.tintStrong, onSecondaryContainer = Color(0xFF141414),
        background = Color(0xFFF3F3F1), onBackground = Color(0xFF141414),
        surface = Color(0xFFF3F3F1), onSurface = Color(0xFF141414), surfaceVariant = Color.White, onSurfaceVariant = air.muted,
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF8F8F6), surfaceContainerHighest = Color(0xFFEDEDEA),
        outline = air.muted, outlineVariant = air.line, error = Color(0xFFB3261E),
    )
    CompositionLocalProvider(LocalAirColors provides air) {
        MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
    }
}
