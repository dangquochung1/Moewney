package app.meowney.feature.room

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.ui.Pal
import java.time.LocalDate
import kotlin.math.roundToInt

/** The five toys under the circle; each opens a module. */
enum class Toy(val label: String) {
    Money("Tiền"),
    Habits("Thói quen"),
    Calendar("Lịch"),
    Notes("Sổ tay"),
    Gift("Quà"),
}

/**
 * Art for the toy row, all at the same art-pixel size (~40-60 px) so one scale fits all.
 * Tower and house come from the packs; jar, calendar and gift are drawn here in the same
 * style (1 px dark outline, flat fill, top-left highlight).
 */
object ToyArt {
    private val OUT = Color(0xFF2A1A14)
    private val WOOD = Color(0xFF9B5E36)
    private val WOOD_LIGHT = Color(0xFFC98A4B)
    private val GLASS = Color(0xFFBFD8E8)
    private val GLASS_DARK = Color(0xFF8FB3CC)
    private val GOLD = Color(0xFFF2C14E)
    private val GOLD_DARK = Color(0xFFC08A2E)
    private val RED = Color(0xFFD9534F)
    private val RED_DARK = Color(0xFFA33A3A)

    fun image(toy: Toy, atlas: SpriteAtlas, typeface: Typeface, jarLevel: Float, today: LocalDate, habitLevel: Float? = null, catColor: Int = 5, calendarCount: Int = 0): ImageBitmap =
        when (toy) {
            Toy.Habits -> towerImage(atlas, habitLevel, catColor)
            Toy.Notes -> sprite(atlas, "prop/house_red")
            Toy.Money -> jarImage(jarLevel, typeface)
            Toy.Calendar -> paint(38, 42) { calendar(today, typeface, calendarCount) }
            Toy.Gift -> paint(38, 38) { gift() }
        }

    /** A coin jar (34 x 44 art px) filled to [level]; [liquid] colours the fill (gold coins by default). */
    fun jarImage(level: Float, typeface: Typeface, liquid: Color = GOLD, label: String = "đ", empty: Boolean = false): ImageBitmap =
        paint(34, 44) { jar(level, typeface, liquid, label, empty) }

    private const val TOWER_PAD = 3 // room above the tower for the cat's ears

    /**
     * The cat tower builds up with today's habits: the base is always there, the rest rises
     * from the floor as [level] grows (the missing part is a faint outline). At 100% the cat
     * sits on the low platform. [level] null = no habits today: the plain tower.
     */
    fun towerImage(atlas: SpriteAtlas, level: Float?, catColor: Int): ImageBitmap {
        val t = atlas["prop/tower_white"]
        val h = t.height + TOWER_PAD
        return paint(t.width, h) {
            fun tower(alpha: Float) = drawImage(
                t.page, t.srcOffset(0), t.srcSize, dstOffset = androidx.compose.ui.unit.IntOffset(0, TOWER_PAD),
                dstSize = androidx.compose.ui.unit.IntSize(t.width, t.height), alpha = alpha,
            )
            if (level == null) {
                tower(1f)
                return@paint
            }
            val base = 12
            val shown = base + ((t.height - base) * level.coerceIn(0f, 1f)).roundToInt()
            tower(0.22f)
            clipRect(top = (h - shown).toFloat()) { tower(1f) }
            if (level >= 1f) {
                val cat = atlas.getOrNull("cat/$catColor/sit") ?: return@paint
                // feet on the low left platform (its top is 23 px below the tower's top)
                drawImage(
                    cat.page, cat.srcOffset(0), cat.srcSize,
                    dstOffset = androidx.compose.ui.unit.IntOffset(2, TOWER_PAD + 24 - cat.height),
                    dstSize = androidx.compose.ui.unit.IntSize(cat.width, cat.height),
                )
            }
        }
    }

    private fun sprite(atlas: SpriteAtlas, key: String): ImageBitmap {
        val s = atlas[key]
        return paint(s.width, s.height) {
            drawImage(s.page, s.srcOffset(0), s.srcSize, dstSize = androidx.compose.ui.unit.IntSize(s.width, s.height))
        }
    }

