package com.davort.airbuzz.ui

import androidx.compose.ui.Modifier
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibilityScope
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

/**
 * Home to Activity and back (Davor, 15 Nov): the day's graph itself moves to its place on the other page (see
 * [todayLine]), so the pages around it only fade, drifting 24 dp. The page you leave goes quickly; the one you go to
 * comes in once the graph is on its way.
 */
fun graphPageChange(forward: Boolean): ContentTransform {
    val drift = 24
    return if (forward) ContentTransform(
        fadeIn(tween(240, 100, Motion.Out)) + slideInHorizontally(tween(240, 100, Motion.Out)) { drift },
        fadeOut(tween(120, easing = Motion.In)) + slideOutHorizontally(tween(120, easing = Motion.In)) { -drift },
        sizeTransform = null,
    ) else ContentTransform(
        fadeIn(tween(220, 60, Motion.Out)) + slideInHorizontally(tween(220, 60, Motion.Out)) { -drift },
        fadeOut(tween(140, easing = Motion.In)) + slideOutHorizontally(tween(140, easing = Motion.In)) { drift },
        sizeTransform = null,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
class PageShare(val shared: SharedTransitionScope, val page: AnimatedVisibilityScope, val forward: Boolean)

/** Set around Home and Activity, so their day graphs can find each other. */
val LocalPageShare = staticCompositionLocalOf<PageShare?> { null }

/** The day's graph on Home and on Activity's today: it moves between them, 320 ms going in and 260 ms coming back. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.todayLine(): Modifier {
    val s = LocalPageShare.current ?: return this
    return with(s.shared) {
        this@todayLine.sharedElement(rememberSharedContentState("today line"), s.page,
            boundsTransform = { _, _ -> tween(if (s.forward) 320 else 260, easing = Motion.Out) })
    }
}
