package app.meowney.feature.room

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.meowney.R
import app.meowney.core.sound.Sfx
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
import app.meowney.core.ui.PixelTextButton
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
    state: HomeState,
    catEvent: CatEvent?,
    onCatEventHandled: () -> Unit,
    actions: HomeActions,
) {
    val m = LocalPixelMetrics.current
    val cat = remember(state.catColor) { Cat(state.catColor) }
    var bubble by remember {
        val hour = LocalDateTime.now().hour
        mutableStateOf<String?>(
            if (CatClock.sleepHour(hour)) {
                CatLines.sleepyGreeting(state.catName)
            } else {
                app.meowney.core.game.SeasonEngine.seasonOn(java.time.LocalDate.now())?.let { CatLines.season(it) }
                    ?: CatLines.greeting(hour, state.catName)
            },
        )
    }
    // background music while home is on screen (paused when another screen or app is in front)
    val meowneyApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as app.meowney.MeowneyApp
    val music = meowneyApp.container.music
    val purr = meowneyApp.container.purr
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val track = app.meowney.core.game.SeasonEngine.music(state.look.season, java.time.LocalDate.now())
    androidx.compose.runtime.DisposableEffect(lifecycle, track) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> music.play(track)
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    music.pause()
                    purr.stop()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) music.play(track)
        onDispose {
            lifecycle.removeObserver(obs)
            music.pause()
            purr.stop()
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
                PixelText("Lưu", style = LocalPixelType.current.title, color = Pal.Ink)
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
                PixelText("Đã trả", style = LocalPixelType.current.title, color = Pal.Ink)
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

    // the cat's sky takes the top 57% of the screen, up under the status bar
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val stageHeight = m.dp((window.height * STAGE_SHARE).toInt())
    val night by rememberNight()
    app.meowney.core.ui.StatusBarIcons(darkBehind = night)
    Box(Modifier.fillMaxSize().background(Pal.Canvas)) {
        PlanetStage(
            cat = cat,
            modifier = Modifier.fillMaxWidth().height(stageHeight),
            look = state.look,
            onCatTap = {
                bubble = when {
                    !cat.poke() -> CatLines.sleepy()
                    state.hasLoans -> CatLines.worriedLoans()
                    else -> CatLines.onTap()
                }
            },
            onCatLongPress = actions.onWardrobe,
            onPet = purr::start,
            onPetEnd = purr::stop,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Hud(state.coins, zoomSettings, actions.onSettings, actions.onShop)
            bubble?.let { SpeechBubble(it, Modifier.padding(top = 10.dp).widthIn(max = 330.dp)) }
        }
        // today's things on a sheet tucked over the bottom edge of the sky
        TodayPanel(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = stageHeight - 20.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Pal.Canvas),
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
    }
}

/** Share of the screen height the cat's sky takes on home. */
private const val STAGE_SHARE = 0.57f

private fun coinsSuffix(coins: Int) = if (coins > 0) " (+$coins xu)" else ""

// ------------------------------------------------------------------ HUD