    private fun paint(w: Int, h: Int, block: DrawScope.() -> Unit): ImageBitmap {
        val bmp = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat()), block)
        return bmp
    }

    private fun DrawScope.px(x: Int, y: Int, w: Int, h: Int, c: Color) =
        drawRect(c, Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()))

    private fun DrawScope.text(s: String, cx: Float, baseline: Float, size: Float, color: Color, typeface: Typeface) {
        val paint = Paint().apply {
            this.typeface = typeface
            textSize = size
            isAntiAlias = false
            this.color = color.toArgb()
            textAlign = Paint.Align.CENTER
        }
        drawIntoCanvas { it.nativeCanvas.drawText(s, cx, baseline, paint) }
    }

    /** Glass jar of coins with a wooden lid and a paper label. */
    private fun DrawScope.jar(level: Float, tf: Typeface, liquid: Color, label: String, alarm: Boolean) {
        // lid
        px(9, 0, 16, 6, OUT)
        px(10, 1, 14, 4, WOOD)
        px(10, 1, 14, 1, WOOD_LIGHT)
        // body (rounded by trimming corners)
        px(3, 7, 28, 37, OUT)
        px(2, 9, 30, 33, OUT)
        px(4, 8, 26, 35, GLASS)
        px(3, 10, 28, 31, GLASS)
        // coins up to the level
        val inside = 32
        val h = (inside * level.coerceIn(0f, 1f)).roundToInt()
        if (h > 0) {
            val top = 42 - h
            px(4, top, 26, h, liquid)
            val shade = Color(liquid.red * 0.75f, liquid.green * 0.75f, liquid.blue * 0.75f)
            for (row in top until 42 step 4) {
                for (col in 5 until 29 step 6) px(col + (row / 4 % 2) * 3, row, 4, 1, shade)
            }
            px(5, top, 6, 1, Color.White.copy(alpha = 0.6f))
        }
        // glass shine + side shadow
        px(6, 11, 2, 12, Color.White.copy(alpha = 0.75f))
        px(28, 12, 2, 26, GLASS_DARK.copy(alpha = 0.6f))
        // label with a đ
        px(10, 18, 14, 11, OUT)
        px(11, 19, 12, 9, if (alarm) RED else Pal.Cream)
        text(label, 17f, 27f, 9f, if (alarm) Pal.Cream else Pal.Ink, tf)
    }

    /** Desk calendar showing today's day number. */
    private fun DrawScope.calendar(today: LocalDate, tf: Typeface, count: Int) {
        px(1, 4, 32, 36, OUT)
        px(2, 5, 30, 34, Pal.Cream)
        px(2, 5, 30, 9, RED)
        px(2, 5, 30, 1, Color(0xFFE88A86))
        px(2, 38, 30, 1, Color(0xFFD9C7A3))
        // binder rings, near the edges so the month label has room between them
        px(4, 0, 3, 8, OUT)
        px(5, 1, 1, 6, Color(0xFFB0B0B0))
        px(27, 0, 3, 8, OUT)
        px(28, 1, 1, 6, Color(0xFFB0B0B0))
        text("T${today.monthValue}", 17f, 13f, 9f, Pal.Cream, tf)
        // day number at 2 px per font pixel stays crisp
        text("%02d".format(today.dayOfMonth), 17f, 35f, 18f, Pal.Ink, tf)
        // how many things are left today, as a red tag on the corner
        if (count > 0) {
            px(26, 30, 12, 12, OUT)
            px(27, 31, 10, 10, RED)
            text(if (count > 9) "9" else "$count", 32f, 39f, 9f, Pal.Cream, tf)
        }
    }

    /** Red present with a gold ribbon and bow. */
    private fun DrawScope.gift() {
        // bow
        px(8, 1, 10, 9, OUT)
        px(20, 1, 10, 9, OUT)
        px(9, 2, 8, 7, GOLD)
        px(21, 2, 8, 7, GOLD)
        px(11, 4, 4, 3, GOLD_DARK)
        px(23, 4, 4, 3, GOLD_DARK)
        px(16, 5, 6, 6, OUT)
        px(17, 6, 4, 4, GOLD_DARK)
        // lid
        px(1, 10, 36, 9, OUT)
        px(2, 11, 34, 7, RED)
        px(2, 11, 34, 1, Color(0xFFE88A86))
        // box
        px(3, 18, 32, 20, OUT)
        px(4, 19, 30, 18, RED)
        px(4, 31, 30, 6, RED_DARK)
        // ribbon
        px(16, 11, 6, 26, GOLD)
        px(16, 11, 1, 26, Color(0xFFFFE08A))
        px(21, 11, 1, 26, GOLD_DARK)
    }
}
