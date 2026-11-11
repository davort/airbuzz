package com.davort.airbuzz.ui

import androidx.compose.animation.core.CubicBezierEasing

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

    /** The shelf grows into Settings (Expand) in this long, then Settings' title rises into place over [TITLE]. */
    const val EXPAND = 380
    const val TITLE = 480
    /** Back: the title sinks behind the Strength card over [TITLE_BACK], then the panel shrinks into the shelf. */
    const val TITLE_BACK = 220
    const val EXPAND_BACK = 260
}
