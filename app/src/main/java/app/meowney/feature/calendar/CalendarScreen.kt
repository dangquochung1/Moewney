package app.meowney.feature.calendar

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.calendar.CalendarEngine
import app.meowney.core.calendar.EventRepeat
import app.meowney.core.calendar.Occurrence
import app.meowney.core.calendar.rep
import app.meowney.core.calendar.start
import app.meowney.core.data.CalDay
import app.meowney.core.data.EventItem
import app.meowney.core.data.TodoItem
import app.meowney.core.db.CalLabelEntity
import app.meowney.core.db.TodoStepEntity
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.notify.CalendarReminder
import app.meowney.core.sound.Sfx
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelCheckbox
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.WEEKDAYS_SHORT
import app.meowney.core.ui.hhmm
import app.meowney.core.ui.shortDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class CalView(val label: String) { MONTH("Tháng"), WEEK("Tuần"), DAY("Ngày"), AGENDA("Sắp tới") }

data class CalendarUi(
    val view: CalView = CalView.MONTH,
    val month: YearMonth = YearMonth.now(),
    val selected: LocalDate = LocalDate.now(),
    val days: Map<LocalDate, CalDay> = emptyMap(),
    val labels: List<CalLabelEntity> = emptyList(),
    /** Labels shown; empty = all. */
    val filter: Set<Long> = emptySet(),
    val inbox: List<TodoItem> = emptyList(),
    val overdue: List<TodoItem> = emptyList(),
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

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(private val c: AppContainer, startDay: LocalDate) : ViewModel() {
    private val view = MutableStateFlow(CalView.MONTH)
    private val selected = MutableStateFlow(startDay)
    private val filter = MutableStateFlow<Set<Long>>(emptySet())

    /** The range loaded: the month grid, a week either side for the week/day views, 60 days for the agenda. */
    private val range = combine(view, selected) { v, d ->
        when (v) {
            CalView.AGENDA -> d to d.plusDays(60)
            else -> {
                val (a, b) = gridRange(YearMonth.from(d))
                minOf(a, d.minusDays(7)) to maxOf(b, d.plusDays(7))
            }
        }
    }

    val state: StateFlow<CalendarUi> = combine(
        view, selected, filter,
        range.flatMapLatest { (a, b) -> c.calendar.days(a, b) },
        combine(c.calendar.labels, c.calendar.inboxAndOverdue(LocalDate.now())) { l, io -> l to io },
    ) { v, sel, f, days, (labels, io) ->
        CalendarUi(v, YearMonth.from(sel), sel, days, labels, f, io.first, io.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUi(selected = startDay, month = YearMonth.from(startDay)))

    init {
        viewModelScope.launch { c.calendar.ensureLabels() }
    }

    fun setView(v: CalView) {
        view.value = v
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
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel, actions: CalendarActions) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var adding by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<String?>(null) } // "inbox" / "overdue"
    var eventMenu by remember { mutableStateOf<EventItem?>(null) }

    PixelScreen("Lịch", onBack = actions.onBack, actions = {
        PixelTextButton("Nhãn", onClick = actions.onLabels, look = ButtonLook.Light)
        PixelTextButton("+", onClick = { adding = true })
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
            for (v in CalView.entries) PixelChip(v.label, selected = s.view == v) { vm.setView(v) }
        }
        Spacer(Modifier.height(m.unit))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            if (s.filter.isNotEmpty()) PixelChip("Tất cả") { vm.clearFilter() }
            for (l in s.labels) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelDot(l.color)
                    PixelChip(l.name, selected = l.id in s.filter) { vm.toggleFilter(l.id) }
                }
            }
            if (s.inbox.isNotEmpty()) PixelChip("Chưa xếp lịch (${s.inbox.size})", selected = list == "inbox") { list = if (list == "inbox") null else "inbox" }
            if (s.overdue.isNotEmpty()) PixelChip("Quá hạn (${s.overdue.size})", selected = list == "overdue") { list = if (list == "overdue") null else "overdue" }
        }
        Spacer(Modifier.height(m.unit * 2))
        val detail: @Composable () -> Unit = {
            list?.let { which ->
                TodoBox(
                    if (which == "inbox") "CHƯA XẾP LỊCH" else "QUÁ HẠN",
                    if (which == "inbox") s.inbox else s.overdue, vm, actions, showDay = which == "overdue",
                )
            }
        }
        when (s.view) {
            CalView.MONTH -> MonthView(s, vm, actions, detail) { eventMenu = it }
            CalView.WEEK -> WeekView(s, vm, actions, detail) { eventMenu = it }
            CalView.DAY -> DayView(s, vm, actions, detail) { eventMenu = it }
            CalView.AGENDA -> AgendaView(s, vm, actions, detail) { eventMenu = it }
        }
    }

    if (adding) {
        PixelDialog(onDismiss = { adding = false }) {
            PixelText("Thêm vào ${shortDate(s.selected)}", style = type.title)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Sự kiện", onClick = { adding = false; actions.onNewEvent(s.selected, null) })
                PixelTextButton("Việc", onClick = { adding = false; actions.onNewTodo(s.selected) })
                PixelTextButton("Việc chưa xếp", onClick = { adding = false; actions.onNewTodo(null) }, look = ButtonLook.Light)
            }
        }
    }
    eventMenu?.let { e ->
        PixelDialog(onDismiss = { eventMenu = null }) {
            PixelText(e.event.title, style = type.title)
            PixelText(occLabel(e, e.occ.start), style = type.caption, color = Pal.InkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Sửa", onClick = { eventMenu = null; actions.onEvent(e.event.id) })
                if (e.event.rep != EventRepeat.NONE) PixelTextButton("Xoá lần này", onClick = { eventMenu = null; vm.skip(e) }, look = ButtonLook.Light)
                if (e.event.cost > 0) PixelTextButton("Ghi chi", onClick = { eventMenu = null; actions.onSpend(e) }, look = ButtonLook.Light)
            }
        }
    }
}

