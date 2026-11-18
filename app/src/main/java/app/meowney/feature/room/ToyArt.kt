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

/** The coin jar of the money screens, drawn in code (1 px dark outline, flat fill, top-left highlight). */
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

    /** A coin jar (34 x 44 art px) filled to [level]; [liquid] colours the fill (gold coins by default). */
    fun jarImage(level: Float, typeface: Typeface, liquid: Color = GOLD, label: String = "đ", empty: Boolean = false): ImageBitmap =
        paint(34, 44) { jar(level, typeface, liquid, label, empty) }

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
}
