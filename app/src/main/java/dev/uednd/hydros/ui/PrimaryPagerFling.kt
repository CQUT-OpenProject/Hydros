package dev.uednd.hydros.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
internal fun rememberPrimaryPagerFlingBehavior(state: PagerState, reduceMotion: Boolean): TargetedFlingBehavior {
    val delegate = PagerDefaults.flingBehavior(state, snapAnimationSpec = tween(if (reduceMotion) 0 else 250))
    return remember(state, delegate) {
        object : TargetedFlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float {
                // A vertical child's edge fling also visits this horizontal scroll parent.
                // Do not hold up its overscroll absorption with a zero-distance snap tween.
                if (initialVelocity == 0f && state.currentPageOffsetFraction == 0f) {
                    onRemainingDistanceUpdated(0f)
                    return 0f
                }
                return with(delegate) { performFling(initialVelocity, onRemainingDistanceUpdated) }
            }
        }
    }
}
