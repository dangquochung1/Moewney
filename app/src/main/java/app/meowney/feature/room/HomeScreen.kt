package app.meowney.feature.room

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.meowney.core.CatEvent
import app.meowney.core.db.RecurringEntity
import app.meowney.core.money.AmountInput
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelKeypad
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.sprite.Sprite
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawNinePatch
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelButton
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.RavenIcon
import app.meowney.core.ui.SpeechBubble
import app.meowney.core.ui.ZoomSource
import app.meowney.core.ui.rememberZoomSource
import app.meowney.core.ui.zoomSource
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

private val WEEKDAYS = arrayOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

/** What home can ask the app to do. */
class HomeActions(
    val onSettings: () -> Unit,
    val onMoney: () -> Unit,
    val onAdd: () -> Unit,
    val onQuick: (QuickTemplate) -> Unit,
    val onPin: (QuickTemplate) -> Unit,
    val onNoSpend: () -> Unit,
    val onPayBill: (RecurringEntity, Long) -> Unit,
    val onSkipBill: (RecurringEntity) -> Unit,
    val onBackupNudged: () -> Unit,
    val onHabits: () -> Unit,
)

@Composable
fun HomeScreen(
    corner: RoomAssets,
    pixelTypeface: Typeface,
    state: HomeState,
    catEvent: CatEvent?,
    onCatEventHandled: () -> Unit,
    actions: HomeActions,
) {
    val m = LocalPixelMetrics.current
    val cat = remember(state.catColor) { Cat(state.catColor, corner.circle, corner.spots) }
    var bubble by remember { mutableStateOf<String?>(CatLines.greeting(LocalDateTime.now().hour, state.catName)) }
    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(3_500)
            bubble = null
        }
    }
    LaunchedEffect(state.mood) { cat.mood = state.mood }
    // once a day, a week after the last backup (after the wake-up intro is over)
    LaunchedEffect(state.backupDays > 0 && !state.backupNudged) {
        if (state.backupDays > 0 && !state.backupNudged) {
            delay(4_000)
            bubble = CatLines.backup(state.backupDays)
            actions.onBackupNudged()
        }
    }
    val zoomSettings = rememberZoomSource()
    val zoomMoney = rememberZoomSource()
    val zoomHabits = rememberZoomSource()
    val zoomAdd = rememberZoomSource()
    var paying by remember { mutableStateOf<RecurringEntity?>(null) }
    paying?.let { bill ->
        var amount by remember(bill) { mutableStateOf(AmountInput(bill.amount)) }
        PixelDialog(onDismiss = { paying = null }) {
            PixelText("Trả ${bill.name}", style = LocalPixelType.current.title)
            PixelText(formatVnd(amount.value), style = LocalPixelType.current.display, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
            PixelKeypad(onKey = { amount = amount.press(it) })
            PixelButton(onClick = {
                if (amount.value > 0) actions.onPayBill(bill, amount.value)
                paying = null
            }, modifier = Modifier.fillMaxWidth()) {
                PixelText("Đã trả", style = LocalPixelType.current.title, color = Pal.Cream)
            }
        }
    }
    LaunchedEffect(catEvent) {
        when (val e = catEvent ?: return@LaunchedEffect) {
            is CatEvent.Fed -> {
                cat.feed()
                bubble = CatLines.fed(e.label, formatVndShort(e.amount)) + coinsSuffix(e.coins)
            }
            is CatEvent.Poured -> {
                cat.play()
                bubble = (e.notes.firstOrNull() ?: CatLines.poured(formatVndShort(e.amount))) + coinsSuffix(e.coins)
            }
            is CatEvent.Coins -> {
                cat.play()
                bubble = e.text
            }
            is CatEvent.Say -> bubble = e.text
        }
        onCatEventHandled()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Pal.Night)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val maxWidth = constraints.maxWidth
        val scale = stageScale(maxWidth, constraints.maxHeight, corner.circle.diameter)
        Column(Modifier.fillMaxSize().padding(horizontal = m.unit * 2)) {
            Hud(state.coins, zoomSettings, actions.onSettings)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                CatStage(
                    corner = corner,
                    cat = cat,
                    scale = scale,
                    onCatTap = {
                        cat.poke()
                        bubble = if (state.hasLoans) CatLines.worriedLoans() else CatLines.onTap()
                    },
                    onCatLongPress = {
                        cat.forceSleep = !cat.forceSleep
                        bubble = CatLines.sleepToggle(cat.forceSleep)
                    },
                )
                bubble?.let {
                    SpeechBubble(
                        it,
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = m.unit * 2)
                            .widthIn(max = m.dp(maxWidth * 85 / 100)),
                    )
                }
            }
            ToyRow(
                typeface = pixelTypeface,
                jarLevel = state.jarLevel,
                moneyZoom = zoomMoney,
                habitsZoom = zoomHabits,
                onToy = { toy ->
                    when (toy) {
                        Toy.Money -> {
                            zoomMoney.mark()
                            actions.onMoney()
                        }
                        Toy.Habits -> {
                            zoomHabits.mark()
                            actions.onHabits()
                        }
                        else -> bubble = CatLines.onToy(toy)
                    }
                },
            )
            TodayPanel(
                state = state,
                modifier = Modifier.weight(1f),
                onQuick = actions.onQuick,
                onPin = { t ->
                    actions.onPin(t)
                    bubble = if (t.pinned) "Bỏ ghim ${t.label} rồi." else "Ghim ${t.label} ${formatVndShort(t.amount)} lên đầu nha."
                },
                addZoom = zoomAdd,
                onAdd = {
                    zoomAdd.mark()
                    actions.onAdd()
                },
                onSettings = {
                    zoomSettings.mark()
                    actions.onSettings()
                },
                onNoSpend = actions.onNoSpend,
                onPayBill = { paying = it },
                onSkipBill = actions.onSkipBill,
            )
            Spacer(Modifier.height(m.unit * 2))
        }
    }
}

