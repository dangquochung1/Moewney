package app.meowney.feature.room

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import kotlinx.coroutines.delay
import java.time.LocalTime
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The round window onto the cat's corner.
 *
 * The scene is drawn at its own resolution (a [StageCircle] of ~54 px), masked to a
 * pixel circle and blown up by the whole-number [scale]. A wooden ring, drawn on the
 * finer UI pixel grid, frames it and hides the big stair-step edge of the mask.
 */
@Composable
fun CatStage(
    corner: RoomAssets,
    cat: Cat,
    scale: Int,
    onCatTap: () -> Unit,
    onCatLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    look: RoomLook = RoomLook(),
) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val circle = corner.circle
    val d = circle.diameter
    val stagePx = d * scale
    val ring = remember(stagePx, scale, m.ui, look.frame) { WoodRing.build(stagePx, scale, m.ui, look.frame) }
    val base = remember(look.wall, look.floor) { corner.baked(look.wall, look.floor) }
    val pad = (ring.sizePx - stagePx) / 2

    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(cat) {
        var last = withFrameMillis { it }
        val start = last
        while (true) {
            withFrameMillis { now ->
                val dt = (now - last).coerceAtMost(100)
                last = now
                clock.longValue = now - start
                cat.update(clock.longValue, dt, atlas, LocalTime.now().hour)
            }
            // Sprites animate at 2-10 fps and positions snap to art pixels, so ~25 fps is
            // plenty; drawing every vsync would only burn battery.
            delay(FRAME_MS)
        }
    }

    val frame = remember(d) { ImageBitmap(d, d) }
    val frameCanvas = remember(frame) { ComposeCanvas(frame) }
    val frameScope = remember { CanvasDrawScope() }
    val mask = remember(d, circle) { circleMask(d, circle.r) }
    val originX = circle.cx - circle.r
    val originY = circle.cy - circle.r

    Canvas(
        modifier
            .size(m.dp(ring.sizePx), m.dp(ring.sizePx))
            .pointerInput(cat, scale) {
                detectTapGestures(
                    onTap = { pos ->
                        val sx = (pos.x - pad) / scale + originX
                        val sy = (pos.y - pad) / scale + originY
                        if (cat.hit(sx, sy)) onCatTap()
                    },
                    onLongPress = { onCatLongPress() },
                )
            },
    ) {
        val now = clock.longValue
        frameScope.draw(Density(1f), LayoutDirection.Ltr, frameCanvas, Size(d.toFloat(), d.toFloat())) {
            translate(-originX, -originY) {
                drawImage(base)
                corner.drawAnimatedTiles(this, now)
                look.rug?.let { with(RoomPaint) { rug(it) } }
                look.decor?.let { with(RoomPaint) { decor(atlas, it) } }
                cat.draw(this, atlas, now)
            }
            drawImage(mask)
        }
        drawImage(
            frame,
            dstOffset = IntOffset(pad, pad),
            dstSize = IntSize(stagePx, stagePx),
            filterQuality = FilterQuality.None,
        )
        drawImage(
            ring.image,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(ring.sizePx, ring.sizePx),
            filterQuality = FilterQuality.None,
        )
    }
}

private const val FRAME_MS = 40L

/** Night-coloured pixels outside the circle, transparent inside (scene resolution). */
private fun circleMask(d: Int, r: Float): ImageBitmap {
    val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
    val night = Pal.Night.toArgb()
    for (y in 0 until d) for (x in 0 until d) {
        if (hypot(x + 0.5f - d / 2f, y + 0.5f - d / 2f) > r) bmp.setPixel(x, y, night)
    }
    return bmp.asImageBitmap()
}

/** The wooden frame, rasterised once on the UI pixel grid. */
class WoodRing private constructor(val image: ImageBitmap, val sizePx: Int) {
    companion object {
        private val OUTLINE = Color(0xFF2A1A14)
        private val DARK = Color(0xFF6E3F25)
        private val WOOD = Color(0xFF9B5E36)
        private val LIGHT = Color(0xFFC98A4B)
        private val SHADOW = Color(0xFF3B2A24)

        /**
         * [stagePx]: scaled circle diameter; [stageScale]: its pixel size (the ring covers one
         * stage pixel inwards to hide the stair-step edge); [ui]: the ring's own pixel size.
         */
        fun build(stagePx: Int, stageScale: Int, ui: Int, tint: Long? = null): WoodRing {
            val light = tint?.let { RoomPaint.shade(it, 1.3f) } ?: LIGHT
            val wood = tint?.let { RoomPaint.shade(it, 1f) } ?: WOOD
            val dark = tint?.let { RoomPaint.shade(it, 0.7f) } ?: DARK
            val shadow = tint?.let { RoomPaint.shade(it, 0.4f) } ?: SHADOW
            val outer = stagePx / 2f + 4 * ui
            val inner = stagePx / 2f - stageScale
            val cells = ceil(2 * outer / ui).toInt() + 1
            val sizePx = cells * ui
            val c = sizePx / 2f
            val bmp = Bitmap.createBitmap(cells, cells, Bitmap.Config.ARGB_8888)
            for (j in 0 until cells) for (i in 0 until cells) {
                val px = (i + 0.5f) * ui - c
                val py = (j + 0.5f) * ui - c
                val dist = hypot(px, py)
                if (dist > outer || dist < inner) continue
                val fromOut = ((outer - dist) / ui).toInt()
                val fromIn = ((dist - inner) / ui).toInt()
                // light comes from the top-left
                val lit = (-px - py) / (dist * 1.4142f) > 0.35f
                val color = when {
                    fromOut == 0 -> OUTLINE
                    fromIn == 0 -> shadow
                    fromIn == 1 -> dark
                    fromOut == 1 -> if (lit) light else wood
                    else -> if (lit) wood else dark
                }
                bmp.setPixel(i, j, color.toArgb())
            }
            return WoodRing(bmp.asImageBitmap(), sizePx)
        }
    }
}
