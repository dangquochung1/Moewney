package app.meowney.feature.calendar

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.container
import app.meowney.core.CatEvent
import app.meowney.core.calendar.CalendarEngine
import app.meowney.core.calendar.EventRepeat
import app.meowney.core.calendar.rep
import app.meowney.core.data.CalDay
import app.meowney.core.data.EventItem
import app.meowney.core.data.TodoItem
import app.meowney.core.db.CalLabelEntity
import app.meowney.core.db.TodoStepEntity
import app.meowney.core.format.formatVndShort
import app.meowney.core.habit.HabitEngine
import app.meowney.core.notify.CalendarReminder
import app.meowney.core.sound.Sfx
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.ParallaxSky
import app.meowney.core.ui.PixelCheckbox
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelTabs
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.SkyLibrary
import app.meowney.core.ui.StatusBarIcons
import app.meowney.core.ui.WEEKDAYS_SHORT
import app.meowney.core.ui.hhmm
import app.meowney.core.ui.shortDate
import app.meowney.feature.room.rememberNight
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class CalView(val label: String) { DAY("Ngày"), WEEK("Tuần"), MONTH("Tháng") }

data class CalendarUi(
    val view: CalView = CalView.DAY,
    val month: YearMonth = YearMonth.now(),
    val selected: LocalDate = LocalDate.now(),
    val days: Map<LocalDate, CalDay> = emptyMap(),
    val labels: List<CalLabelEntity> = emptyList(),
    /** Labels shown; empty = all. */
    val filter: Set<Long> = emptySet(),
    val inbox: List<TodoItem> = emptyList(),
    val overdue: List<TodoItem> = emptyList(),
    /** The goal of the selected week, and which week (its Monday) it belongs to. */
    val goal: String = "",
    val goalWeek: LocalDate? = null,
    /** Today's habits: done and due (null until loaded). */
    val habitsToday: Pair<Int, Int>? = null,
) {
    fun day(d: LocalDate): CalDay = filtered(days[d] ?: CalDay(d))

    private fun filtered(d: CalDay): CalDay = if (filter.isEmpty()) d else d.copy(
        events = d.events.filter { it.event.labelId in filter },
        todos = d.todos.filter { it.todo.labelId in filter },
    )
}

/** First Monday shown and last Sunday shown for a month (6 rows). */
fun gridRange(month: YearMonth): Pair<LocalDate, LocalDate> {
    val first = month.atDay(1)
    val start = first.minusDays((first.dayOfWeek.value - 1).toLong())
    return start to start.plusDays(41)
}