// ------------------------------------------------------------------ month

@Composable
private fun MonthView(s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, extra: @Composable () -> Unit, onEventMenu: (EventItem) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        item { extra() }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PixelTextButton("<", onClick = { vm.shiftMonth(-1) }, look = ButtonLook.Light)
                PixelText("Tháng ${s.month.monthValue}/${s.month.year}", style = type.title, color = Pal.Cream, align = TextAlign.Center, modifier = Modifier.weight(1f))
                PixelTextButton(">", onClick = { vm.shiftMonth(1) }, look = ButtonLook.Light)
            }
        }
        item { MonthGrid(s, vm) }
        item { DayDetail(s.day(s.selected), vm, actions, onEventMenu) }
    }
}

@Composable
private fun MonthGrid(s: CalendarUi, vm: CalendarViewModel) {
    val m = LocalPixelMetrics.current
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
            for (d in WEEKDAYS_SHORT) PixelText(d, style = type.caption, color = Pal.Paper, align = TextAlign.Center, modifier = Modifier.weight(1f))
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

@Composable
private fun DayCell(d: CalDay, inMonth: Boolean, today: Boolean, selected: Boolean, bands: List<Pair<EventItem, Int>>, modifier: Modifier) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val dots = (d.events.filter { !it.occ.multiDay }.map { it.color } + d.todos.filter { !it.done }.map { it.color }).take(3)
    Box(modifier.aspectRatio(0.82f)) {
        Canvas(Modifier.fillMaxSize()) {
            val u = m.ui.toFloat()
            drawRect(if (selected) Pal.Orange else if (today) Pal.Gold else Pal.WoodDark)
            drawRect(if (inMonth) Pal.Cream else Pal.Paper.copy(alpha = 0.55f), Offset(u, u), Size(size.width - 2 * u, size.height - 2 * u))
            // multi-day bands under the number, edge to edge so they join the next cell
            for ((e, slot) in bands) {
                if (slot > 1) continue
                val top = size.height * 0.34f + slot * 4 * u
                val left = if (e.occ.start == d.day) u * 2 else 0f
                val right = if (e.occ.end == d.day) size.width - u * 2 else size.width
                drawRect(Color(e.color), Offset(left, top), Size(right - left, 3 * u))
            }
            // up to three dots
            dots.forEachIndexed { i, c ->
                drawRect(Color(c), Offset(u * 3 + i * u * 4, size.height * 0.58f), Size(u * 3, u * 3))
            }
            // every habit done that day
            if (d.habitsAllDone) drawRect(Pal.Green, Offset(u, size.height - 3 * u), Size(size.width - 2 * u, 2 * u))
        }
        PixelText(
            "${d.day.dayOfMonth}", style = type.caption,
            color = if (inMonth) Pal.Ink else Pal.InkSoft,
            modifier = Modifier.padding(start = m.unit * 2, top = m.unit),
        )
        if (d.spent > 0) {
            PixelText(
                formatVndShort(d.spent), style = type.caption, color = if (d.overLimit) Pal.Red else Pal.InkSoft,
                maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(start = m.unit * 2, bottom = m.unit * 3),
            )
        }
    }
}

