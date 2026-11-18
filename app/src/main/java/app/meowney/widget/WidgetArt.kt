package app.meowney.widget

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.meowney.core.data.CatScene
import app.meowney.core.data.TodaySnapshot
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.Pal
import app.meowney.core.ui.SkyLibrary
import app.meowney.feature.room.CatClock
import app.meowney.feature.room.PlanetScene
import app.meowney.feature.room.SeasonOverlay
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Widget pictures. The widget shows the cat on its little planet under the home screen's sky,
 * wearing the hat picked in the shop; the cat naps at the app's hours and shows how the day is going.
 * No money is shown.
 */
object WidgetArt {
    /** Season effects are drawn as one still frame. */
    private const val STILL = 4_000L

    /** Draws into a fresh bitmap of [w] x [h] pixels. */
    fun paint(w: Int, h: Int, block: DrawScope.() -> Unit): Bitmap {
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

    /** The cat naps at the same hours as in the app. */
    fun asleep(hour: Int) = CatClock.sleepHour(hour)

    private fun pose(scene: CatScene) = when (scene) {
        CatScene.CALM -> "sit"
        CatScene.HAPPY -> "meow_sit"
        CatScene.WORRIED -> "stand"
        CatScene.ANGRY -> "hiss_l"
        CatScene.SAD -> "sad"
    }

    /** A tiny speech bubble with a red "!" beside an angry cat (scene pixels). */
    private fun DrawScope.bang(x: Float, y: Float) {
        drawRect(Pal.Ink, Offset(x - 2, y - 2), Size(6f, 13f))
        drawRect(Pal.Ink, Offset(x - 3, y - 1), Size(8f, 11f))
        drawRect(Pal.Card, Offset(x - 2, y - 1), Size(6f, 11f))
        drawRect(Pal.Red, Offset(x, y), Size(2f, 5f))
        drawRect(Pal.Red, Offset(x, y + 7), Size(2f, 2f))
    }

    private fun DrawScope.zzz(x: Float, y: Float) {
        val c = Pal.Cream
        drawRect(c, Offset(x, y), Size(3f, 1f))
        drawRect(c, Offset(x + 1, y + 1), Size(1f, 1f))
        drawRect(c, Offset(x, y + 2), Size(3f, 1f))
        drawRect(c, Offset(x + 5, y - 4), Size(2f, 1f))
        drawRect(c, Offset(x + 6, y - 3), Size(1f, 1f))
        drawRect(c, Offset(x + 5, y - 2), Size(2f, 1f))
    }

    /**
     * The cat on its planet at scene resolution ([p] says where things go): the season behind,
     * the planet (no turning), the cat in the pose of the day or asleep, the season in front.
     */
    private fun sceneBitmap(atlas: SpriteAtlas, skies: SkyLibrary, s: TodaySnapshot, p: PlanetScene, hour: Int): ImageBitmap {
        val out = ImageBitmap(p.width, p.height)
        val look = s.look
        val w = p.width.toFloat()
        val h = p.height.toFloat()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w, h)) {
            with(SeasonOverlay) { behind(look.season, STILL, w, h) }
            val planet = skies.planet(if (CatClock.isNight(hour)) "moon" else "earth")
            val left = (p.cx - planet.width / 2f).roundToInt()
            val top = (p.cy - planet.height / 2f).roundToInt()
            drawImage(planet, dstOffset = IntOffset(left, top), dstSize = IntSize(planet.width, planet.height), filterQuality = FilterQuality.None)
            drawOval(Color(0x33000000), Offset(p.feetX - 8f, p.feetY - 2f), Size(16f, 3f))
            val asleep = s.onboarded && asleep(hour)
            val anim = if (asleep) "sleep1_r" else pose(s.scene)
            val cat = atlas.getOrNull("cat/${s.catColor}/$anim") ?: atlas["cat/${s.catColor}/sit"]
            drawSprite(cat, 0, p.feetX, p.feetY)
            look.hat?.let { hat -> atlas.getOrNull("hat/$hat/$anim")?.let { drawSprite(it, 0, p.feetX, p.feetY) } }
            when {
                asleep -> zzz(p.feetX + 10f, p.feetY - 22f)
                s.scene == CatScene.ANGRY -> bang(p.feetX - 20f, p.feetY - 27f)
            }
            with(SeasonOverlay) { front(look.season, STILL, w, h, atlas) }
        }
        return out
    }

    /**
     * The cat on its planet under the sky (day or night by the hour), filling [w] x [h] with
     * rounded corners. The sky covers the frame at a whole-number scale, sitting on the bottom
     * edge like on the home screen; the planet and the cat are laid out by [PlanetScene].
     * [plus] adds the round "ghi" button in the corner.
     */
    fun scene(atlas: SpriteAtlas, skies: SkyLibrary, s: TodaySnapshot, w: Int, h: Int, hour: Int, radius: Float, plus: Boolean = false): Bitmap {
        val p = PlanetScene.of(w.coerceAtLeast(1), h.coerceAtLeast(1))
        val art = sceneBitmap(atlas, skies, s, p, hour)
        val sky = skies.sky(if (CatClock.isNight(hour)) SkyLibrary.HOME_NIGHT else SkyLibrary.HOME_DAY)
        return paint(w, h) {
            val clip = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), CornerRadius(radius))) }
            clipPath(clip) {
                sky.layers.firstOrNull()?.let { back ->
                    val k = ceil(max(h.toFloat() / back.height, w.toFloat() / back.width)).toInt().coerceAtLeast(1)
                    val dw = back.width * k
                    val dh = back.height * k
                    for (layer in sky.layers) {
                        drawImage(layer, dstOffset = IntOffset((w - dw) / 2, h - dh), dstSize = IntSize(dw, dh), filterQuality = FilterQuality.None)
                    }
                }
                drawImage(
                    art,
                    dstOffset = IntOffset(p.offsetX, p.offsetY),
                    dstSize = IntSize(p.width * p.scale, p.height * p.scale),
                    filterQuality = FilterQuality.None,
                )
            }
            if (plus) {
                val r = minOf(w, h) * 0.11f
                val c = Offset(w - r * 1.5f, h - r * 1.5f)
                drawCircle(Pal.GoldLip, r, c + Offset(0f, r * 0.12f))
                drawCircle(Pal.Gold, r, c)
                val t = r * 0.16f
                drawRect(Pal.Ink, Offset(c.x - r * 0.5f, c.y - t), Size(r, t * 2))
                drawRect(Pal.Ink, Offset(c.x - t, c.y - r * 0.5f), Size(t * 2, r))
            }
        }
    }

    /** A soft rounded button with a label of one or two lines. [primary] = the gold "+". */
    fun button(tf: Typeface, label: String, w: Int, h: Int, primary: Boolean, radius: Float): Bitmap = paint(w, h) {
        val r = CornerRadius(radius)
        val lip = h * 0.05f
        if (primary) drawRoundRect(Pal.GoldLip, Offset(0f, lip), Size(w.toFloat(), h - lip), r)
        drawRoundRect(if (primary) Pal.Gold else Pal.Card, Offset.Zero, Size(w.toFloat(), h - lip), r)
        if (label == "+") {
            val arm = h * 0.2f
            val t = h * 0.045f
            val cx = w / 2f
            val cy = (h - lip) / 2f
            drawRect(Pal.Ink, Offset(cx - arm, cy - t), Size(arm * 2, t * 2))
            drawRect(Pal.Ink, Offset(cx - t, cy - arm), Size(t * 2, arm * 2))
            return@paint
        }
        // one or two lines ("Ăn sáng\n35k"), as big as fits
        val lines = label.split('\n')
        var t = (h / (11 * lines.size + 4)).coerceAtLeast(1)
        val probe = Paint().apply { typeface = tf }
        while (t > 1) {
            probe.textSize = 9f * t
            if (lines.all { probe.measureText(it) <= w - radius }) break
            t--
        }
        val lineH = 10f * t
        val top = (h - lip) / 2f - lineH * lines.size / 2f
        lines.forEachIndexed { i, line ->
            text(line, w / 2f, top + lineH * (i + 1) - 2f * t, t, if (i == 0) Pal.Ink else Pal.InkSoft, tf, center = true)
        }
    }
}
