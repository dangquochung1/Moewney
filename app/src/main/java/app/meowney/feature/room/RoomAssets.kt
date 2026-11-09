package app.meowney.feature.room

import android.content.res.AssetManager
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.ui.Pal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TilesetRef(val name: String, val image: String, val columns: Int)

@Serializable
data class LayerRef(val name: String, val tiles: List<Int>)

/** A tappable area of the room, in room pixels. */
@Serializable
data class Hotspot(val id: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
}

@Serializable
data class RoomFile(
    val width: Int,
    val height: Int,
    val tile: Int,
    val tilesets: List<TilesetRef>,
    val layers: List<LayerRef>,
    val anims: Map<String, List<List<Int>>> = emptyMap(),
    val hotspots: List<Hotspot> = emptyList(),
    val spots: Map<String, List<Int>> = emptyMap(),
    /** Visible round window: centre x, centre y, radius (scene pixels). */
    val circle: List<Int> = emptyList(),
)

/** A tile that cycles through frames (the windows), drawn every frame on top of the baked room. */
class AnimatedTile(val tileset: Int, val col: Int, val row: Int, frames: List<List<Int>>) {
    private val ids = IntArray(frames.size) { frames[it][0] }
    private val ends = IntArray(frames.size).also { ends ->
        var t = 0
        frames.forEachIndexed { i, f -> t += f[1]; ends[i] = t }
    }
    private val total = ends.last()

    fun tileAt(ms: Long): Int {
        val t = (ms % total).toInt()
        return ids[ends.indexOfFirst { t < it }]
    }
}

/**
 * A tile scene from tools/build_room.py (the cat's corner). Static layers are baked once into [base]
 * (room-pixel resolution); animated tiles are drawn on top each frame.
 */
class RoomAssets private constructor(
    val file: RoomFile,
    private val sheets: List<ImageBitmap>,
    val base: ImageBitmap,
    private val animated: List<AnimatedTile>,
) {
    val widthPx get() = file.width * file.tile
    val heightPx get() = file.height * file.tile

    fun spot(name: String): Pair<Float, Float> =
        file.spots[name]?.let { it[0].toFloat() to it[1].toFloat() } ?: (widthPx / 2f to heightPx / 2f)

    val spots: Map<String, Pair<Float, Float>>
        get() = file.spots.mapValues { (_, v) -> v[0].toFloat() to v[1].toFloat() }

    val circle: StageCircle
        get() = file.circle.takeIf { it.size == 3 }
            ?.let { StageCircle(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
            ?: StageCircle(widthPx / 2f, heightPx / 2f, minOf(widthPx, heightPx) / 2f)

    fun hotspotAt(x: Float, y: Float): Hotspot? = file.hotspots.firstOrNull { it.contains(x, y) }

    fun drawAnimatedTiles(scope: DrawScope, ms: Long) {
        for (a in animated) drawTile(scope, a.tileset, a.tileAt(ms), a.col, a.row)
    }

    private fun drawTile(scope: DrawScope, ts: Int, index: Int, col: Int, row: Int) {
        val t = file.tile
        val columns = file.tilesets[ts].columns
        scope.drawImage(
            sheets[ts],
            srcOffset = IntOffset((index % columns) * t, (index / columns) * t),
            srcSize = IntSize(t, t),
            dstOffset = IntOffset(col * t, row * t),
            dstSize = IntSize(t, t),
            filterQuality = FilterQuality.None,
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(assets: AssetManager, path: String = "room/corner.json"): RoomAssets {
            val file = assets.open(path).use {
                json.decodeFromString<RoomFile>(it.readBytes().decodeToString())
            }
            val sheets = file.tilesets.map { SpriteAtlas.loadBitmap(assets, "room/${it.image}") }
            val base = ImageBitmap(file.width * file.tile, file.height * file.tile)
            val animated = mutableListOf<AnimatedTile>()
            val room = RoomAssets(file, sheets, base, animated)
            CanvasDrawScope().draw(
                Density(1f), LayoutDirection.Ltr, Canvas(base),
                Size(base.width.toFloat(), base.height.toFloat()),
            ) {
                drawRect(Pal.Night)
                for (layer in file.layers) {
                    layer.tiles.forEachIndexed { i, v ->
                        if (v == 0) return@forEachIndexed
                        val ts = (v - 1) / 10000
                        val index = (v - 1) % 10000
                        val col = i % file.width
                        val row = i / file.width
                        val anim = file.anims["$ts:$index"]
                        if (anim != null) {
                            animated += AnimatedTile(ts, col, row, anim)
                        } else {
                            room.drawTile(this, ts, index, col, row)
                        }
                    }
                }
            }
            return room
        }
    }
}
