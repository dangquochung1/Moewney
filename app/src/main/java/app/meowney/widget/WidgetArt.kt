package app.meowney.widget

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.meowney.core.data.TodaySnapshot
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawNinePatch
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.Pal
import app.meowney.feature.room.Mood
import java.time.LocalTime

/**
 * Widget pictures, drawn with the app's own sprites and pixel font so the home screen
 * keeps the pixel look (RemoteViews text would use the system font).
 */
object WidgetArt {
    private fun paint(w: Int, h: Int, block: DrawScope.() -> Unit): Bitmap {
        val bmp = ImageBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w.toFloat(), h.toFloat()), block)
        return bmp.asAndroidBitmap()
    }

    private fun DrawScope.text(s: String, x: Float, baseline: Float, px: Int, color: Color, tf: Typeface, center: Boolean = false) {
        val p = Paint().apply {
            typeface = tf
            textSize = 9f * px
            isAntiAlias = false
            this.color = color.toArgb()
            textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
        }
        drawIntoCanvas { it.nativeCanvas.drawText(s, x, baseline, p) }
    }

    private fun catAnim(mood: Mood): String {
        val hour = LocalTime.now().hour
        return when {
            hour >= 22 || hour < 6 -> "sleep1_r"
            mood == Mood.Sad -> "sad"
            mood == Mood.Worried -> "stand"
            mood == Mood.Happy -> "meow_sit"
            else -> "sit"
        }
    }

    /** The main card: cat + "Hôm nay còn". [wide] puts the text beside the cat. */
    fun card(atlas: SpriteAtlas, tf: Typeface, s: TodaySnapshot, w: Int, h: Int, wide: Boolean): Bitmap = paint(w, h) {
        val k = (h / 40).coerceAtLeast(2) // one art pixel
        drawNinePatch(atlas["ui/wood/nine_path_panel_3"], IntOffset.Zero, IntSize(w, h), (h / 72).coerceAtLeast(2))
        val cat = atlas.getOrNull("cat/${s.catColor}/${catAnim(s.mood)}") ?: atlas["cat/${s.catColor}/sit"]
        if (!s.onboarded) {
            drawSprite(cat, 0, w / 2f, h * 0.7f, scale = k)
            text("Mở Meowney để bắt đầu", w / 2f, h * 0.88f, (k / 2).coerceAtLeast(1), Pal.Ink, tf, center = true)
            return@paint
        }
        val t = (k / 2).coerceAtLeast(1) // font pixel
        val big = if (wide) k else t + 1
        val leftColor = if (s.left < 0) Pal.Red else Pal.Ink
        if (wide) {
            val catScale = (h * 0.6f / cat.height).toInt().coerceAtLeast(2)
            drawSprite(cat, 0, w * 0.2f, h * 0.85f, scale = catScale)
            val x = w * 0.4f
            text("HÔM NAY CÒN", x, h * 0.32f, t, Pal.InkSoft, tf)
            text(formatVnd(s.left), x, h * 0.32f + 13f * big, big, leftColor, tf)
            text(if (s.spent == 0L) "chưa tiêu đồng nào" else "đã tiêu ${formatVndShort(s.spent)}", x, h * 0.32f + 13f * big + 13f * t, t, Pal.InkSoft, tf)
        } else {
            val catScale = (h * 0.4f / cat.height).toInt().coerceAtLeast(2)
            val foot = h * 0.52f
            drawSprite(cat, 0, w / 2f, foot, scale = catScale)
            text("CÒN", w / 2f, foot + 11f * t, t, Pal.InkSoft, tf, center = true)
            text(formatVndShort(s.left), w / 2f, foot + 11f * t + 11f * big, big, leftColor, tf, center = true)
        }
    }

    /** A wooden button with a label. [primary] = orange "+" style. */
    fun button(atlas: SpriteAtlas, tf: Typeface, label: String, w: Int, h: Int, primary: Boolean): Bitmap = paint(w, h) {
        val k = (h / 16).coerceAtLeast(2)
        drawNinePatch(atlas[if (primary) "ui/wood/button_normal" else "ui/wood/button_hover"], IntOffset.Zero, IntSize(w, h), k)
        val ink = if (primary) Pal.Cream else Pal.Ink
        if (label == "+") {
            // the font's "+" is tiny; draw a chunky pixel plus instead
            val u = (h / 16).coerceAtLeast(2).toFloat()
            val cx = (w / 2f / u).toInt() * u
            val cy = (h / 2f / u).toInt() * u - u
            drawRect(ink, androidx.compose.ui.geometry.Offset(cx - 3 * u, cy), Size(7 * u, u))
            drawRect(ink, androidx.compose.ui.geometry.Offset(cx, cy - 3 * u), Size(u, 7 * u))
            return@paint
        }
        // one or two lines ("Ăn sáng\n35k"), as big as fits
        val lines = label.split('\n')
        var t = (h / (11 * lines.size + 4)).coerceAtLeast(1)
        val probe = Paint().apply { typeface = tf }
        while (t > 1) {
            probe.textSize = 9f * t
            if (lines.all { probe.measureText(it) <= w - 8f * k }) break
            t--
        }
        val lineH = 10f * t
        val top = h / 2f - lineH * lines.size / 2f
        lines.forEachIndexed { i, line ->
            text(line, w / 2f, top + lineH * (i + 1) - 2f * t, t, if (i == 0) ink else Pal.InkSoft.takeIf { !primary } ?: ink, tf, center = true)
        }
    }
}
