package app.meowney.feature.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.meowney.core.data.CalDay
import app.meowney.core.data.EventItem
import app.meowney.core.data.TodoItem
import app.meowney.core.format.formatVndShort
import app.meowney.core.habit.HabitEngine
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.CardTone
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.TickBox
import app.meowney.core.ui.WEEKDAYS_SHORT
import app.meowney.core.ui.hhmm
import app.meowney.core.ui.shortDate
import java.time.LocalDate

// ------------------------------------------------------------------ a day as a list

/** One line of a day: an event or a to-do, with the time it starts (null = all day / no time). */
sealed interface AgendaLine {
    val minute: Int?

    data class Event(val item: EventItem, override val minute: Int?) : AgendaLine
    data class Todo(val item: TodoItem, override val minute: Int?) : AgendaLine
}

/**
 * A day in the order it is read: all-day and multi-day events first, then everything with a
 * time (events and to-dos mixed) from the earliest, then to-dos without a time.
 */
fun agendaOf(d: CalDay): List<AgendaLine> {
    val allDay = d.events.filter { it.occ.allDay || it.occ.multiDay || it.occ.startMinute == null }
    val timed = d.events.filter { it !in allDay }.map { AgendaLine.Event(it, it.occ.startMinute) } +
        d.todos.filter { it.todo.dueMinute != null }.map { AgendaLine.Todo(it, it.todo.dueMinute) }
    return allDay.map { AgendaLine.Event(it, null) } +
        timed.sortedBy { it.minute } +
        d.todos.filter { it.todo.dueMinute == null }.map { AgendaLine.Todo(it, null) }
}

/** What the habits line says for [day]: x/y today, how many were done on a past day, nothing ahead. */
fun habitsLine(day: LocalDate, today: LocalDate, d: CalDay, todayHabits: Pair<Int, Int>?): String? = when {
    day == today -> todayHabits?.takeIf { it.second > 0 }?.let { (done, total) -> "Thói quen $done/$total" + if (done == total) " ✓" else "" }
    day.isBefore(today) && d.habitsDone > 0 -> "Thói quen: xong ${d.habitsDone}" + if (d.habitsAllDone) " ✓" else ""
    else -> null
}

/**
 * The day's events and to-dos, each line led by its time; to-dos tick right here. Under them
 * the habits of the day and the buttons to add something.
 */
