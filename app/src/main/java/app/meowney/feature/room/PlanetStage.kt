package app.meowney.feature.room

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.meowney.container
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.ParallaxSky
import app.meowney.core.ui.SkyLibrary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Where things go in the scene of a stage [widthPx] x [heightPx] screen pixels big. The scene
 * is drawn small ([width] x [height] scene pixels) and blown up by the whole number [scale];
 * the planet sits at the bottom with only its top cap showing and the cat stands on top.
 */
data class PlanetScene(
    val scale: Int,
    val width: Int,
    val height: Int,
    val radius: Float,
    val cx: Float,
    val cy: Float,
    val feetX: Float,
    val feetY: Float,
    /** Screen offset of the scene's top-left (0 or less: the scene is cut on the left/top). */
    val offsetX: Int,
    val offsetY: Int,
) {
    /** Scene pixel under the screen point ([x], [y]). */
    fun toScene(x: Float, y: Float) = (x - offsetX) / scale to (y - offsetY) / scale

    companion object {
        /** Scene pixels across a phone screen: the cat is about a third of it. */
        const val VIEW_WIDTH = 68f

        /** The planet images are this square, with a disc of [RADIUS] whose top is on row 1. */
        const val PLANET = 102
        const val RADIUS = 50f

        /** How much of the planet shows above the bottom edge, in the middle. */
        const val CAP = 34

        /** Room above the planet for the cat and a bit of sky. */
        private const val ABOVE = 34

        fun of(widthPx: Int, heightPx: Int): PlanetScene {
            val byWidth = (widthPx / VIEW_WIDTH).roundToInt()
            val byHeight = heightPx / (CAP + ABOVE)
            val k = minOf(byWidth, byHeight).coerceAtLeast(2)
            val w = (widthPx + k - 1) / k
            val h = (heightPx + k - 1) / k
            val r = RADIUS
            val top = (h - CAP).toFloat() // the planet's top row: its outline
            return PlanetScene(
                scale = k,
                width = w,
                height = h,
                radius = r,
                cx = (w / 2).toFloat(),
                cy = top - 1 + PLANET / 2f, // centre of the image, which starts a row above the disc
                feetX = (w / 2).toFloat(),
                feetY = top + 1f, // the paws overlap the outline of the ground by one pixel
                offsetX = (widthPx - w * k) / 2,
                offsetY = heightPx - h * k,
            )
        }

        /** Degrees the planet has turned after the cat walked [walked] scene pixels on top of it. */
        fun turn(walked: Float, radius: Float = RADIUS): Float = -Math.toDegrees((walked / radius).toDouble()).toFloat()
    }
}

/** True from 18:00 to 06:59; re-checked every half minute. */
@Composable
fun rememberNight(): State<Boolean> = produceState(CatClock.isNight(LocalTime.now().hour)) {
    while (true) {
        delay(30_000)
        value = CatClock.isNight(LocalTime.now().hour)
    }
}

/**
 * The cat on its little planet under a drifting sky (Earth by day, the Moon at night). The
 * size comes from [modifier]. Tap, long-press and stroke the cat like before; stroking makes
 * hearts float up.
 */