/** The day picked when a week opens: today in this week, else its Monday. */
fun dayPickedInWeek(anyDay: LocalDate, today: LocalDate): LocalDate {
    val monday = HabitEngine.weekStart(anyDay)
    return if (!today.isBefore(monday) && today.isBefore(monday.plusDays(7))) today else monday
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(private val c: AppContainer, startDay: LocalDate, startView: CalView) : ViewModel() {
    private val view = MutableStateFlow(startView)
    private val selected = MutableStateFlow(startDay)
    private val filter = MutableStateFlow<Set<Long>>(emptySet())

    /** The month grid, and a week either side of the chosen day for the day and week views. */
    private val range = selected.map { d ->
        val (a, b) = gridRange(YearMonth.from(d))
        minOf(a, d.minusDays(7)) to maxOf(b, d.plusDays(7))
    }.distinctUntilChanged()

    private val week = selected.map { HabitEngine.weekStart(it) }.distinctUntilChanged()
    private val goal = week.flatMapLatest { w -> c.calendar.weekGoal(w).map { w to it } }
    private val habits = c.habits.today(LocalDate.now()).map { l -> l.count { it.done } to l.size }

    private val base = combine(
        view, selected, filter,
        range.flatMapLatest { (a, b) -> c.calendar.days(a, b) },
        combine(c.calendar.labels, c.calendar.inboxAndOverdue(LocalDate.now())) { l, io -> l to io },
    ) { v, sel, f, days, (labels, io) ->
        CalendarUi(v, YearMonth.from(sel), sel, days, labels, f, io.first, io.second)
    }

    val state: StateFlow<CalendarUi> = combine(base, goal, habits) { s, (w, g), h -> s.copy(goal = g, goalWeek = w, habitsToday = h) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUi(view = startView, selected = startDay, month = YearMonth.from(startDay)))

    init {
        viewModelScope.launch { c.calendar.ensureLabels() }
    }

    /** Switches the view and remembers it for next time. */
    fun setView(v: CalView) {
        view.value = v
        viewModelScope.launch { c.settings.setCalendarView(v.name) }
    }

    fun select(d: LocalDate) {
        selected.value = d
    }

    fun shiftMonth(n: Long) {
        val d = selected.value
        val m = YearMonth.from(d).plusMonths(n)
        selected.value = if (m == YearMonth.now()) LocalDate.now() else m.atDay(1)
    }

    fun shiftDays(n: Long) {
        selected.value = selected.value.plusDays(n)
    }

    fun shiftWeek(n: Long) {
        selected.value = dayPickedInWeek(selected.value.plusWeeks(n), LocalDate.now())
    }

    fun saveGoal(weekStart: LocalDate, text: String) = viewModelScope.launch { c.calendar.setWeekGoal(weekStart, text) }

    fun toggleFilter(id: Long) {
        filter.value = if (id in filter.value) filter.value - id else filter.value + id
    }

    fun clearFilter() {
        filter.value = emptySet()
    }

    fun toggleTodo(t: TodoItem) = viewModelScope.launch { reward(c.calendar.toggleTodo(t.todo.id), t.todo.title) }

    fun toggleStep(s: TodoStepEntity, title: String) = viewModelScope.launch { reward(c.calendar.toggleStep(s), title) }

    private suspend fun reward(coins: Int, title: String) {
        if (coins > 0) {
            c.sfx.play(Sfx.Sound.Coin)
            c.events.post(CatEvent.Coins(coins, "Xong \"$title\" rồi! Trẫm vờn len ăn mừng. +$coins xu"))
        }
        CalendarReminder.schedule(c.context)
    }

    fun skip(e: EventItem) = viewModelScope.launch {
        c.calendar.skipOccurrence(e.event.id, e.occ.start)
        CalendarReminder.schedule(c.context)
    }
}

class CalendarActions(
    val onBack: () -> Unit,
    val onNewEvent: (LocalDate, Int?) -> Unit,
    val onNewTodo: (LocalDate?) -> Unit,
    val onEvent: (Long) -> Unit,
    val onTodo: (Long) -> Unit,
    val onLabels: () -> Unit,
    val onSpend: (EventItem) -> Unit,
    val onMoney: () -> Unit,
    val onHabits: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel, actions: CalendarActions) {
    val s by vm.state.collectAsState()
    val type = LocalPixelType.current
    val skies = LocalContext.current.container.skies
    val night by rememberNight()
    var adding by remember { mutableStateOf(false) }
    var pickingLabels by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<String?>(null) } // "inbox" / "overdue"
    var eventMenu by remember { mutableStateOf<EventItem?>(null) }
    // a sky behind the day and week views (day: clouds, 18h–7h: night clouds)
    val sky = s.view != CalView.MONTH
    if (sky) StatusBarIcons(darkBehind = night)

    PixelScreen(
        "Lịch",
        onBack = null,
        titleColor = if (sky && night) Pal.Card else Pal.Ink,
        backdrop = if (!sky) null else {
            {
                Crossfade(night, animationSpec = tween(1_500), label = "calendar sky") { dark ->
                    ParallaxSky(skies.sky(if (dark) SkyLibrary.CAL_NIGHT else SkyLibrary.CAL_DAY), Modifier.fillMaxSize())
                }
            }
        },
        actions = { PixelTextButton("+", onClick = { adding = true }) },
    ) {
        PixelTabs(CalView.entries, s.view, { it.label }) { vm.setView(it) }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelChip(if (s.filter.isEmpty()) "Nhãn" else "Nhãn ${s.filter.size}", selected = s.filter.isNotEmpty()) { pickingLabels = true }
            if (s.inbox.isNotEmpty()) PixelChip("Chưa xếp (${s.inbox.size})", selected = list == "inbox") { list = if (list == "inbox") null else "inbox" }
            if (s.overdue.isNotEmpty()) PixelChip("Quá hạn (${s.overdue.size})", selected = list == "overdue") { list = if (list == "overdue") null else "overdue" }
        }
        val detail: @Composable () -> Unit = {
            list?.let { which ->
                TodoBox(
                    if (which == "inbox") "CHƯA XẾP LỊCH" else "QUÁ HẠN",
                    if (which == "inbox") s.inbox else s.overdue, vm, actions, showDay = which == "overdue",
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (s.view) {
                CalView.DAY -> DayView(s, vm, actions, detail) { eventMenu = it }
                CalView.WEEK -> WeekView(s, vm, actions, night, detail) { eventMenu = it }
                CalView.MONTH -> MonthView(s, vm, actions, detail) { eventMenu = it }
            }
        }
    }

    if (pickingLabels) {
        PixelDialog(onDismiss = { pickingLabels = false }) {
            PixelText("Nhãn", style = type.title)
            if (s.filter.isNotEmpty()) PixelChip("Tất cả") { vm.clearFilter() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (l in s.labels) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        LabelDot(l.color)
                        PixelChip(l.name, selected = l.id in s.filter) { vm.toggleFilter(l.id) }
                    }
                }
            }
            PixelTextButton("Sửa nhãn", onClick = { pickingLabels = false; actions.onLabels() }, look = ButtonLook.Plain, modifier = Modifier.fillMaxWidth())
        }
    }
    if (adding) {
        PixelDialog(onDismiss = { adding = false }) {
            PixelText("Thêm vào ${shortDate(s.selected)}", style = type.title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelTextButton("Sự kiện", onClick = { adding = false; actions.onNewEvent(s.selected, null) })
                PixelTextButton("Việc", onClick = { adding = false; actions.onNewTodo(s.selected) })
                PixelTextButton("Việc chưa xếp", onClick = { adding = false; actions.onNewTodo(null) }, look = ButtonLook.Plain)
            }
        }
    }
    eventMenu?.let { e ->
        PixelDialog(onDismiss = { eventMenu = null }) {
            PixelText(e.event.title, style = type.title)
            PixelText(occLabel(e, e.occ.start), style = type.caption, color = Pal.InkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelTextButton("Sửa", onClick = { eventMenu = null; actions.onEvent(e.event.id) })
                if (e.event.rep != EventRepeat.NONE) PixelTextButton("Xoá lần này", onClick = { eventMenu = null; vm.skip(e) }, look = ButtonLook.Plain)
                if (e.event.cost > 0) PixelTextButton("Ghi chi", onClick = { eventMenu = null; actions.onSpend(e) }, look = ButtonLook.Plain)
            }
        }
    }
}

