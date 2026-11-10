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
import app.meowney.core.data.HabitToday
import app.meowney.core.data.TodoItem
import app.meowney.core.ui.hhmm
import app.meowney.core.data.kind
import app.meowney.core.data.part
import app.meowney.core.db.RecurringEntity
import app.meowney.core.habit.HabitType
import app.meowney.core.habit.PartOfDay
import app.meowney.core.money.AmountInput
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelKeypad
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.feature.notes.paperSheet
import app.meowney.core.sprite.Sprite
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawNinePatch
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelButton
import app.meowney.core.ui.PixelCheckbox
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
    val onHabitTap: (HabitToday) -> Unit,
    val onHabitCount: (HabitToday, Int) -> Unit,
    val onHabitFailed: (HabitToday, Boolean) -> Unit,
    val onTimer: (HabitToday) -> Unit,
    val onCalendar: () -> Unit,
    val onTodo: (TodoItem) -> Unit,
    val onNotes: () -> Unit,
    /** Today's diary page; a mood picked on the panel comes along. */
    val onDiary: (Int?) -> Unit,
    val onNote: (Long) -> Unit,
    val onCheck: (Long) -> Unit,
    val onGift: () -> Unit,
    val onShop: () -> Unit,
    val onWardrobe: () -> Unit,
    val onChallenge: () -> Unit,
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
    var bubble by remember {
        mutableStateOf<String?>(
            app.meowney.core.game.SeasonEngine.seasonOn(java.time.LocalDate.now())?.let { CatLines.season(it) }
                ?: CatLines.greeting(LocalDateTime.now().hour, state.catName),
        )
    }
    // background music while home is on screen (paused when another screen or app is in front)
    val music = (androidx.compose.ui.platform.LocalContext.current.applicationContext as app.meowney.MeowneyApp).container.music
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val track = app.meowney.core.game.SeasonEngine.music(state.look.season, java.time.LocalDate.now())
    androidx.compose.runtime.DisposableEffect(lifecycle, track) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> music.play(track)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> music.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) music.play(track)
        onDispose {
            lifecycle.removeObserver(obs)
            music.pause()
        }
    }
    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(3_500)
            bubble = null
        }
    }
    LaunchedEffect(state.mood) { cat.mood = state.mood }
    LaunchedEffect(cat, state.look) {
        cat.propSprites = mapOf(Prop.Bed to state.look.bed, Prop.Bowl to state.look.bowl, Prop.Yarn to state.look.toy)
        if (state.birthday == null) cat.hat = state.look.hat
    }
    // a birthday today: the cat wears a hat and says so (after the wake-up intro)
    LaunchedEffect(state.birthday) {
        cat.hat = if (state.birthday != null) "santa" else state.look.hat
        if (state.birthday != null) {
            delay(3_000)
            bubble = CatLines.birthday(state.birthday)
        }
    }
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
    val zoomCalendar = rememberZoomSource()
    val zoomNotes = rememberZoomSource()
    val zoomGift = rememberZoomSource()
    val zoomAdd = rememberZoomSource()
    var counting by remember { mutableStateOf<HabitToday?>(null) }
    counting?.let { h ->
        var value by remember(h.habit.id) { mutableStateOf(h.value) }
        PixelDialog(onDismiss = { counting = null }) {
            PixelText(h.habit.name, style = LocalPixelType.current.title)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelButton(onClick = { value = (value - 1).coerceAtLeast(0) }, look = ButtonLook.Light) { PixelText("-", style = LocalPixelType.current.title) }
                PixelText("$value / ${h.habit.target} ${h.habit.unit}", style = LocalPixelType.current.title, modifier = Modifier.weight(1f), align = TextAlign.Center)
                PixelButton(onClick = { value += 1 }, look = ButtonLook.Light) { PixelText("+", style = LocalPixelType.current.title) }
            }
            PixelButton(onClick = {
                actions.onHabitCount(h, value)
                counting = null
            }, modifier = Modifier.fillMaxWidth()) {
                PixelText("Lưu", style = LocalPixelType.current.title, color = Pal.Cream)
            }
        }
    }
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
            is CatEvent.Treat -> {
                cat.feed()
                bubble = CatLines.ate(e.name)
            }
            is CatEvent.Mood -> {
                if (e.level >= 3) cat.cheer() else cat.comfort()
                bubble = e.text
            }
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
            Hud(state.coins, zoomSettings, actions.onSettings, actions.onShop)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                CatStage(
                    corner = corner,
                    cat = cat,
                    scale = scale,
                    onCatTap = {
                        cat.poke()
                        bubble = if (state.hasLoans) CatLines.worriedLoans() else CatLines.onTap()
                    },
                    onCatLongPress = actions.onWardrobe,
                    look = state.look,
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
                habitLevel = state.habitLevel,
                calendarCount = state.calendarCount(LocalDateTime.now().let { it.hour * 60 + it.minute }),
                catColor = state.catColor,
                moneyZoom = zoomMoney,
                habitsZoom = zoomHabits,
                calendarZoom = zoomCalendar,
                notesZoom = zoomNotes,
                giftZoom = zoomGift,
                diaryEmote = app.meowney.core.notes.Mood.of(state.diaryMood)?.emote,
                diaryDone = state.diaryWritten,
                tower = state.look.tower,
                giftBadge = state.spinReady,
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
                        Toy.Calendar -> {
                            zoomCalendar.mark()
                            actions.onCalendar()
                        }
                        Toy.Notes -> {
                            zoomNotes.mark()
                            actions.onNotes()
                        }
                        Toy.Gift -> {
                            zoomGift.mark()
                            actions.onGift()
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
                habitActions = actions,
                onCountEdit = { counting = it },
            )
            Spacer(Modifier.height(m.unit * 2))
        }
    }
}

