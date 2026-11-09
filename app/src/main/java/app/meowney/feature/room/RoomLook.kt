package app.meowney.feature.room

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.meowney.core.data.GameState
import app.meowney.core.game.Catalog
import app.meowney.core.game.Season
import app.meowney.core.game.SeasonEngine
import app.meowney.core.game.Slot
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import kotlin.math.roundToInt

/** How the cat's corner is dressed: what the shop sold and the wardrobe put in each slot. */
data class RoomLook(
    /** ARGB tints; null = the art as drawn. */
    val frame: Long? = null,
    val wall: Long? = null,
    val floor: Long? = null,
    val rug: Long? = null,
    val decor: String? = null,
    val bed: String = "prop/catbed",
    val bowl: String = "prop/pet_food_red",
    val toy: String = "prop/yarnball",
    val tower: String = "prop/tower_white",
    /** hat/<name> worn now, or null. */
    val hat: String? = null,
    val season: Season? = null,
)

/** The look from what is equipped; in Noel the cat gets a Santa hat unless Sen picked one. */
fun GameState.look(season: Season?): RoomLook {
    fun item(slot: Slot) = equipped(slot)?.let { Catalog[it] }
    val hatItem = item(Slot.HAT)
    val hat = hatItem?.sprite?.split("/")?.getOrNull(1) ?: SeasonEngine.freeHat(season)
    return RoomLook(
        frame = item(Slot.FRAME)?.tint?.takeIf { item(Slot.FRAME)?.default == false },
        wall = item(Slot.WALL)?.tint,
        floor = item(Slot.FLOOR)?.tint,
        rug = item(Slot.RUG)?.tint,
        decor = item(Slot.DECOR)?.sprite,
        bed = item(Slot.BED)?.sprite ?: "prop/catbed",
        bowl = item(Slot.BOWL)?.sprite ?: "prop/pet_food_red",
        toy = item(Slot.TOY)?.sprite ?: "prop/yarnball",
        tower = item(Slot.TOWER)?.sprite ?: "prop/tower_white",
        hat = hat,
        season = season,
    )
}

object RoomPaint {
    /**
     * Recolours a layer in place: every pixel takes the tint, lighter or darker by how bright
     * it was against the layer's average, so the stone and plank texture stays.
     */
    fun colorize(img: ImageBitmap, tint: Long) {
        val bmp = img.asAndroidBitmap()
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0.0
        var n = 0
        for (c in px) if ((c ushr 24) > 0) {
            sum += lum(c)
            n++
        }
        if (n == 0) return
        val avg = (sum / n).coerceAtLeast(1.0)
        val tr = ((tint shr 16) and 0xFF).toInt()
        val tg = ((tint shr 8) and 0xFF).toInt()
        val tb = (tint and 0xFF).toInt()
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            val k = lum(c) / avg
            // the light trim (top of the wall, highlights) keeps its own colour
            if (k > 1.3) continue
            fun ch(t: Int, o: Int) = (0.75 * t * k + 0.25 * o).roundToInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (ch(tr, (c shr 16) and 0xFF) shl 16) or (ch(tg, (c shr 8) and 0xFF) shl 8) or ch(tb, c and 0xFF)
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private fun lum(c: Int) = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)

    fun shade(c: Long, k: Float): Color {
        val r = ((c shr 16) and 0xFF) * k
        val g = ((c shr 8) and 0xFF) * k
        val b = (c and 0xFF) * k
        return Color(r.coerceIn(0f, 255f) / 255f, g.coerceIn(0f, 255f) / 255f, b.coerceIn(0f, 255f) / 255f)
    }

    /** An oval rug on the floor under the cat's spot (scene pixels). */
    fun DrawScope.rug(tint: Long, cx: Float = 32f, cy: Float = 56f, rx: Int = 18, ry: Int = 5) {
        val edge = shade(tint, 0.6f)
        val body = shade(tint, 1f)
        val stripe = shade(tint, 1.25f)
        for (dy in -ry..ry) {
            val half = (rx * kotlin.math.sqrt(1f - (dy * dy).toFloat() / ((ry + 0.5f) * (ry + 0.5f)))).roundToInt()
            if (half <= 0) continue
            val y = cy + dy
            drawRect(edge, Offset(cx - half, y), Size(2f * half, 1f))
            if (half > 1 && dy != -ry && dy != ry) {
                val inner = half - 1
                drawRect(if (dy == 0 || dy == -2 || dy == 2) stripe else body, Offset(cx - inner, y), Size(2f * inner, 1f))
            }
        }
    }

    /** The corner decoration: hanging things on the wall, the rest standing on the floor at the left. */
    fun DrawScope.decor(atlas: SpriteAtlas, sprite: String) {
        val s = atlas.getOrNull(sprite) ?: return
        if (sprite == "decor/herbs") drawSprite(s, 0, 47f, 30f)
        else if (sprite == "decor/lantern") drawSprite(s, 0, 48f, 24f)
        else drawSprite(s, 0, 13f, 52f)
    }
}
