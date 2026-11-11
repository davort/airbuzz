package com.davort.airbuzz.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
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

    /** Shelf to Settings (Anchor), and back at about 60 %. */
    const val ANCHOR = 260
    const val ANCHOR_BACK = 160
}

/**
 * Settings rising in above the shelf (Anchor): each block fades in and rises 8 dp, the one nearest the shelf first
 * ([order] 0), 35 ms apart. With [play] false it shows at once, as when coming back from an inner screen.
 */
@Composable
fun Rise(order: Int, play: Boolean, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(!play).apply { targetState = true } }
    val rise = with(LocalDensity.current) { 8.dp.roundToPx() }
    val delay = Motion.ANCHOR * 15 / 100 + order * 35
    AnimatedVisibility(
        state,
        enter = fadeIn(tween(Motion.ANCHOR, delay, Motion.Out)) + slideInVertically(tween(Motion.ANCHOR, delay, Motion.Out)) { rise },
        exit = ExitTransition.None,
    ) { content() }
}
