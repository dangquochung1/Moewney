package app.meowney.core.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.navigation.NavBackStackEntry

/**
 * "Zoom out of the toy you tapped": a screen opened from a toy or button on the home screen
 * grows from that spot and shrinks back into it when closed.
 */
object Zoom {
    private const val MS = 320

    /** Set right before navigating; picked up by the next screen that enters. */
    private var pending: TransformOrigin? = null
    private val origins = HashMap<String, TransformOrigin?>()

    fun from(origin: TransformOrigin) {
        pending = origin
    }

    /** The zoom spot of each screen, fixed the first time it enters (null = slides instead). */
    private fun originOf(entry: NavBackStackEntry): TransformOrigin? {
        if (entry.id !in origins) {
            origins[entry.id] = pending
            pending = null
        }
        return origins[entry.id]
    }

    /** Enter: zoom from the marked spot if there is one, else slide in from the right. */
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val o = originOf(targetState)
        if (o != null) {
            scaleIn(tween(MS, easing = FastOutSlowInEasing), initialScale = 0.05f, transformOrigin = o) + fadeIn(tween(MS / 2))
        } else {
            slideInHorizontally(tween(MS)) { it / 5 } + fadeIn(tween(MS))
        }
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val o = origins[initialState.id]
        if (o != null) {
            scaleOut(tween(MS, easing = FastOutSlowInEasing), targetScale = 0.05f, transformOrigin = o) + fadeOut(tween(MS, delayMillis = MS / 2))
        } else {
            slideOutHorizontally(tween(MS)) { it / 5 } + fadeOut(tween(MS))
        }
    }

    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = { fadeOut(tween(MS)) }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = { fadeIn(tween(MS)) }
}

/** Remembers where this element is on screen; call [ZoomSource.mark] in its onClick. */
class ZoomSource {
    var origin: TransformOrigin = TransformOrigin.Center
    fun mark() = Zoom.from(origin)
}

@Composable
fun rememberZoomSource() = remember { ZoomSource() }

fun Modifier.zoomSource(source: ZoomSource): Modifier = onGloballyPositioned { c ->
    val root = c.findRootCoordinates().size
    val b = c.boundsInRoot()
    if (root.width > 0 && root.height > 0) {
        source.origin = TransformOrigin(b.center.x / root.width, b.center.y / root.height)
    }
}
