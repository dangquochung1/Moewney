package app.meowney.feature.room

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.SpeechBubble

private const val TITLE = "MEOWNEY"
private const val FULL_MS = 2_600L
private const val FADE_MS = 300L

/**
 * "Mèo thức dậy": the first open of the day, a light comes up on the sleeping cat, it
 * yawns, sits up and greets Sen. Later opens only fade in. Tap anywhere to skip.
 */
@Composable
fun WakeIntro(full: Boolean, catColor: Int, greeting: String?, onDone: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val atlas = LocalSprites.current
    var start by remember { mutableLongStateOf(-1L) }
    var t by remember { mutableLongStateOf(0L) }
    var skipAt by remember { mutableStateOf<Long?>(null) }
    val end = (skipAt ?: if (full) FULL_MS else 0L) + FADE_MS

    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { now ->
                if (start < 0) start = now
                t = now - start
            }
            if (t >= end) break
        }
        onDone()
    }

    val fadeFrom = skipAt ?: if (full) FULL_MS else 0L
    val alpha = if (t <= fadeFrom) 1f else (1f - (t - fadeFrom).toFloat() / FADE_MS).coerceIn(0f, 1f)

    Box(
        Modifier
            .fillMaxSize()
            .alpha(alpha)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                if (skipAt == null) skipAt = t
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawRect(Pal.Night) }
        if (!full) return@Box

        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val scale = (m.ui * 3).coerceAtLeast(4)
            Canvas(Modifier.fillMaxSize()) {
                // the light comes up: a warm disc that grows, with a wooden rim
                val grow = ((t - 300f) / 700f).coerceIn(0f, 1f)
                val radius = (w * 0.36f * grow / scale).toInt() * scale.toFloat()
                val c = Offset(w / 2, h * 0.45f)
                if (radius > 0) {
                    drawCircle(Pal.WoodDark, radius + scale * 2, c)
                    drawCircle(Color(0xFF4A3A32), radius, c)
                    drawCircle(Color(0x33F2C14E), radius * 0.7f, c)
                }
                // the cat: asleep, then a yawn, then sitting up with a meow
                val (anim, at) = when {
                    t < 900 -> "sleep1_r" to t
                    t < 1_700 -> "yawn_sit" to (t - 900)
                    else -> "meow_sit" to (t - 1_700)
                }
                val sprite = atlas.getOrNull("cat/$catColor/$anim") ?: atlas["cat/$catColor/sit"]
                val frame = if (anim == "meow_sit") sprite.frameAt(at).coerceAtMost(sprite.frames - 1) else sprite.frameAt(at)
                drawSprite(sprite, frame, c.x, c.y + sprite.height * scale / 2f, scale = scale, alpha = grow.coerceAtLeast(0.15f))
            }
            Column(
                Modifier.fillMaxSize().padding(top = m.dp((h * 0.45f - w * 0.36f).toInt() - m.ui * 40), start = m.unit * 4, end = m.unit * 4),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // the name types itself in, one letter every 90 ms
                val letters = (t / 90).toInt().coerceIn(0, TITLE.length)
                PixelText(
                    TITLE.take(letters).padEnd(TITLE.length, ' '),
                    style = type.display, color = Pal.Gold, align = TextAlign.Center,
                )
            }
            if (t > 1_800 && greeting != null) {
                SpeechBubble(
                    greeting,
                    Modifier
                        .align(Alignment.Center)
                        .padding(top = m.dp((h * 0.45f).toInt()))
                        .widthIn(max = m.dp((w * 0.85f).toInt()))
                        .fillMaxWidth(0.85f),
                )
            }
        }
    }
}

/** What the cat says when it wakes up. */
fun wakeGreeting(hour: Int, catName: String, left: Long?, leftText: (Long) -> String): String {
    val hello = when (hour) {
        in 5..10 -> "Chào buổi sáng Sen!"
        in 11..13 -> "Trưa rồi Sen ơi!"
        in 14..17 -> "Chiều rồi đó Sen!"
        else -> "Sen về rồi à!"
    }
    return if (left == null) "$hello $catName dậy rồi nè." else "$hello Hôm nay còn ${leftText(left)}."
}