private fun coinsSuffix(coins: Int) = if (coins > 0) " (+$coins xu)" else ""

// ------------------------------------------------------------------ HUD

@Composable
private fun Hud(coins: Long, zoom: ZoomSource, onSettings: () -> Unit, onCoins: () -> Unit) {
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
        PixelPanel(Modifier.clickable(onClick = onCoins)) {
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
private fun ToyRow(
    typeface: Typeface,
    jarLevel: Float,
    habitLevel: Float?,
    calendarCount: Int,
    catColor: Int,
    moneyZoom: ZoomSource,
    habitsZoom: ZoomSource,
    calendarZoom: ZoomSource,
    notesZoom: ZoomSource,
    giftZoom: ZoomSource,
    diaryEmote: String?,
    diaryDone: Boolean,
    tower: String,
    giftBadge: Boolean,
    onToy: (Toy) -> Unit,
) {
    val m = LocalPixelMetrics.current
    val atlas = LocalSprites.current
    val today = LocalDate.now()
    val scale = (m.ui - 1).coerceAtLeast(2)
    // quantise the level so the jar image is not rebuilt for every đồng
    val level = (jarLevel * 16).toInt() / 16f
    val images = remember(atlas, typeface, level, today, habitLevel, catColor, calendarCount, diaryEmote, diaryDone, tower, giftBadge) {
        Toy.entries.associateWith { ToyArt.image(it, atlas, typeface, level, today, habitLevel, catColor, calendarCount, diaryEmote, diaryDone, tower, giftBadge) }
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
                    Toy.Calendar -> Modifier.zoomSource(calendarZoom)
                    Toy.Notes -> Modifier.zoomSource(notesZoom)
                    Toy.Gift -> Modifier.zoomSource(giftZoom)
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
    habitActions: HomeActions,
    onCountEdit: (HabitToday) -> Unit,
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
            if (state.events.isNotEmpty() || state.todos.isNotEmpty() || state.overdue > 0) CalendarToday(state, habitActions)
            if (state.habits.isNotEmpty()) HabitsToday(state.habits, habitActions, onCountEdit)
            DiaryToday(state, habitActions)
            state.challenge?.let { ChallengeToday(it, habitActions) }
            if (state.pinned.isNotEmpty()) PinnedNotes(state, habitActions)
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

// ------------------------------------------------------------------ habits

/** Habits grouped by part of the day: the current part first, then the rest, done ones last. */
@Composable
private fun HabitsToday(items: List<HabitToday>, actions: HomeActions, onCountEdit: (HabitToday) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val now = PartOfDay.now(LocalDateTime.now().hour)
    val order = listOf(now, PartOfDay.ALL) + PartOfDay.entries.filter { it != now && it != PartOfDay.ALL }
    // quit habits stay in their part (they are "being held" all day)
    val open = items.filter { !it.done || it.habit.kind == HabitType.QUIT }
    val finished = items.filter { it.done && it.habit.kind != HabitType.QUIT }
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        PixelText("THÓI QUEN ${items.count { it.done }}/${items.size}", style = type.caption, color = Pal.InkSoft)
        for (part in order) {
            val group = open.filter { it.habit.part == part }
            if (group.isEmpty()) continue
            PixelText("- ${part.label} -", style = type.caption, color = Pal.Wood)
            for (h in group) HabitRow(h, actions, onCountEdit)
        }
        if (finished.isNotEmpty()) {
            PixelText("- Đã xong -", style = type.caption, color = Pal.Wood)
            for (h in finished) HabitRow(h, actions, onCountEdit)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HabitRow(h: HabitToday, actions: HomeActions, onCountEdit: (HabitToday) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val habit = h.habit
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when (habit.kind) {
                        HabitType.CHECK, HabitType.COUNT -> actions.onHabitTap(h)
                        HabitType.TIMER -> actions.onTimer(h)
                        HabitType.QUIT -> Unit
                    }
                },
                onLongClick = { if (habit.kind == HabitType.COUNT) onCountEdit(h) },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
    ) {
        RavenIcon(habit.icon, scale = (m.ui - 1).coerceAtLeast(2))
        Column(Modifier.weight(1f)) {
            PixelText(habit.name, style = type.bodyBold, maxLines = 1, color = if (h.done && habit.kind != HabitType.QUIT) Pal.InkSoft else Pal.Ink)
            val sub = when (habit.kind) {
                HabitType.CHECK -> if (h.done) "xong" else null
                HabitType.COUNT -> "${h.value}/${habit.target} ${habit.unit}".trim()
                HabitType.TIMER -> "${h.value}/${habit.target} phút"
                HabitType.QUIT -> if (h.failed) "lỡ rồi" else "đang giữ"
            }
            val week = if (habit.timesPerWeek > 0) "${h.weekDone}/${habit.timesPerWeek} tuần này" else null
            val line = listOfNotNull(sub, week).joinToString(" · ")
            if (line.isNotEmpty()) PixelText(line, style = type.caption, color = if (h.failed) Pal.Red else Pal.InkSoft)
        }
        when (habit.kind) {
            HabitType.CHECK -> PixelCheckbox(h.done, onCheckedChange = { actions.onHabitTap(h) })
            HabitType.COUNT -> PixelButton(onClick = { actions.onHabitTap(h) }, look = if (h.done) ButtonLook.Light else ButtonLook.Orange) {
                PixelText("+1", style = type.caption, color = if (h.done) Pal.Ink else Pal.Cream)
            }
            HabitType.TIMER -> PixelButton(onClick = { actions.onTimer(h) }, look = if (h.done) ButtonLook.Light else ButtonLook.Orange) {
                PixelText(if (h.done) "xong" else "Bắt đầu", style = type.caption, color = if (h.done) Pal.Ink else Pal.Cream)
            }
            HabitType.QUIT -> PixelButton(onClick = { actions.onHabitFailed(h, !h.failed) }, look = ButtonLook.Light) {
                PixelText(if (h.failed) "Hoàn tác" else "Lỡ rồi", style = type.caption)
            }
        }
    }
}

// ------------------------------------------------------------------ monthly challenge

@Composable
private fun ChallengeToday(v: app.meowney.core.data.ChallengeView, actions: HomeActions) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Column(Modifier.fillMaxWidth().clickable { actions.onChallenge() }, verticalArrangement = Arrangement.spacedBy(m.unit)) {
        PixelText("THỬ THÁCH THÁNG", style = type.caption, color = Pal.InkSoft)
        val p = v.picked
        if (p == null) {
            PixelText("Chọn thử thách tháng này >", style = type.bodyBold, color = Pal.Orange)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(p.label, style = type.bodyBold, modifier = Modifier.weight(1f), maxLines = 1)
                PixelText(
                    when {
                        v.claimed -> "đã nhận"
                        v.done -> "mở rương!"
                        app.meowney.core.game.ChallengeEngine.settledAtMonthEnd(p.kind) -> if (v.value >= p.target) "đang giữ" else "lỡ rồi"
                        else -> "${v.value}/${p.target}"
                    },
                    style = type.caption, color = if (v.done && !v.claimed) Pal.Orange else Pal.InkSoft,
                )
            }
            app.meowney.core.ui.PixelProgress(v.progress, color = if (v.done) Pal.Green else Pal.Gold, heightPx = 4)
        }
    }
}

// ------------------------------------------------------------------ notebook

@Composable
private fun DiaryToday(state: HomeState, actions: HomeActions) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val mood = app.meowney.core.notes.Mood.of(state.diaryMood)
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        PixelText("NHẬT KÝ", style = type.caption, color = Pal.InkSoft, modifier = Modifier.clickable { actions.onDiary(null) })
        if (mood == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelText("Hôm nay Sen thấy sao?", style = type.bodyBold, modifier = Modifier.weight(1f), maxLines = 2)
                for (md in app.meowney.core.notes.Mood.entries) {
                    app.meowney.feature.notes.MoodIcon(md, scale = (m.ui - 2).coerceAtLeast(2), Modifier.clickable { actions.onDiary(md.level) }, framed = true)
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().clickable { actions.onDiary(null) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
            ) {
                app.meowney.feature.notes.MoodIcon(mood, scale = (m.ui - 2).coerceAtLeast(2), framed = true)
                PixelText(mood.label, style = type.bodyBold)
                PixelText(if (state.diaryWritten) "· đã viết" else "· viết vài dòng nhé", style = type.caption, color = Pal.InkSoft, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PinnedNotes(state: HomeState, actions: HomeActions) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        PixelText("GHIM", style = type.caption, color = Pal.InkSoft)
        for (n in state.pinned) {
            Column(
                Modifier.fillMaxWidth()
                    .paperSheet(app.meowney.feature.notes.paper(n.note.color), m.ui.toFloat())
                    .padding(horizontal = m.unit * 3, vertical = m.unit * 2),
                verticalArrangement = Arrangement.spacedBy(m.unit),
            ) {
                PixelText(
                    n.note.title.ifBlank { "Ghi chú" }, style = type.bodyBold, maxLines = 1,
                    modifier = Modifier.fillMaxWidth().clickable { actions.onNote(n.id) },
                )
                val checks = n.checks
                val open = checks.filter { !it.checked }
                for (b in (open + checks.filter { it.checked }).take(6)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.onCheck(b.id) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
                    ) {
                        app.meowney.feature.notes.MiniCheck(b.checked, px = 9)
                        PixelText(
                            b.text, style = type.body.copy(textDecoration = if (b.checked) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                            color = if (b.checked) Pal.InkSoft else Pal.Ink, maxLines = 1,
                        )
                    }
                }
                if (checks.isEmpty()) {
                    n.blocks.firstOrNull { it.text.isNotBlank() }?.let {
                        PixelText(it.text.trim(), style = type.caption, color = Pal.Ink, maxLines = 2, modifier = Modifier.clickable { actions.onNote(n.id) })
                    }
                } else if (checks.size > 6) {
                    PixelText("${open.size} việc còn lại", style = type.caption, color = Pal.InkSoft, modifier = Modifier.clickable { actions.onNote(n.id) })
                }
            }
        }
    }
}

// ------------------------------------------------------------------ calendar

@Composable
private fun CalendarToday(state: HomeState, actions: HomeActions) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val now = LocalDateTime.now().let { it.hour * 60 + it.minute }
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        PixelText("LỊCH", style = type.caption, color = Pal.InkSoft, modifier = Modifier.clickable { actions.onCalendar() })
        for (e in state.eventsLeft(now)) {
            Row(
                Modifier.fillMaxWidth().clickable { actions.onCalendar() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
            ) {
                Canvas(Modifier.size(m.unit * 3)) { drawRect(androidx.compose.ui.graphics.Color(e.color)) }
                val time = when {
                    e.occ.multiDay -> "Ngày ${java.time.temporal.ChronoUnit.DAYS.between(e.occ.start, java.time.LocalDate.now()) + 1}"
                    e.occ.allDay || e.occ.startMinute == null -> "Cả ngày"
                    else -> hhmm(e.occ.startMinute)
                }
                PixelText(time, style = type.caption, color = Pal.InkSoft)
                PixelText(e.event.title, style = type.bodyBold, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
        for (t in state.todos) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelText(
                    (if (t.todo.starred) "★ " else "") + t.todo.title + (t.todo.dueMinute?.let { " · " + hhmm(it) } ?: "") +
                        if (t.steps.isNotEmpty()) " · ${t.steps.count { it.done }}/${t.steps.size}" else "",
                    style = type.bodyBold, maxLines = 1, color = if (t.done) Pal.InkSoft else Pal.Ink,
                    modifier = Modifier.weight(1f).clickable { actions.onCalendar() },
                )
                PixelCheckbox(t.done, onCheckedChange = { actions.onTodo(t) })
            }
        }
        if (state.overdue > 0) {
            PixelText("Quá hạn: ${state.overdue} việc >", style = type.caption, color = Pal.Red, modifier = Modifier.clickable { actions.onCalendar() })
        }
    }
}