/** A white pill floating on the sky. */
@Composable
private fun Pill(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(Pal.Card)
            .border(1.dp, Pal.Line, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun Hud(coins: Long, zoom: ZoomSource, onSettings: () -> Unit, onCoins: () -> Unit) {
    val type = LocalPixelType.current
    val atlas = LocalSprites.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val now = LocalDateTime.now()
        Pill {
            PixelText("${WEEKDAYS[now.dayOfWeek.value - 1]} %02d/%02d".format(now.dayOfMonth, now.monthValue), style = type.bodyBold, color = Pal.Ink)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(38.dp)
                .zoomSource(zoom)
                .clip(CircleShape)
                .background(Pal.Card)
                .border(1.dp, Pal.Line, CircleShape)
                .clickable(role = Role.Button) {
                    zoom.mark()
                    onSettings()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = "Cài đặt", tint = Pal.Ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(8.dp))
        Pill(Modifier.clickable(onClick = onCoins)) {
            CoinIcon(atlas)
            PixelText(" $coins", style = type.bodyBold, color = Pal.Ink)
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
    val k = (m.ui - 1).coerceAtLeast(2)
    Canvas(Modifier.size(m.dp(coin.width * k), m.dp(coin.height * k))) {
        drawImage(
            coin.page, coin.srcOffset(coin.frameAt(clock.longValue)), coin.srcSize,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None,
        )
    }
}

// ------------------------------------------------------------------ today

/**
 * Under the sky, in this order: bills due, the big "+ Ghi chi tiêu", quick picks, the diary,
 * the month's challenge, today's habits and calendar, pinned notes, the backup reminder and
 * the no-spend line (each only when there is something to show).
 */
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
    val type = LocalPixelType.current
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.billsDue.isNotEmpty()) {
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PixelText("CẦN TRẢ", style = type.caption, color = Pal.Red)
                    for (b in state.billsDue) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                PixelText(b.name, style = type.bodyBold, maxLines = 1)
                                PixelText(formatVnd(b.amount), style = type.caption, color = Pal.InkSoft)
                            }
                            PixelTextButton("Đã trả", onClick = { onPayBill(b) })
                            PixelTextButton("Bỏ qua", onClick = { onSkipBill(b) }, look = ButtonLook.Light)
                        }
                    }
                }
            }
        }
        PixelButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(50.dp).zoomSource(addZoom)) {
            PixelText("+ Ghi chi tiêu", style = type.title, color = Pal.Ink)
        }
        if (state.templates.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PixelText("Ghi nhanh · giữ lâu để ghim", style = type.caption, color = Pal.InkSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (t in state.templates) TemplateChip(t, onClick = { onQuick(t) }, onLongClick = { onPin(t) })
                }
            }
        }
        PixelPanel(Modifier.fillMaxWidth()) { DiaryToday(state, habitActions) }
        state.challenge?.let { PixelPanel(Modifier.fillMaxWidth()) { ChallengeToday(it, habitActions) } }
        if (state.habits.isNotEmpty()) {
            PixelPanel(Modifier.fillMaxWidth()) { HabitsToday(state.habits, habitActions, onCountEdit) }
        }
        if (state.events.isNotEmpty() || state.todos.isNotEmpty() || state.overdue > 0) {
            PixelPanel(Modifier.fillMaxWidth()) { CalendarToday(state, habitActions) }
        }
        if (state.pinned.isNotEmpty()) PixelPanel(Modifier.fillMaxWidth()) { PinnedNotes(state, habitActions) }
        if (state.backupDays > 0) {
            PixelTextButton("Sao lưu dữ liệu (${state.backupDays} ngày chưa lưu)", onClick = onSettings, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        }
        PixelPanel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelText(
                    if (state.spentToday == 0L) "Hôm nay chưa tiêu" else "Đã tiêu hôm nay ${formatVnd(state.spentToday)}",
                    style = type.bodyBold, modifier = Modifier.weight(1f),
                )
                if (state.canClaimNoSpend) PixelTextButton("Không tiêu gì · nhận xu", onClick = onNoSpend, look = ButtonLook.Tab)
            }
        }
    }
}

/** A quick pick: one tap logs it, a long press pins it (pinned ones are Golden Dune). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TemplateChip(t: QuickTemplate, onClick: () -> Unit, onLongClick: () -> Unit) {
    val type = LocalPixelType.current
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (t.pinned) Pal.Selected else Pal.Card)
            .border(1.dp, Pal.Line, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        PixelText((if (t.pinned) "★ " else "") + "${t.label} ${formatVndShort(t.amount)}", style = type.bodyBold, color = Pal.Ink, maxLines = 1)
    }
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
                PixelText("+1", style = type.caption, color = if (h.done) Pal.Ink else Pal.Ink)
            }
            HabitType.TIMER -> PixelButton(onClick = { actions.onTimer(h) }, look = if (h.done) ButtonLook.Light else ButtonLook.Orange) {
                PixelText(if (h.done) "xong" else "Bắt đầu", style = type.caption, color = if (h.done) Pal.Ink else Pal.Ink)
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
            app.meowney.core.ui.PixelProgress(v.progress, color = if (v.done) Pal.Green else Pal.Mint, heightPx = 4)
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
