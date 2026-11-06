package app.meowney.feature.room

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import app.meowney.core.format.formatVnd
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelButton
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.SpeechBubble
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** What the room shows; GĐ1 fills these from the money engine. */
data class RoomState(
    val catColor: Int = 1,
    val coins: Long = 0,
    val leftToday: Long? = null,
    val habitsDone: Int = 0,
    val habitsTotal: Int = 0,
    /** Fill level 0..1 of the 6 jars, in method order. */
    val jarLevels: List<Float> = listOf(0.8f, 0.35f, 0.9f, 0.2f, 0.6f, 1f),
)

private val WEEKDAYS = arrayOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

@Composable
fun RoomScreen(
    room: RoomAssets,
    pixelTypeface: Typeface,
    state: RoomState,
    onHotspot: (String) -> Unit,
    onOpenUiKit: () -> Unit,
) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current

    val cat = remember(state.catColor) {
        val (sx, sy) = room.spot("rug")
        Cat(state.catColor, sx, sy, room)
    }
    val clock = remember { mutableLongStateOf(0L) }
    var bubble by remember { mutableStateOf<Pair<String, Long>?>(null) }

    // Game loop: advance the cat; the Canvas reads `clock` so only drawing re-runs.
    LaunchedEffect(cat) {
        var last = withFrameMillis { it }
        val start = last
        bubble = CatLines.greeting(LocalDateTime.now().hour) to 0L
        while (true) {
            withFrameMillis { now ->
                val dt = (now - last).coerceAtMost(100)
                last = now
                clock.longValue = now - start
                cat.update(clock.longValue, dt, atlas, LocalDateTime.now().hour)
            }
        }
    }
    // Bubbles fade after a few seconds.
    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(3_500)
            bubble = null
        }
    }

    val frame = remember(room) { ImageBitmap(room.widthPx, room.heightPx) }
    val frameScope = remember { CanvasDrawScope() }
    val frameCanvas = remember(frame) { ComposeCanvas(frame) }
    val textPaint = remember(pixelTypeface) {
        Paint().apply {
            typeface = pixelTypeface
            textSize = 9f
            isAntiAlias = false
            color = Pal.Ink.toArgb()
            textAlign = Paint.Align.CENTER
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Pal.Night),
    ) {
        val k = m.room
        val roomW = room.widthPx * k
        val roomH = room.heightPx * k
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight
        val left = (maxW - roomW) / 2
        val top = (maxH - roomH) / 2

        Canvas(
            Modifier
                .layout { measurable, _ ->
                    val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(roomW, roomH))
                    layout(maxW, maxH) { p.place(left, top) }
                }
                .pointerInput(cat) {
                    detectTapGestures { pos ->
                        val x = pos.x / k
                        val y = pos.y / k
                        if (cat.hit(x, y)) {
                            cat.poke(clock.longValue, atlas)
                            bubble = CatLines.onTap() to clock.longValue
                        } else {
                            room.hotspotAt(x, y)?.let { spot ->
                                bubble = CatLines.onHotspot(spot.id) to clock.longValue
                                onHotspot(spot.id)
                            }
                        }
                    }
                },
        ) {
            val now = clock.longValue
            frameScope.draw(
                Density(1f), LayoutDirection.Ltr, frameCanvas,
                Size(frame.width.toFloat(), frame.height.toFloat()),
            ) {
                drawImage(room.base)
                room.drawAnimatedTiles(this, now)
                drawCalendar(textPaint)
                drawJars(state.jarLevels)
                drawSprite(atlas["treasure/littletreasurechest"], 0, 40f, 256f)
                cat.draw(this, atlas, now)
            }
            drawImage(
                frame,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(roomW, roomH),
                filterQuality = FilterQuality.None,
            )
        }

        // Speech bubble above the cat's head.
        bubble?.let { (text, _) ->
            val headX = left + (cat.x * k).roundToInt()
            val headY = top + ((cat.y - 30) * k).roundToInt()
            Box(
                Modifier.layout { measurable, constraints ->
                    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (maxW * 0.8f).toInt()))
                    layout(maxW, maxH) {
                        p.place((headX - p.width / 2).coerceIn(8, maxW - p.width - 8), (headY - p.height).coerceAtLeast(8))
                    }
                },
            ) { SpeechBubble(text) }
        }

        // HUD: date + coins on top, "today" strip + quick add at the bottom.
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(m.unit * 2),
        ) {
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), verticalAlignment = Alignment.CenterVertically) {
                val now = LocalDateTime.now()
                PixelPanel {
                    PixelText(
                        "${WEEKDAYS[now.dayOfWeek.value - 1]} %02d/%02d".format(now.dayOfMonth, now.monthValue),
                        style = type.bodyBold, color = Pal.Ink,
                    )
                }
                Spacer(Modifier.weight(1f))
                PixelTextButton("UI", onClick = onOpenUiKit, look = ButtonLook.Light)
                Spacer(Modifier.size(m.unit * 2))
                PixelPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CoinIcon(atlas)
                        PixelText(" ${state.coins}", style = type.bodyBold, color = Pal.Ink)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
            ) {
                PixelPanel(Modifier.weight(1f)) {
                    val money = state.leftToday?.let { "Còn ${formatVnd(it)}" } ?: "Còn —"
                    PixelText(
                        "$money · Thói quen ${state.habitsDone}/${state.habitsTotal}",
                        style = type.bodyBold, color = Pal.Ink, maxLines = 1,
                    )
                }
                PixelButton(
                    onClick = {
                        bubble = CatLines.onHotspot("add") to clock.longValue
                        onHotspot("add")
                    },
                    modifier = Modifier.size(m.unit * 28),
                    padded = false,
                ) {
                    PixelText("+", style = type.display, color = Pal.Cream)
                }
            }
        }
    }
}

