package app.meowney.core.ui

import android.content.res.AssetManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.meowney.core.sprite.SpriteAtlas
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Layers of one sky, back to front, and how fast each drifts (art pixels per second). */
class SkySet(val layers: List<ImageBitmap>, val drift: List<Float>)

/** The skies in assets/bg (tools/pack_assets.py), loaded once each. */
class SkyLibrary(private val assets: AssetManager) {
    private val sets = HashMap<String, SkySet>()
    private val planets = HashMap<String, ImageBitmap>()

    fun sky(name: String): SkySet = synchronized(sets) {
        sets.getOrPut(name) {
            val drift = DRIFT.getValue(name)
            SkySet(drift.indices.map { SpriteAtlas.loadBitmap(assets, "bg/$name/${it + 1}.png") }, drift)
        }
    }

    /** "earth" or "moon": the ground under the cat. */
    fun planet(name: String): ImageBitmap = synchronized(planets) {
        planets.getOrPut(name) { SpriteAtlas.loadBitmap(assets, "planet/$name.png") }
    }

    companion object {
        const val HOME_DAY = "home_day"
        const val HOME_NIGHT = "home_night"
        const val CAL_DAY = "cal_day"
        const val CAL_NIGHT = "cal_night"

        /** The back layer stays put; clouds nearer the front drift faster. */
        private val DRIFT = mapOf(
            HOME_DAY to listOf(0f, 0.5f, 1f, 1.6f, 2.4f, 3.4f),
            HOME_NIGHT to listOf(0f, 1.6f),
            CAL_DAY to listOf(0f, 0.6f, 1.3f, 2.2f),
            CAL_NIGHT to listOf(0f, 0.8f, 1.8f),
        )
    }
}

/**
 * A sky of [set] filling the box: every layer at the same whole-number scale (big enough to
 * cover it), sitting on the bottom edge and tiling sideways while it drifts. When the cat
 * walks the clouds slide too, nearer ones more ([walked] in scene pixels, right = positive).
 */
@Composable
fun ParallaxSky(set: SkySet, modifier: Modifier = Modifier, walked: () -> Float = { 0f }) {
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(set) {
        val start = withFrameMillis { it }
        while (true) {
            withFrameMillis { clock.longValue = it - start }
            // clouds move a few screen pixels a second: 20 frames a second is plenty
            delay(50)
        }
    }
    Canvas(modifier) {
        val back = set.layers.firstOrNull() ?: return@Canvas
        val s = ceil(max(size.height / back.height, size.width / back.width)).toInt().coerceAtLeast(1)
        val dw = back.width * s
        val dh = back.height * s
        val top = (size.height - dh).roundToInt()
        val t = clock.longValue / 1000f
        val w = walked()
        val last = (set.layers.size - 1).coerceAtLeast(1)
        set.layers.forEachIndexed { i, img ->
            val shift = (set.drift[i] * t + w * WALK_SHIFT * i / last) * s
            val start = (size.width - dw) / 2f - shift
            var x = start - ceil(start / dw) * dw // the copy that starts at or left of 0
            if (x > 0f) x -= dw
            x = floor(x)
            while (x < size.width) {
                drawImage(
                    img,
                    dstOffset = IntOffset(x.toInt(), top),
                    dstSize = IntSize(dw, dh),
                    filterQuality = FilterQuality.None,
                )
                x += dw
            }
        }
    }
}

/** Art pixels the front cloud layer slides per scene pixel the cat walks. */
private const val WALK_SHIFT = 0.8f

/**
 * While shown, the status bar icons are light when [darkBehind] (a night sky behind them) and
 * dark otherwise; the app's normal dark icons come back when this leaves the screen.
 */
@Composable
fun StatusBarIcons(darkBehind: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    if (view.isInEditMode) return
    androidx.compose.runtime.DisposableEffect(darkBehind) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !darkBehind
        onDispose { controller?.isAppearanceLightStatusBars = true }
    }
}