@Composable
fun PlanetStage(
    cat: Cat,
    modifier: Modifier = Modifier,
    look: RoomLook = RoomLook(),
    onCatTap: () -> Unit = {},
    onCatLongPress: () -> Unit = {},
    onPet: () -> Unit = {},
    onPetEnd: () -> Unit = {},
) {
    val atlas = LocalSprites.current
    val skies = LocalContext.current.container.skies
    val night by rememberNight()
    val clock = remember { mutableLongStateOf(0L) }
    val hearts = remember { mutableStateListOf<Heart>() }
    val durationOf = remember(cat, atlas) {
        { name: String -> atlas.getOrNull("cat/${cat.color}/$name")?.let { it.frames * 1000L / it.entry.fps.coerceAtLeast(1) } ?: 1_000L }
    }
    LaunchedEffect(cat) {
        var last = withFrameMillis { it }
        val start = last
        while (true) {
            withFrameMillis { now ->
                val dt = (now - last).coerceAtMost(100)
                last = now
                clock.longValue = now - start
                cat.update(clock.longValue, dt, LocalTime.now().hour, durationOf)
                if (hearts.isNotEmpty()) {
                    val wall = System.currentTimeMillis()
                    hearts.removeAll { wall - it.born > 800 }
                }
            }
            // sprites animate at 2-10 fps and the scene is drawn small: ~25 fps is plenty
            delay(40)
        }
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val scene = remember(constraints.maxWidth, constraints.maxHeight) { PlanetScene.of(constraints.maxWidth, constraints.maxHeight) }
        Crossfade(night, animationSpec = tween(1_500), label = "sky") { dark ->
            ParallaxSky(
                skies.sky(if (dark) SkyLibrary.HOME_NIGHT else SkyLibrary.HOME_DAY),
                Modifier.fillMaxSize(),
                walked = { cat.walked },
            )
        }
        val frame = remember(scene) { ImageBitmap(scene.width, scene.height) }
        val frameCanvas = remember(frame) { ComposeCanvas(frame) }
        val frameScope = remember { CanvasDrawScope() }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(cat, scene) {
                    val slop = viewConfiguration.touchSlop
                    val longMs = viewConfiguration.longPressTimeoutMillis
                    coroutineScope {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val (sx, sy) = scene.toScene(down.position.x, down.position.y)
                            if (!Cat.hit(sx, sy, scene.feetX, scene.feetY)) return@awaitEachGesture
                            var moved = 0f
                            var petting = false
                            var held = false
                            val longJob = launch {
                                delay(longMs)
                                if (!petting) {
                                    held = true
                                    onCatLongPress()
                                }
                            }
                            try {
                                while (true) {
                                    val change = awaitPointerEvent().changes.firstOrNull() ?: break
                                    if (change.changedToUp() || !change.pressed) {
                                        if (!petting && !held) onCatTap()
                                        break
                                    }
                                    val delta = change.positionChange()
                                    moved += abs(delta.x) + abs(delta.y)
                                    if (!petting && !held && moved > slop) {
                                        petting = true
                                        longJob.cancel()
                                        cat.petStart()
                                        onPet()
                                    }
                                    if (petting) {
                                        change.consume()
                                        val wall = System.currentTimeMillis()
                                        val (px, py) = scene.toScene(change.position.x, change.position.y)
                                        if (hearts.isEmpty() || wall - hearts.last().born > 140) {
                                            hearts.add(Heart(px + Random.nextInt(-6, 7), py - 22f, wall))
                                        }
                                    }
                                }
                            } finally {
                                longJob.cancel()
                                if (petting) {
                                    cat.petEnd()
                                    onPetEnd()
                                }
                            }
                        }
                    }
                },
        ) {
            val now = clock.longValue
            val planet = skies.planet(if (night) "moon" else "earth")
            frameScope.draw(Density(1f), LayoutDirection.Ltr, frameCanvas, Size(scene.width.toFloat(), scene.height.toFloat())) {
                drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                with(SeasonOverlay) { behind(look.season, now, scene.width.toFloat(), scene.height.toFloat()) }
                val left = (scene.cx - planet.width / 2f).roundToInt()
                val top = (scene.cy - planet.height / 2f).roundToInt()
                rotate(PlanetScene.turn(cat.walked, scene.radius), pivot = Offset(left + planet.width / 2f, top + planet.height / 2f)) {
                    drawImage(planet, dstOffset = IntOffset(left, top), dstSize = IntSize(planet.width, planet.height), filterQuality = FilterQuality.None)
                }
                drawOval(Color(0x33000000), Offset(scene.feetX - 8f, scene.feetY - 2f), Size(16f, 3f))
                cat.draw(this, atlas, now, scene.feetX, scene.feetY)
                with(SeasonOverlay) { front(look.season, now, scene.width.toFloat(), scene.height.toFloat(), atlas) }
                val wall = System.currentTimeMillis()
                for (h in hearts) {
                    val t = ((wall - h.born) / 800f).coerceIn(0f, 1f)
                    if (t < 1f) pixelHeart(h.x, h.y - t * 12f, Pal.Red.copy(alpha = 1f - t))
                }
            }
            drawImage(
                frame,
                dstOffset = IntOffset(scene.offsetX, scene.offsetY),
                dstSize = IntSize(scene.width * scene.scale, scene.height * scene.scale),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

private class Heart(val x: Float, val y: Float, val born: Long)

/** A 7×6 pixel heart, one scene pixel per cell, feet of the glyph at [y]. */
private fun DrawScope.pixelHeart(x: Float, y: Float, color: Color) {
    val cells = intArrayOf(
        1, 0, 2, 0, 4, 0, 5, 0,
        0, 1, 1, 1, 2, 1, 3, 1, 4, 1, 5, 1, 6, 1,
        0, 2, 1, 2, 2, 2, 3, 2, 4, 2, 5, 2, 6, 2,
        1, 3, 2, 3, 3, 3, 4, 3, 5, 3,
        2, 4, 3, 4, 4, 4,
        3, 5,
    )
    var i = 0
    while (i < cells.size) {
        drawRect(color, Offset((x - 3f + cells[i]).toInt().toFloat(), (y - 5f + cells[i + 1]).toInt().toFloat()), Size(1f, 1f))
        i += 2
    }
}