// ------------------------------------------------------------------ month

@Composable
private fun MonthView(s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, extra: @Composable () -> Unit, onEventMenu: (EventItem) -> Unit) {
    val type = LocalPixelType.current
    val today = LocalDate.now()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { extra() }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PixelTextButton("<", onClick = { vm.shiftMonth(-1) }, look = ButtonLook.Plain)
                PixelText("Tháng ${s.month.monthValue}/${s.month.year}", style = type.title, color = Pal.Ink, align = TextAlign.Center, modifier = Modifier.weight(1f))
                PixelTextButton(">", onClick = { vm.shiftMonth(1) }, look = ButtonLook.Plain)
            }
        }
        item { MonthGrid(s, vm) }
        item {
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val d = s.day(s.selected)
                    PixelText(shortDate(d.day) + if (d.day == today) " · hôm nay" else "", style = type.title)
                    DayAgenda(d, s, vm, actions, onEventMenu)
                    val money = if (d.spent > 0 || d.earned > 0) {
                        "Chi ${formatVndShort(d.spent)}" + (if (d.earned > 0) " · Thu ${formatVndShort(d.earned)}" else "")
                    } else {
                        null
                    }
                    money?.let { PixelText(it, style = type.caption, color = Pal.InkSoft, modifier = Modifier.clickable { actions.onMoney() }) }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun MonthGrid(s: CalendarUi, vm: CalendarViewModel) {
    val type = LocalPixelType.current
    val (start, _) = gridRange(s.month)
    val today = LocalDate.now()
    var drag by remember { mutableStateOf(0f) }
    Column(
        Modifier.pointerInput(s.month) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (drag > 120) vm.shiftMonth(-1) else if (drag < -120) vm.shiftMonth(1)
                    drag = 0f
                },
                onHorizontalDrag = { _, dx -> drag += dx },
            )
        },
    ) {
        Row {
            for (d in WEEKDAYS_SHORT) PixelText(d, style = type.caption, color = Pal.InkSoft, align = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        for (week in 0 until 6) {
            val monday = start.plusDays(week * 7L)
            val bands = (0 until 7).flatMap { s.day(monday.plusDays(it.toLong())).events }.filter { it.occ.multiDay }.distinctBy { it.event.id to it.occ.start }
            val slots = CalendarEngine.weekSlots(bands.map { it.occ }, monday)
            Row {
                for (col in 0 until 7) {
                    val day = monday.plusDays(col.toLong())
                    DayCell(
                        s.day(day), inMonth = day.monthValue == s.month.monthValue, today = day == today, selected = day == s.selected,
                        bands = bands.filter { it.occ.covers(day) }.map { it to (slots[it.occ] ?: 0) },
                        modifier = Modifier.weight(1f).clickable { vm.select(day) },
                    )
                }
            }
        }
    }
}

/** A day of the month grid: rounded, its number, multi-day bands, up to three dots, money spent. */
@Composable
private fun DayCell(d: CalDay, inMonth: Boolean, today: Boolean, selected: Boolean, bands: List<Pair<EventItem, Int>>, modifier: Modifier) {
    val type = LocalPixelType.current
    val dots = (d.events.filter { !it.occ.multiDay }.map { it.color } + d.todos.filter { !it.done }.map { it.color }).take(3)
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .aspectRatio(0.82f)
            .padding(2.dp)
            .clip(shape)
            .background(if (today) Pal.GoldTint else if (inMonth) Pal.Card else Pal.Well)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Pal.Gold else Pal.Line, shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = 2.dp.toPx()
            // multi-day bands under the number
            for ((e, slot) in bands) {
                if (slot > 1) continue
                val top = size.height * 0.36f + slot * 3 * u
                drawRect(Color(e.color), Offset(0f, top), Size(size.width, 2 * u))
            }
            dots.forEachIndexed { i, c ->
                drawCircle(Color(c), radius = 1.5f * u, center = Offset(3 * u + i * 4 * u, size.height * 0.62f))
            }
            // every habit done that day
            if (d.habitsAllDone) drawRoundRect(Pal.Green, Offset(2 * u, size.height - 3 * u), Size(size.width - 4 * u, 1.5f * u), androidx.compose.ui.geometry.CornerRadius(u))
        }
        PixelText(
            "${d.day.dayOfMonth}", style = if (today || selected) type.bodyBold else type.caption,
            color = if (inMonth) Pal.Ink else Pal.InkFaint, modifier = Modifier.padding(start = 5.dp, top = 2.dp),
        )
        if (d.spent > 0) {
            PixelText(
                formatVndShort(d.spent), style = type.caption.copy(fontSize = type.caption.fontSize * 0.85f), color = Pal.InkSoft,
                maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ to-do lists

fun occLabel(e: EventItem, day: LocalDate): String {
    val o = e.occ
    return when {
        o.multiDay -> "Ngày ${ChronoUnit.DAYS.between(o.start, day) + 1}/${ChronoUnit.DAYS.between(o.start, o.end) + 1}" +
            if (!o.allDay && o.startMinute != null && day == o.start) " · từ ${hhmm(o.startMinute)}" else ""
        o.allDay || o.startMinute == null -> "Cả ngày"
        else -> hhmm(o.startMinute) + (o.endMinute?.let { "–" + hhmm(it) } ?: "")
    }
}

@Composable
fun TodoRow(t: TodoItem, vm: CalendarViewModel, actions: CalendarActions, showDay: Boolean) {
    val type = LocalPixelType.current
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelCheckbox(t.done, onCheckedChange = { vm.toggleTodo(t) })
            Column(Modifier.weight(1f).clickable { actions.onTodo(t.todo.id) }) {
                PixelText((if (t.todo.starred) "★ " else "") + t.todo.title, style = type.bodyBold, color = if (t.done) Pal.InkSoft else Pal.Ink, maxLines = 1)
                val late = t.day?.let { if (!t.done && it.isBefore(today)) "trễ ${ChronoUnit.DAYS.between(it, today)} ngày" else null }
                val bits = listOfNotNull(
                    if (showDay) t.day?.let { shortDate(it) } else null,
                    t.todo.dueMinute?.let { hhmm(it) },
                    late,
                    if (t.steps.isNotEmpty()) "${t.steps.count { it.done }}/${t.steps.size} bước" else null,
                    t.label?.name,
                )
                if (bits.isNotEmpty()) PixelText(bits.joinToString(" · "), style = type.caption, color = if (late != null) Pal.Red else Pal.InkSoft)
            }
            LabelDot(t.color)
        }
    }
}

@Composable
private fun TodoBox(title: String, items: List<TodoItem>, vm: CalendarViewModel, actions: CalendarActions, showDay: Boolean) {
    PixelPanel(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelText(title, style = LocalPixelType.current.caption, color = Pal.InkSoft)
            for (t in items) TodoRow(t, vm, actions, showDay)
        }
    }
}
