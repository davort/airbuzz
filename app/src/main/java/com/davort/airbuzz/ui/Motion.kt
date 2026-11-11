package com.davort.airbuzz.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Motion from the motion study (brief decision 40). Compose stretches every duration by Android's animator duration
 * scale, so "Remove animations" in Android's settings turns all of it into cuts.
 */
object Motion {
    /** Enters and exits: a fast start that settles. */
    val Out = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
    /** Things already on screen that move or resize. */
    val InOut = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)
    /** Leaving: a quiet, quick start. */
    val In = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    /** Material's emphasized curve, for the shelf growing into Settings. */
    val Emph = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * The shelf opening Settings is one linear timeline of this length: the panel grows over its first 65 %, and the
     * layers of Settings rise in staggered over it (see [parallax]). Back plays the same timeline in reverse, faster.
     */
    const val EXPAND = 460
    const val EXPAND_BACK = 300
}

/**
 * One layer of Settings in the shelf's reveal (decision 40). [t] is the shared timeline, 0 → 1. Layer 0 is the lowest on
 * screen and starts first, near the shelf; each layer above starts 6 % later and travels 8 dp further, so the scene
 * opens upward like parallax and the title, the top layer, rises 72 dp out from behind the Strength card.
 */
fun Modifier.parallax(t: Float, layer: Int): Modifier = graphicsLayer {
    val k = Motion.Out.transform(((t - 0.1f - layer * 0.06f) / 0.4f).coerceIn(0f, 1f))
    alpha = k
    translationY = (1f - k) * (24 + layer * 8).dp.toPx()
}