private fun coinsSuffix(coins: Int) = if (coins > 0) " (+$coins xu)" else ""

// ------------------------------------------------------------------ HUD

@Composable
private fun Hud(coins: Long, zoom: ZoomSource, onSettings: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val atlas = LocalSprites.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = m.unit * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val now = LocalDateTime.now()
        PixelPanel {
            PixelText(
                "${WEEKDAYS[now.dayOfWeek.value - 1]} %02d/%02d".format(now.dayOfMonth, now.monthValue),
                style = type.bodyBold, color = Pal.Ink,
            )
        }
        Spacer(Modifier.weight(1f))
        PixelButton(
            onClick = {
                zoom.mark()
                onSettings()
            },
            look = ButtonLook.Light, padded = false, modifier = Modifier.size(m.unit * 14).zoomSource(zoom),
        ) {
            RavenIcon(1, scale = m.ui - 1)
        }
        Spacer(Modifier.size(m.unit * 2))
        PixelPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoinIcon(atlas)
                PixelText(" $coins", style = type.bodyBold, color = Pal.Ink)
            }
        }
    }
}

@Composable
private fun CoinIcon(atlas: SpriteAtlas) {
    val m = LocalPixelMetrics.current
    val coin = atlas["coin/coin"]
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { clock.longValue = it }
            delay(1000L / coin.entry.fps.coerceAtLeast(1))
        }
    }
    Canvas(Modifier.size(m.dp(coin.width * m.ui), m.dp(coin.height * m.ui))) {
        drawImage(
            coin.page, coin.srcOffset(coin.frameAt(clock.longValue)), coin.srcSize,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None,
        )
    }
}

// ------------------------------------------------------------------ toys

@Composable
private fun ToyRow(typeface: Typeface, jarLevel: Float, moneyZoom: ZoomSource, habitsZoom: ZoomSource, onToy: (Toy) -> Unit) {
    val m = LocalPixelMetrics.current
    val atlas = LocalSprites.current
    val today = LocalDate.now()
    val scale = (m.ui - 1).coerceAtLeast(2)
    // quantise the level so the jar image is not rebuilt for every đồng
    val level = (jarLevel * 16).toInt() / 16f
    val images = remember(atlas, typeface, level, today) {
        Toy.entries.associateWith { ToyArt.image(it, atlas, typeface, level, today) }
    }
    val tallest = images.values.maxOf { it.height }
    Row(
        Modifier.fillMaxWidth().padding(vertical = m.unit * 2),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        for (toy in Toy.entries) {
            ToyButton(
                toy, images.getValue(toy), scale, tallest,
                modifier = when (toy) {
                    Toy.Money -> Modifier.zoomSource(moneyZoom)
                    Toy.Habits -> Modifier.zoomSource(habitsZoom)
                    else -> Modifier
                },
            ) { onToy(toy) }
        }
    }
}

