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
 * Little things in the sky during a season, in scene pixels of a [w] x [h] scene: bats and
 * lantern sparks behind the planet, snow and leaves in front of everything.
 */
object SeasonOverlay {
    private val SNOW = Color(0xFFF6F8FF)
    private val LEAVES = listOf(Color(0xFFE86A33), Color(0xFFD9534F), Color(0xFFF2C14E), Color(0xFFB0703F))
    private val BAT = Color(0xFF2A2027)
    private val SPARK = Color(0xFFF2C14E)

    /** A deterministic 0..1 value per particle, so nothing needs to be stored. */
    private fun rnd(i: Int, salt: Int): Float = (((i * 73856093) xor (salt * 19349663)) and 0xFFFF) / 65535f

    fun DrawScope.behind(season: Season?, now: Long, w: Float, h: Float) {
        when (season) {
            Season.HALLOWEEN -> bats(now, w, h)
            Season.TET -> sparks(now, w, h)
            else -> Unit
        }
    }

    fun DrawScope.front(season: Season?, now: Long, w: Float, h: Float, atlas: SpriteAtlas) {
        when (season) {
            Season.NOEL -> snow(now, w, h)
            Season.AUTUMN -> leaves(now, w, h)
            Season.TET -> lanterns(w, atlas)
            else -> Unit
        }
    }

    private fun DrawScope.px(x: Float, y: Float, c: Color, pw: Float = 1f, ph: Float = 1f) =
        drawRect(c, Offset(x.toInt().toFloat(), y.toInt().toFloat()), Size(pw, ph))

    private fun DrawScope.snow(now: Long, w: Float, h: Float) {
        val n = (w * h / 190).toInt().coerceIn(12, 40)
        for (i in 0 until n) {
            val speed = 4f + rnd(i, 1) * 6f // scene px per second
            val period = (h + 8) / speed
            val t = ((now / 1000f + rnd(i, 2) * period) % period) / period
            val y = -4f + t * (h + 8)
            val x = rnd(i, 3) * w + sin(now / 900f + i) * 2f
            px(x, y, SNOW, if (i % 5 == 0) 2f else 1f, if (i % 5 == 0) 2f else 1f)
        }
    }

    private fun DrawScope.leaves(now: Long, w: Float, h: Float) {
        val n = (w * h / 500).toInt().coerceIn(6, 16)
        for (i in 0 until n) {
            val speed = 3f + rnd(i, 1) * 3f
            val period = (h + 8) / speed
            val t = ((now / 1000f + rnd(i, 2) * period) % period) / period
            val y = -4f + t * (h + 8)
            val x = rnd(i, 3) * w + sin(now / 700f + i * 1.7f) * 5f
            val c = LEAVES[i % LEAVES.size]
            px(x, y, c, 2f, 1f)
            px(x + 1, y + 1, c, 1f, 1f)
        }
    }

    private fun DrawScope.bats(now: Long, w: Float, h: Float) {
        for (i in 0 until 3) {
            val period = 9f + i * 3f
            val t = ((now / 1000f + i * 4f) % period) / period
            val x = -8f + t * (w + 16)
            val y = h * 0.15f + i * 6f + sin(now / 400f + i) * 3f
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

    private fun DrawScope.sparks(now: Long, w: Float, h: Float) {
        for (i in 0 until 10) {
            val on = (now / 300 + i * 7) % 6 < 2
            if (on) px(4f + rnd(i, 5) * (w - 8), 4f + rnd(i, 6) * h * 0.4f, SPARK)
        }
    }

    /** Two red lanterns hanging from the top corners. */
    private fun DrawScope.lanterns(w: Float, atlas: SpriteAtlas) {
        atlas.getOrNull("decor/lantern")?.let { l ->
            drawSprite(l, 0, (w * 0.14f).toInt().toFloat(), 22f)
            drawSprite(l, 0, (w * 0.86f).toInt().toFloat(), 22f)
        }
    }
}
