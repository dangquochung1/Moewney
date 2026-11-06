package app.meowney.core.sprite

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One entry of sprites.json: a strip of [n] equal frames, or a single image when n == 1. */
@Serializable
data class SpriteEntry(
    val p: Int,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val n: Int = 1,
    val fps: Int = 0,
    /** Nine-patch insets in source pixels: left, top, right, bottom. */
    val nine: List<Int>? = null,
)

@Serializable
private data class SpritesFile(val pages: List<String>, val sprites: Map<String, SpriteEntry>)

class Sprite(val key: String, val page: ImageBitmap, val entry: SpriteEntry) {
    val width get() = entry.w
    val height get() = entry.h
    val frames get() = entry.n

    /** Frame shown [ms] milliseconds into a looping animation. */
    fun frameAt(ms: Long, fps: Int = entry.fps): Int =
        if (frames <= 1 || fps <= 0) 0 else ((ms * fps / 1000) % frames).toInt()

    fun srcOffset(frame: Int) = IntOffset(entry.x + frame.coerceIn(0, frames - 1) * entry.w, entry.y)
    val srcSize get() = IntSize(entry.w, entry.h)
}

/** Everything packed by tools/pack_assets.py. Loaded once, off the main thread. */
class SpriteAtlas private constructor(private val sprites: Map<String, Sprite>) {
    operator fun get(key: String): Sprite =
        sprites[key] ?: error("Sprite '$key' is not in sprites.json - add it to tools/pack_assets.py")

    fun getOrNull(key: String): Sprite? = sprites[key]

    fun keys(prefix: String): List<String> = sprites.keys.filter { it.startsWith(prefix) }.sorted()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(assets: AssetManager): SpriteAtlas {
            val file = assets.open("sprites/sprites.json").use {
                json.decodeFromString<SpritesFile>(it.readBytes().decodeToString())
            }
            val pages = file.pages.map { name -> loadBitmap(assets, "sprites/$name") }
            return SpriteAtlas(file.sprites.mapValues { (key, e) -> Sprite(key, pages[e.p], e) })
        }

        fun loadBitmap(assets: AssetManager, path: String): ImageBitmap {
            val options = BitmapFactory.Options().apply { inScaled = false }
            return assets.open(path).use { BitmapFactory.decodeStream(it, null, options)!! }.asImageBitmap()
        }
    }
}

/**
 * Draws one frame with its bottom-centre at ([x], [y]), [scale] screen pixels per art pixel.
 * Positions are rounded so art pixels always land on whole screen pixels.
 */
fun DrawScope.drawSprite(
    sprite: Sprite,
    frame: Int,
    x: Float,
    y: Float,
    scale: Int = 1,
    flipX: Boolean = false,
    alpha: Float = 1f,
) {
    val w = sprite.width * scale
    val h = sprite.height * scale
    val left = Math.round(x - w / 2f)
    val top = Math.round(y - h)
    if (!flipX) {
        drawImage(
            sprite.page, sprite.srcOffset(frame), sprite.srcSize,
            dstOffset = IntOffset(left, top), dstSize = IntSize(w, h),
            alpha = alpha, filterQuality = FilterQuality.None,
        )
    } else {
        scale(-1f, 1f, pivot = androidx.compose.ui.geometry.Offset(left + w / 2f, top + h / 2f)) {
            drawImage(
                sprite.page, sprite.srcOffset(frame), sprite.srcSize,
                dstOffset = IntOffset(left, top), dstSize = IntSize(w, h),
                alpha = alpha, filterQuality = FilterQuality.None,
            )
        }
    }
}

/** Draws [sprite] (frame 0) as a nine-patch filling [size] at [topLeft]; corners scaled by [scale]. */
fun DrawScope.drawNinePatch(
    sprite: Sprite,
    topLeft: IntOffset,
    size: IntSize,
    scale: Int,
    alpha: Float = 1f,
) {
    val nine = sprite.entry.nine ?: listOf(0, 0, 0, 0)
    val (l, t, r, b) = nine
    val sx = sprite.entry.x
    val sy = sprite.entry.y
    val sw = sprite.width
    val sh = sprite.height
    val srcXs = intArrayOf(0, l, sw - r, sw)
    val srcYs = intArrayOf(0, t, sh - b, sh)
    // Corners keep their art size; if the target is too small they shrink evenly.
    val cl = minOf(l * scale, size.width / 2)
    val cr = minOf(r * scale, size.width - cl)
    val ct = minOf(t * scale, size.height / 2)
    val cb = minOf(b * scale, size.height - ct)
    val dstXs = intArrayOf(0, cl, size.width - cr, size.width)
    val dstYs = intArrayOf(0, ct, size.height - cb, size.height)
    for (row in 0..2) for (col in 0..2) {
        val srcW = srcXs[col + 1] - srcXs[col]
        val srcH = srcYs[row + 1] - srcYs[row]
        val dstW = dstXs[col + 1] - dstXs[col]
        val dstH = dstYs[row + 1] - dstYs[row]
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) continue
        drawImage(
            sprite.page,
            srcOffset = IntOffset(sx + srcXs[col], sy + srcYs[row]),
            srcSize = IntSize(srcW, srcH),
            dstOffset = IntOffset(topLeft.x + dstXs[col], topLeft.y + dstYs[row]),
            dstSize = IntSize(dstW, dstH),
            alpha = alpha,
            filterQuality = FilterQuality.None,
        )
    }
}
