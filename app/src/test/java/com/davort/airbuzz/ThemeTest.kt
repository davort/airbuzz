package com.davort.airbuzz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.davort.airbuzz.ui.DarkAccents
import com.davort.airbuzz.ui.LightAccents
import com.davort.airbuzz.ui.airColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every swatch, with the greys tinted toward it, keeps the brief's 4.5:1 for blank text and grey text. */
class ThemeTest {
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun everySwatchKeepsContrast() {
        for ((dark, accents) in listOf(false to LightAccents, true to DarkAccents)) for (acc in accents) {
            val c = airColors(dark, acc.color)
            val where = "${acc.name} in ${if (dark) "dark" else "light"}"
            assertTrue("pressed blank, $where: ${ratio(c.accent, c.tintPressed)}", ratio(c.accent, c.tintPressed) >= 4.5)
            assertTrue("grey text, $where: ${ratio(c.muted, c.bg)}", ratio(c.muted, c.bg) >= 4.5)
            assertTrue("grey text on cards, $where: ${ratio(c.muted, c.surface)}", ratio(c.muted, c.surface) >= 4.5)
        }
    }
}
