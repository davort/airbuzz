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

    /** The shelf grows into Settings (Expand) in this long, then Settings fades in over [FILL]. */
    const val EXPAND = 450
    const val FILL = 200
    /** Back: Settings fades out over [FILL_BACK], then the panel shrinks into the shelf. */
    const val FILL_BACK = 120
    const val EXPAND_BACK = 300
}