// ------------------------------------------------------------------ the day's list

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
fun DayDetail(d: CalDay, vm: CalendarViewModel, actions: CalendarActions, onEventMenu: (EventItem) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(shortDate(d.day) + if (d.day == today) " · hôm nay" else "", style = type.title, modifier = Modifier.weight(1f))
                PixelTextButton("+ Sự kiện", onClick = { actions.onNewEvent(d.day, null) }, look = ButtonLook.Light)
                Spacer(Modifier.width(m.unit))
                PixelTextButton("+ Việc", onClick = { actions.onNewTodo(d.day) }, look = ButtonLook.Light)
            }
            if (d.events.isEmpty() && d.todos.isEmpty()) PixelText("Không có gì. Trẫm cho Sen rảnh.", style = type.caption, color = Pal.InkSoft)
            for (e in d.events) EventRow(e, d.day, today, actions, onEventMenu)
            for (t in d.todos) TodoRow(t, vm, actions, showDay = false)
            val bits = listOfNotNull(
                if (d.spent > 0 || d.earned > 0) "Chi ${formatVndShort(d.spent)}" + (if (d.earned > 0) " · Thu ${formatVndShort(d.earned)}" else "") + (if (d.overLimit) " (vượt hạn mức)" else "") else null,
                if (d.habitsDone > 0) "Thói quen: xong ${d.habitsDone}" + if (d.habitsAllDone) " (hết)" else "" else null,
            )
            if (bits.isNotEmpty()) {
                PixelText(bits.joinToString(" · "), style = type.caption, color = Pal.InkSoft, modifier = Modifier.clickable { actions.onMoney() })
            }
        }
    }
}

@Composable
fun EventRow(e: EventItem, day: LocalDate, today: LocalDate, actions: CalendarActions, onEventMenu: (EventItem) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(
        Modifier.fillMaxWidth().clickable { onEventMenu(e) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
    ) {
        LabelDot(e.color, 4)
        Column(Modifier.weight(1f)) {
            PixelText(e.event.title, style = type.bodyBold, maxLines = 1)
            val bits = listOfNotNull(
                occLabel(e, day),
                e.label?.name,
                e.event.location.ifBlank { null },
                if (e.birthday) {
                    val n = CalendarEngine.countdown(e.event.start, today)
                    if (n == 0L) "hôm nay!" else "còn $n ngày"
                } else {
                    null
                },
                if (e.event.cost > 0) "dự chi ${formatVndShort(e.event.cost)}" else null,
            )
            PixelText(bits.joinToString(" · "), style = type.caption, color = Pal.InkSoft, maxLines = 2)
        }
        if (e.event.cost > 0 && !day.isAfter(today)) {
            PixelTextButton("Ghi chi", onClick = { actions.onSpend(e) })
        }
    }
}

@Composable
fun TodoRow(t: TodoItem, vm: CalendarViewModel, actions: CalendarActions, showDay: Boolean) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
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
        if (!t.done) {
            for (step in t.steps) {
                Row(Modifier.padding(start = m.unit * 8), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelCheckbox(step.done, onCheckedChange = { vm.toggleStep(step, t.todo.title) })
                    PixelText(step.text, style = type.caption, color = if (step.done) Pal.InkSoft else Pal.Ink)
                }
            }
        }
    }
}

@Composable
private fun TodoBox(title: String, items: List<TodoItem>, vm: CalendarViewModel, actions: CalendarActions, showDay: Boolean) {
    val m = LocalPixelMetrics.current
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText(title, style = LocalPixelType.current.caption, color = Pal.InkSoft)
            for (t in items) TodoRow(t, vm, actions, showDay)
        }
    }
}
