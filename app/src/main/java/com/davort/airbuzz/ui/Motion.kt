package com.davort.airbuzz.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

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
    /** Material's emphasized curve: a quick start with a long, soft landing, for pages sliding in. */
    val Emph = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Going to a deeper page, and coming back from it (see [pageChange]). */
    const val PAGE = 360
    const val PAGE_BACK = 300
    /** Settings and the pickers rising as sheets, and sinking back (see [SheetLayer]). */
    const val SHEET = 300
    const val SHEET_BACK = 220
}

/**
 * Every move between pages, inside the Settings sheet as well (decision 40), measured from Linear's app. The page you go to starts 40 % of the width to
 * the right and is already fading in, so you see only the end of its slide; the page you leave drifts 15 % the other
 * way and is gone sooner. The two moving apart at different speeds gives the sense of depth. Back mirrors it, a
 * little faster.
 */
fun pageChange(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(if (forward) Motion.PAGE else Motion.PAGE_BACK, easing = Motion.Emph)
    val front = 0.4f
    val behind = -0.15f
    return ContentTransform(
        slideInHorizontally(slide) { (it * if (forward) front else behind).roundToInt() } + fadeIn(tween(150, easing = Motion.Out)),
        slideOutHorizontally(slide) { (it * if (forward) behind else front).roundToInt() } + fadeOut(tween(if (forward) 180 else 120, easing = Motion.Out)),
        sizeTransform = null,
    )
}