@Composable
fun DayAgenda(d: CalDay, s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, onEventMenu: (EventItem) -> Unit) {
    val type = LocalPixelType.current
    val today = LocalDate.now()
    val lines = agendaOf(d)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (lines.isEmpty()) PixelText("Không có gì. Trẫm cho Sen rảnh.", style = type.body, color = Pal.InkSoft)
        for (line in lines) {
            when (line) {
                is AgendaLine.Event -> {
                    val e = line.item
                    val time = if (line.minute == null) occLabel(e, d.day) else hhmm(line.minute)
                    AgendaRow(time) {
                        Column(Modifier.weight(1f).clickable { onEventMenu(e) }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                LabelDot(e.color)
                                PixelText(e.event.title, style = type.bodyBold, maxLines = 1)
                            }
                            val bits = listOfNotNull(
                                e.occ.endMinute?.takeIf { line.minute != null }?.let { "đến ${hhmm(it)}" },
                                e.label?.name,
                                e.event.location.ifBlank { null },
                                if (e.event.cost > 0) "dự chi ${formatVndShort(e.event.cost)}" else null,
                            )
                            if (bits.isNotEmpty()) PixelText(bits.joinToString(" · "), style = type.caption, color = Pal.InkSoft, maxLines = 2)
                        }
                        if (e.event.cost > 0 && !d.day.isAfter(today)) PixelTextButton("Ghi chi", onClick = { actions.onSpend(e) }, look = ButtonLook.Light)
                    }
                }
                is AgendaLine.Todo -> {
                    val t = line.item
                    AgendaRow(line.minute?.let(::hhmm) ?: "việc") {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TickBox(t.done, 20.dp, Modifier.clickable { vm.toggleTodo(t) })
                                PixelText(
                                    (if (t.todo.starred) "★ " else "") + t.todo.title + if (t.steps.isNotEmpty()) " · ${t.steps.count { it.done }}/${t.steps.size}" else "",
                                    style = type.bodyBold, color = if (t.done) Pal.InkSoft else Pal.Ink, maxLines = 2,
                                    modifier = Modifier.weight(1f).clickable { actions.onTodo(t.todo.id) },
                                )
                                LabelDot(t.color)
                            }
                            if (!t.done) {
                                for (step in t.steps) {
                                    Row(Modifier.padding(start = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TickBox(step.done, 16.dp, Modifier.clickable { vm.toggleStep(step, t.todo.title) })
                                        PixelText(step.text, style = type.caption, color = if (step.done) Pal.InkSoft else Pal.Ink)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        habitsLine(d.day, today, d, s.habitsToday)?.let { text ->
            PixelText("$text ›", style = type.bodyBold, color = Pal.Green, modifier = Modifier.clickable { actions.onHabits() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelTextButton("+ Sự kiện", onClick = { actions.onNewEvent(d.day, null) }, look = ButtonLook.Light)
            PixelTextButton("+ Việc", onClick = { actions.onNewTodo(d.day) }, look = ButtonLook.Light)
        }
    }
}

/** A line led by its time in a narrow column. */
@Composable
private fun AgendaRow(time: String, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PixelText(time, style = LocalPixelType.current.bodyBold, color = Pal.AccentText, modifier = Modifier.width(62.dp).padding(top = 1.dp), maxLines = 2)
        content()
    }
}

/** "‹ title ›" for stepping days or weeks. */
@Composable
private fun Stepper(title: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PixelTextButton("‹", onClick = onPrev, look = ButtonLook.Light)
        PixelText(title, style = LocalPixelType.current.title, color = Pal.Ink, align = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
        PixelTextButton("›", onClick = onNext, look = ButtonLook.Light)
    }
}

// ------------------------------------------------------------------ day

@Composable
fun DayView(s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, extra: @Composable () -> Unit, onEventMenu: (EventItem) -> Unit) {
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        extra()
        PixelPanel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Stepper(shortDate(s.selected) + if (s.selected == today) " · hôm nay" else "", onPrev = { vm.shiftDays(-1) }, onNext = { vm.shiftDays(1) })
                DayAgenda(s.day(s.selected), s, vm, actions, onEventMenu)
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

// ------------------------------------------------------------------ week

/**
 * "TUẦN NÀY." like a paper planner: the week's dates, a goal Sen types, then the seven days
 * from Monday; the chosen one opens up into its list, the others show their colours and how
 * many to-dos are done.
 */
@Composable
fun WeekView(s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, night: Boolean, extra: @Composable () -> Unit, onEventMenu: (EventItem) -> Unit) {
    val type = LocalPixelType.current
    val today = LocalDate.now()
    val monday = HabitEngine.weekStart(s.selected)
    val days = (0 until 7).map { monday.plusDays(it.toLong()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        extra()
        PixelText(
            if (today in days) "TUẦN NÀY." else "TUẦN ${dm(monday)}.",
            style = type.pixelTitle, color = if (night) Pal.Card else Pal.Ink, modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
        PixelPanel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stepper("Tuần ${dm(monday)} – ${dm(days.last())}", onPrev = { vm.shiftWeek(-1) }, onNext = { vm.shiftWeek(1) })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PixelText("Mục tiêu", style = type.bodyBold, color = Pal.Ink)
                    // what Sen types stays on screen; the database follows it
                    var goal by remember(monday) { mutableStateOf<String?>(null) }
                    LaunchedEffect(monday, s.goal) { if (goal == null && s.goalWeek == monday) goal = s.goal }
                    PixelTextField(
                        goal ?: if (s.goalWeek == monday) s.goal else "",
                        {
                            goal = it.take(120)
                            vm.saveGoal(monday, it)
                        },
                        Modifier.weight(1f),
                        placeholder = "vd: ngủ trước 23h",
                    )
                }
            }
        }
        for (d in days) {
            WeekDay(s.day(d), open = d == s.selected, today = d == today, s, vm, actions, onEventMenu)
        }
        Spacer(Modifier.size(12.dp))
    }
}

private fun dm(d: LocalDate) = "%02d/%02d".format(d.dayOfMonth, d.monthValue)

@Composable
private fun WeekDay(d: CalDay, open: Boolean, today: Boolean, s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, onEventMenu: (EventItem) -> Unit) {
    val type = LocalPixelType.current
    val todos = d.todos
    PixelPanel(
        Modifier.fillMaxWidth().let { if (open) it else it.clickable { vm.select(d.day) } },
        tone = if (open) CardTone.Plain else if (today) CardTone.Lemon else CardTone.Plain,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelText("${WEEKDAYS_SHORT[d.day.dayOfWeek.value - 1]} ${d.day.dayOfMonth}", style = type.title, color = Pal.Ink)
                if (today) PixelText("hôm nay", style = type.caption, color = Pal.AccentText)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in (d.events.map { it.color } + todos.map { it.color }).take(6)) LabelDot(c)
                }
                if (todos.isNotEmpty()) PixelText("${todos.count { it.done }}/${todos.size}", style = type.bodyBold, color = Pal.InkSoft)
                else if (d.events.isNotEmpty()) PixelText("${d.events.size} lịch", style = type.caption, color = Pal.InkSoft)
            }
            if (open) DayAgenda(d, s, vm, actions, onEventMenu)
        }
    }
}
