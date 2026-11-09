package app.meowney.feature.room

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * The round window onto the cat's corner, in scene pixels.
 *
 * The cat is checked with a body box ([BODY_W] x [BODY_H], feet at the bottom centre)
 * rather than its sprite cell, which has transparent padding. The set of feet positions
 * whose box fits is convex, so walking in a straight line between two valid points
 * never leaves the circle.
 */
data class StageCircle(val cx: Float, val cy: Float, val r: Float) {
    val diameter: Int get() = (2 * r).toInt()

    fun fits(x: Float, y: Float, w: Float = BODY_W, h: Float = BODY_H): Boolean {
        val left = x - w / 2
        val right = x + w / 2
        val top = y - h
        return inside(left, top) && inside(right, top) && inside(left, y) && inside(right, y)
    }

    private fun inside(px: Float, py: Float) = hypot(px - cx, py - cy) <= r - MARGIN

    /** [x], [y] if the box fits there, else the nearest point towards the centre where it does. */
    fun clamp(x: Float, y: Float, w: Float = BODY_W, h: Float = BODY_H): Pair<Float, Float> {
        if (fits(x, y, w, h)) return x to y
        val tx = cx
        val ty = cy + h / 2 // feet position that centres the box
        if (!fits(tx, ty, w, h)) return tx to ty
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2
            if (fits(x + (tx - x) * mid, y + (ty - y) * mid, w, h)) hi = mid else lo = mid
        }
        // Step a little further in so the point sits strictly inside, not on the edge,
        // where float rounding while walking could tip it out.
        hi = minOf(1f, hi + INSET)
        return (x + (tx - x) * hi) to (y + (ty - y) * hi)
    }

    companion object {
        const val BODY_W = 20f
        const val BODY_H = 24f
        private const val MARGIN = 0.5f
        private const val INSET = 0.02f
    }
}

/**
 * Whole-number scale for the stage: the circle takes about [heightShare] of the screen
 * height (the user asked for ~4/10) but never more than [widthShare] of its width.
 */
fun stageScale(
    widthPx: Int,
    heightPx: Int,
    circlePx: Int,
    heightShare: Float = 0.40f,
    widthShare: Float = 0.92f,
): Int = floor(min(heightPx * heightShare, widthPx * widthShare) / circlePx).toInt().coerceAtLeast(2)
