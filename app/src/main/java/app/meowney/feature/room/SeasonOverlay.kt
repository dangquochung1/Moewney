package app.meowney.feature.room

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.meowney.core.game.Season
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import kotlin.math.sin

/**
 * Little things in the air during a season, drawn over the cat's corner in scene pixels
 * (the corner is 64 x 64): snow at Noel, leaves in autumn, bats at Halloween, lanterns at Tết.
 */
object SeasonOverlay {
    private val SNOW = Color(0xFFF6F8FF)
    private val LEAVES = listOf(Color(0xFFE86A33), Color(0xFFD9534F), Color(0xFFF2C14E), Color(0xFFB0703F))
    private val BAT = Color(0xFF2A2027)
    private val SPARK = Color(0xFFF2C14E)

    /** A deterministic 0..1 value per particle, so nothing needs to be stored. */
    private fun rnd(i: Int, salt: Int): Float = (((i * 73856093) xor (salt * 19349663)) and 0xFFFF) / 65535f

    fun DrawScope.season(season: Season?, now: Long, atlas: SpriteAtlas) {
        when (season) {
            Season.NOEL -> snow(now)
            Season.AUTUMN -> leaves(now)
            Season.HALLOWEEN -> bats(now)
            Season.TET -> tet(now, atlas)
            null -> Unit
        }
    }

    private fun DrawScope.px(x: Float, y: Float, c: Color, w: Float = 1f, h: Float = 1f) =
        drawRect(c, Offset(x.toInt().toFloat(), y.toInt().toFloat()), Size(w, h))

    private fun DrawScope.snow(now: Long) {
        for (i in 0 until 22) {
            val speed = 4f + rnd(i, 1) * 6f // scene px per second
            val period = 70f / speed
            val t = ((now / 1000f + rnd(i, 2) * period) % period) / period
            val y = -4f + t * 72f
            val x = rnd(i, 3) * 64f + sin(now / 900f + i) * 2f
            px(x, y, SNOW, if (i % 5 == 0) 2f else 1f, if (i % 5 == 0) 2f else 1f)
        }
    }

    private fun DrawScope.leaves(now: Long) {
        for (i in 0 until 9) {
            val speed = 3f + rnd(i, 1) * 3f
            val period = 72f / speed
            val t = ((now / 1000f + rnd(i, 2) * period) % period) / period
            val y = -4f + t * 72f
            val x = rnd(i, 3) * 64f + sin(now / 700f + i * 1.7f) * 5f
            val c = LEAVES[i % LEAVES.size]
            px(x, y, c, 2f, 1f)
            px(x + 1, y + 1, c, 1f, 1f)
        }
    }

    private fun DrawScope.bats(now: Long) {
        for (i in 0 until 3) {
            val period = 9f + i * 3f
            val t = ((now / 1000f + i * 4f) % period) / period
            val x = -8f + t * 80f
            val y = 10f + i * 6f + sin(now / 400f + i) * 3f
            val up = (now / 180 + i) % 2 == 0L
            // a tiny bat: body + two wings that flap
            px(x, y, BAT, 2f, 2f)
            if (up) {
                px(x - 3, y - 1, BAT, 3f, 1f)
                px(x + 2, y - 1, BAT, 3f, 1f)
            } else {
                px(x - 3, y + 1, BAT, 3f, 1f)
                px(x + 2, y + 1, BAT, 3f, 1f)
            }
        }
    }

    private fun DrawScope.tet(now: Long, atlas: SpriteAtlas) {
        atlas.getOrNull("decor/lantern")?.let { l ->
            // inside the round window so the tops are not cut off
            drawSprite(l, 0, 19f, 32f)
            drawSprite(l, 0, 45f, 32f)
        }
        for (i in 0 until 6) {
            val on = (now / 300 + i * 7) % 6 < 2
            if (on) px(8f + rnd(i, 5) * 48f, 6f + rnd(i, 6) * 20f, SPARK)
        }
    }
}
