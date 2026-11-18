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
import androidx.compose.ui.graphics.drawscope.translate
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
import app.meowney.feature.room.RoomAssets
import app.meowney.feature.room.RoomPaint
import app.meowney.feature.room.SeasonOverlay
import kotlin.math.ceil

/**
 * Widget pictures. The widget is a window into the cat's room, drawn with the room's own
 * tiles and sprites and what the wardrobe put there; the cat sleeps at night and shows how
 * the day is going. No money is shown.
 */
object WidgetArt {
    private val NIGHT = Color(0x7A10143A)

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

    fun night(hour: Int) = hour >= 22 || hour < 6

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
     * The room at scene resolution: tiles, what the wardrobe put there, the cat, the weather.
     * [extraRows] more rows of stone wall go on top, for a frame taller than the room.
     */
    private fun sceneBitmap(atlas: SpriteAtlas, room: RoomAssets, s: TodaySnapshot, hour: Int, extraRows: Int): ImageBitmap {
        val w = room.widthPx
        val tile = room.file.tile
        val extra = extraRows * tile
        val h = room.heightPx + extra
        val out = ImageBitmap(w, h)
        val night = night(hour)
        val look = s.look
        val walls = room.baked(look.wall, look.floor)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w.toFloat(), h.toFloat())) {
            // the first row of the room is plain stone (the window is drawn separately), so it repeats
            for (r in 0 until extraRows) {
                drawImage(walls, srcOffset = IntOffset.Zero, srcSize = IntSize(w, tile), dstOffset = IntOffset(0, r * tile), dstSize = IntSize(w, tile))
            }
            translate(top = extra.toFloat()) { roomLayer(atlas, room, s, night, walls, Size(w.toFloat(), h.toFloat()), extra.toFloat()) }
            // the season's weather, repeated across a room wider than the corner it was made for
            var x = 0f
            while (x < w) {
                translate(left = x, top = (h - 64).toFloat()) { with(SeasonOverlay) { season(look.season, 4_000L + x.toLong() * 37, atlas) } }
                x += 64f
            }
        }
        return out
    }

    private fun DrawScope.roomLayer(atlas: SpriteAtlas, room: RoomAssets, s: TodaySnapshot, night: Boolean, walls: ImageBitmap, full: Size, extra: Float) {
        val look = s.look
        val h = room.heightPx
        drawImage(walls)
        // the window's animation has a lit frame (0 ms) and a dark one (950 ms); on a taller wall
        // it moves up to the middle of the stone
        val tile = room.file.tile
        translate(top = -((extra / tile).toInt() / 2 * tile).toFloat()) { room.drawAnimatedTiles(this, if (night) 950 else 0) }
        val (hx, hy) = room.spot("home")
        look.rug?.let { with(RoomPaint) { rug(it, cx = hx, cy = hy - 1f, rx = 18, ry = if (h < 64) 3 else 5) } }
        fun prop(sprite: String?, spot: String) {
            val art = sprite?.let(atlas::getOrNull) ?: return
            val (x, y) = room.spot(spot)
            drawSprite(art, 0, x, y)
        }
        val wide = "post" in room.spots
        val asleep = s.onboarded && night
        if (wide || asleep) prop(look.bed, "bed")
        prop(if (s.scene == CatScene.SAD) "prop/emptybowl" else look.bowl, "bowl")
        prop(look.toy, "yarn")
        if (wide) {
            prop(look.decor ?: "decor/pot", "decor")
            prop("prop/scratchingpost", "post")
        }
        if (asleep) {
            val (bx, by) = room.spot("bed_cat")
            val cat = atlas.getOrNull("cat/${s.catColor}/sleep1_r") ?: atlas["cat/${s.catColor}/sit"]
            drawSprite(cat, 0, bx, by)
            drawRect(NIGHT, Offset(0f, -extra), full)
            zzz(bx + 12f, by - 28f)
        } else {
            val anim = pose(s.scene)
            val cat = atlas.getOrNull("cat/${s.catColor}/$anim") ?: atlas["cat/${s.catColor}/sit"]
            drawSprite(cat, 0, hx, hy)
            look.hat?.let { hat -> atlas.getOrNull("hat/$hat/$anim")?.let { drawSprite(it, 0, hx, hy) } }
            if (s.scene == CatScene.ANGRY) bang(hx - 20f, hy - 27f)
            if (night) drawRect(NIGHT, Offset(0f, -extra), full)
        }
    }

    /**
     * The room filling [w] x [h] with rounded corners: as wide as the frame, scaled by a whole
     * number so the pixels stay sharp. A taller frame gets more wall on top; a flatter one
     * loses some wall. [plus] adds the round "ghi" button in the corner.
     */
    fun scene(atlas: SpriteAtlas, room: RoomAssets, s: TodaySnapshot, w: Int, h: Int, hour: Int, radius: Float, plus: Boolean = false): Bitmap {
        val scale = ceil(w.toFloat() / room.widthPx).toInt().coerceAtLeast(1)
        val missing = h - room.heightPx * scale
        val extraRows = if (missing > 0) ceil(missing.toFloat() / (room.file.tile * scale)).toInt() else 0
        val art = sceneBitmap(atlas, room, s, hour, extraRows)
        return paint(w, h) {
            val dw = art.width * scale
            val dh = art.height * scale
            val clip = Path().apply { addRoundRect(RoundRect(Rect(0f, 0f, w.toFloat(), h.toFloat()), CornerRadius(radius))) }
            clipPath(clip) {
                // keep the floor: crop from the top when the picture is taller than the frame
                drawImage(art, dstOffset = IntOffset((w - dw) / 2, h - dh), dstSize = IntSize(dw, dh), filterQuality = FilterQuality.None)
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
