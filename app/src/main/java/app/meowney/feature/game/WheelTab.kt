package app.meowney.feature.game

import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import app.meowney.container
import app.meowney.core.game.Catalog
import app.meowney.core.game.Prize
import app.meowney.core.game.SpinOutcome
import app.meowney.core.game.WheelEngine
import app.meowney.core.sound.Sfx
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/** Frames of the wheel art: each turns it 7.5° clockwise; at frame 0 a slot sits right under the pointer. */
private const val FRAMES = 48

/** The frame where slot [k] (clockwise from the top at frame 0) is under the pointer. */
private fun frameFor(k: Int) = Math.floorMod(FRAMES - 6 * k, FRAMES)

/** What a prize looks like on the wheel: a sprite, or a short text. */
private fun Prize.icon(): Pair<String?, String?> = when (this) {
    Prize.COINS5 -> null to "5"
    Prize.COINS10 -> null to "10"
    Prize.COINS20 -> null to "20"
    Prize.COINS50 -> null to "50"
    Prize.TREAT -> "crop/fish" to null
    Prize.TICKET -> "treasure/goldkey" to null
    Prize.BOOST -> null to "x2"
    Prize.RARE -> "treasure/littletreasurechest" to null
}

fun SpinOutcome.text(): String = when {
    itemId != null -> "Trúng ${Catalog[itemId]?.name ?: itemId}!" + if (Catalog[itemId]?.slot == app.meowney.core.game.Slot.FOOD) " Cho mèo ăn ở Tủ đồ nhé." else " Vào Tủ đồ để dùng."
    ticketHabit != null -> "Trúng vé nghỉ cho \"$ticketHabit\". Lỡ một hôm cũng không mất chuỗi."
    boost -> "Hôm nay ghi sổ được gấp đôi xu!"
    coins > 0 -> "+$coins xu!"
    else -> prize.label
}

@Composable
fun WheelTab(vm: GiftViewModel) {
    val c = LocalContext.current.container
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val atlas = LocalSprites.current
    val left by vm.spinsLeft.collectAsState()
    val allDone by vm.allHabitsDone.collectAsState()
    val lixi by vm.lixiReady.collectAsState()
    val scope = rememberCoroutineScope()
    val turn = remember { Animatable(0f) }
    var spinning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpinOutcome?>(null) }
    val paint = remember {
        Paint().apply {
            typeface = c.typeface
            isAntiAlias = false
            textAlign = Paint.Align.CENTER
            color = Pal.Ink.toArgb()
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(m.unit * 3),
    ) {
        val k = m.ui + 2
        Canvas(Modifier.size(m.dp(72 * k), m.dp(84 * k))) {
            val frame = Math.floorMod(turn.value.toInt(), FRAMES)
            val wheel = atlas.getOrNull("ui/wheel/turn_%02d".format(frame)) ?: return@Canvas
            val top = 10f * k
            drawSprite(wheel, 0, size.width / 2, top + 72f * k, scale = k)
            drawPrizes(atlas, paint, frame, size.width / 2, top + 36f * k, 22f * k, k)
            atlas.getOrNull("ui/wheel/pointer")?.let { drawSprite(it, 0, size.width / 2, top + 6f * k, scale = k) }
        }
        PixelText(
            when {
                left > 0 -> "Hôm nay còn $left lượt quay"
                allDone -> "Hết lượt hôm nay. Mai quay tiếp nhé!"
                else -> "Hết lượt. Xong hết thói quen hôm nay để được thêm 1 lượt."
            },
            style = type.body, color = Pal.Cream, align = TextAlign.Center,
        )
        PixelTextButton(if (spinning) "..." else "QUAY!", enabled = left > 0 && !spinning, modifier = Modifier.fillMaxWidth(), onClick = {
            spinning = true
            scope.launch {
                val r = vm.spin()
                if (r == null) {
                    spinning = false
                    return@launch
                }
                val (index, outcome) = r
                c.sfx.play(Sfx.Sound.Click)
                val start = turn.value
                val now = Math.floorMod(start.toInt(), FRAMES)
                val delta = Math.floorMod(frameFor(index) - now, FRAMES)
                turn.animateTo(start + FRAMES * 4 + delta, tween(3_600, easing = CubicBezierEasing(0.1f, 0.6f, 0.2f, 1f)))
                c.sfx.play(if (outcome.coins >= 20 || outcome.itemId != null) Sfx.Sound.Gold else Sfx.Sound.Coin)
                result = outcome
                spinning = false
            }
        })
        if (lixi) {
            PixelTextButton("Nhận lì xì Tết", onClick = { vm.claimLixi() }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        }
        PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
            Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelText("Giải thưởng", style = type.bodyBold, color = Pal.Ink)
                PixelText("Xu 5 / 10 / 20 / 50 · món ăn cho mèo · vé nghỉ thói quen · x2 xu ghi sổ cả ngày · rương đồ hiếm (đồ trang trí ngẫu nhiên).", style = type.caption, color = Pal.InkSoft)
                PixelText("Mỗi ngày 1 lượt, xong hết thói quen thêm 1 lượt. Lượt không để dành qua hôm sau.", style = type.caption, color = Pal.InkSoft)
            }
        }
    }
    result?.let { r ->
        PixelDialog(onDismiss = { result = null }) {
            PixelText(r.prize.label.uppercase(), style = type.title, color = Pal.Orange)
            PixelText(r.text(), style = type.body, color = Pal.Ink)
            PixelTextButton("Tuyệt!", onClick = { result = null }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Prize icons on their slots, turning with the wheel (the icons stay upright). */
private fun DrawScope.drawPrizes(atlas: SpriteAtlas, paint: Paint, frame: Int, cx: Float, cy: Float, r: Float, k: Int) {
    paint.textSize = 9f * k
    WheelEngine.segments.forEachIndexed { slot, prize ->
        val deg = 45.0 * slot + 7.5 * frame
        val a = Math.toRadians(deg)
        val x = cx + (r * sin(a)).toFloat()
        val y = cy - (r * cos(a)).toFloat()
        val (sprite, label) = prize.icon()
        if (sprite != null) {
            atlas.getOrNull(sprite)?.let { drawSprite(it, 0, x, y + it.height * k / 2f, scale = k) }
        } else if (label != null) {
            drawIntoCanvas { canvas ->
                paint.color = Pal.Night.toArgb()
                canvas.nativeCanvas.drawText(label, x + k, y + 4f * k, paint)
                paint.color = Pal.Cream.toArgb()
                canvas.nativeCanvas.drawText(label, x, y + 3f * k, paint)
            }
        }
    }
}