@Composable
private fun CoinIcon(atlas: SpriteAtlas) {
    val m = LocalPixelMetrics.current
    val coin = atlas["coin/coin"]
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { clock.longValue = it } }
    Canvas(Modifier.size(m.dp(coin.width * m.ui), m.dp(coin.height * m.ui))) {
        drawImage(
            coin.page, coin.srcOffset(coin.frameAt(clock.longValue)), coin.srcSize,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None,
        )
    }
}

// ------------------------------------------------------------------ room props drawn in code

/** Wall calendar showing today's day number (room pixels, beside the window). */
private fun DrawScope.drawCalendar(paint: Paint) {
    val x = 65f
    val y = 34f
    drawRect(Pal.Ink, Offset(x, y), Size(14f, 17f))
    drawRect(Pal.Cream, Offset(x + 1, y + 1), Size(12f, 15f))
    drawRect(Pal.Red, Offset(x + 1, y + 1), Size(12f, 4f))
    drawRect(Pal.Ink, Offset(x + 3, y - 1), Size(1f, 3f))
    drawRect(Pal.Ink, Offset(x + 10, y - 1), Size(1f, 3f))
    drawIntoCanvas {
        it.nativeCanvas.drawText("%02d".format(LocalDateTime.now().dayOfMonth), x + 7.5f, y + 15f, paint)
    }
}

/** The 6 jars on the shelf, each filled to its level. */
private fun DrawScope.drawJars(levels: List<Float>) {
    levels.forEachIndexed { i, level ->
        val x = (6 * 16 + 4 + (i % 3) * 15).toFloat()
        val y = (2 * 16 + 2 + (i / 3) * 16).toFloat()
        // lid
        drawRect(Pal.Wood, Offset(x + 1, y), Size(6f, 2f))
        // glass
        drawRect(Color(0xFFDDE8F0), Offset(x, y + 2), Size(8f, 10f))
        drawRect(Color(0xFF3A4A5A), Offset(x + 1, y + 3), Size(6f, 8f))
        // liquid, snapped to whole pixels
        val h = (8 * level.coerceIn(0f, 1f)).roundToInt()
        if (h > 0) {
            drawRect(Pal.Jars[i], Offset(x + 1, y + 11 - h), Size(6f, h.toFloat()))
            drawRect(Color.White.copy(alpha = 0.4f), Offset(x + 1, y + 11 - h), Size(6f, 1f))
        }
        // glint
        drawRect(Color.White.copy(alpha = 0.7f), Offset(x + 2, y + 4), Size(1f, 2f))
    }
}