@Composable
private fun ToyButton(toy: Toy, image: ImageBitmap, scale: Int, tallest: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Column(
        modifier.clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(m.dp(tallest * scale)), contentAlignment = Alignment.BottomCenter) {
            Canvas(
                Modifier
                    .size(m.dp(image.width * scale), m.dp(image.height * scale))
                    .offset(y = if (pressed) m.dp(scale) else m.dp(0)),
            ) {
                drawImage(
                    image,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None,
                    alpha = if (pressed) 0.8f else 1f,
                )
            }
        }
        PixelText(toy.label, style = type.caption, color = if (pressed) Pal.Gold else Pal.Cream, align = TextAlign.Center)
    }
}

// ------------------------------------------------------------------ today

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TodayPanel(
    state: HomeState,
    modifier: Modifier,
    onQuick: (QuickTemplate) -> Unit,
    onPin: (QuickTemplate) -> Unit,
    addZoom: ZoomSource,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onNoSpend: () -> Unit,
    onPayBill: (RecurringEntity) -> Unit,
    onSkipBill: (RecurringEntity) -> Unit,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelPanel(modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        ) {
            if (state.billsDue.isNotEmpty()) {
                PixelText("CẦN TRẢ", style = type.caption, color = Pal.Red)
                for (b in state.billsDue) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        Column(Modifier.weight(1f)) {
                            PixelText(b.name, style = type.bodyBold, maxLines = 1)
                            PixelText(formatVnd(b.amount), style = type.caption, color = Pal.InkSoft)
                        }
                        PixelButton(onClick = { onPayBill(b) }, padded = true) { PixelText("Đã trả", style = type.caption, color = Pal.Cream) }
                        PixelButton(onClick = { onSkipBill(b) }, look = ButtonLook.Light) { PixelText("Bỏ qua", style = type.caption) }
                    }
                }
            }
            Column {
                PixelText("HÔM NAY CÒN ĐƯỢC TIÊU", style = type.caption, color = Pal.InkSoft)
                PixelText(
                    formatVnd(state.leftToday),
                    style = type.display,
                    color = if (state.leftToday < 0) Pal.Red else Pal.Ink,
                )
                PixelText(
                    if (state.spentToday == 0L) "Hôm nay chưa tiêu đồng nào" else "Đã tiêu hôm nay ${formatVnd(state.spentToday)}",
                    style = type.caption, color = Pal.InkSoft,
                )
                if (state.reserved > 0) {
                    PixelText("Đã chừa ${formatVnd(state.reserved)} cho khoản định kỳ", style = type.caption, color = Pal.InkSoft)
                }
            }
            if (state.templates.isNotEmpty()) {
                PixelText("Ghi nhanh (giữ lâu để ghim)", style = type.bodyBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
                    verticalArrangement = Arrangement.spacedBy(m.unit * 2),
                ) {
                    for (t in state.templates) {
                        TemplateChip(t, onClick = { onQuick(t) }, onLongClick = { onPin(t) })
                    }
                }
            }
            PixelButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().zoomSource(addZoom)) {
                PixelText("+ Ghi chi tiêu", style = type.title, color = Pal.Cream)
            }
            if (state.backupDays > 0) {
                PixelButton(onClick = onSettings, modifier = Modifier.fillMaxWidth(), look = ButtonLook.Light) {
                    PixelText("Sao lưu dữ liệu (${state.backupDays} ngày chưa lưu)", style = type.bodyBold, color = Pal.Ink)
                }
            }
            if (state.canClaimNoSpend) {
                PixelButton(onClick = onNoSpend, modifier = Modifier.fillMaxWidth(), look = ButtonLook.Light) {
                    PixelText("Hôm nay Sen không tiêu gì", style = type.bodyBold, color = Pal.Ink)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateChip(t: QuickTemplate, onClick: () -> Unit, onLongClick: () -> Unit) {
    val type = LocalPixelType.current
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val art = atlas[if (t.pinned) "ui/wood/button_normal" else "ui/wood/button_hover"]
    Box(
        Modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .drawNine(art, m.ui)
            .padding(horizontal = m.unit * 4, vertical = m.unit * 2),
    ) {
        PixelText(
            (if (t.pinned) "* " else "") + "${t.label} ${formatVndShort(t.amount)}",
            style = type.bodyBold,
            color = if (t.pinned) Pal.Cream else Pal.Ink,
            maxLines = 1,
        )
    }
}

private fun Modifier.drawNine(sprite: Sprite, scale: Int) = drawBehind {
    drawNinePatch(sprite, IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), scale)
}
