package app.meowney.feature.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.meowney.core.data.EventItem
import app.meowney.core.habit.HabitEngine
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.WEEKDAYS_SHORT
import app.meowney.core.ui.hhmm
import app.meowney.core.ui.shortDate
import java.time.LocalDate
import java.time.LocalTime

// ------------------------------------------------------------------ week & day

@Composable
fun WeekView(s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, extra: @Composable () -> Unit, onEventMenu: (EventItem) -> Unit) {
    val monday = HabitEngine.weekStart(s.selected)
    val days = (0 until 7).map { monday.plusDays(it.toLong()) }
    Column {
        extra()
        Nav("${shortDate(days.first())} – ${shortDate(days.last())}", onPrev = { vm.shiftDays(-7) }, onNext = { vm.shiftDays(7) })
        HourGrid(days, s, selected = s.selected, onDay = vm::select, onSlot = actions.onNewEvent, onEvent = onEventMenu)
    }
}

@Composable
fun DayView(s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, extra: @Composable () -> Unit, onEventMenu: (EventItem) -> Unit) {
    val m = LocalPixelMetrics.current
    val d = s.day(s.selected)
    Column {
        extra()
        Nav(shortDate(s.selected), onPrev = { vm.shiftDays(-1) }, onNext = { vm.shiftDays(1) })
        if (d.todos.isNotEmpty()) {
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    for (t in d.todos) TodoRow(t, vm, actions, showDay = false)
                }
            }
        }
        HourGrid(listOf(s.selected), s, selected = s.selected, onDay = {}, onSlot = actions.onNewEvent, onEvent = onEventMenu)
    }
}

@Composable
private fun Nav(title: String, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = LocalPixelMetrics.current.unit), verticalAlignment = Alignment.CenterVertically) {
        PixelTextButton("<", onClick = onPrev, look = ButtonLook.Light)
        PixelText(title, style = LocalPixelType.current.bodyBold, color = Pal.Cream, align = TextAlign.Center, modifier = Modifier.weight(1f))
        PixelTextButton(">", onClick = onNext, look = ButtonLook.Light)
    }
}

/** Timed events placed by hour; all-day and multi-day ones in a row on top. Tap an empty hour to add. */
@Composable
private fun HourGrid(
    days: List<LocalDate>,
    s: CalendarUi,
    selected: LocalDate,
    onDay: (LocalDate) -> Unit,
    onSlot: (LocalDate, Int?) -> Unit,
    onEvent: (EventItem) -> Unit,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    val hourH: Dp = m.unit * 18
    val gutter: Dp = m.unit * 12
    val density = LocalDensity.current
    val scroll = rememberScrollState(initial = with(density) { (hourH * (LocalTime.now().hour - 1).coerceAtLeast(0)).roundToPx() })
    Column {
        // header + all-day row
        Row {
            Box(Modifier.width(gutter))
            for (d in days) {
                val cd = s.day(d)
                Column(Modifier.weight(1f).clickable { onDay(d) }.padding(horizontal = m.unit / 2), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (days.size > 1) {
                        PixelText(WEEKDAYS_SHORT[d.dayOfWeek.value - 1], style = type.caption, color = if (d == today) Pal.Gold else Pal.Paper)
                        PixelText("${d.dayOfMonth}", style = type.bodyBold, color = if (d == selected) Pal.Orange else if (d == today) Pal.Gold else Pal.Cream)
                    }
                    for (e in cd.events.filter { it.occ.allDay || it.occ.multiDay || it.occ.startMinute == null }) {
                        Box(
                            Modifier.fillMaxWidth().padding(top = m.unit / 2).clickable { onEvent(e) },
                        ) {
                            Canvas(Modifier.matchParentSize()) { drawRect(Color(e.color)) }
                            PixelText(e.event.title, style = type.caption, color = Pal.Ink, maxLines = 1, modifier = Modifier.padding(horizontal = m.unit))
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll)) {
            val colW = (maxWidth - gutter) / days.size
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(hourH * 24)
                    .pointerInput(days) {
                        detectTapGestures { p ->
                            val x = p.x - gutter.toPx()
                            if (x < 0) return@detectTapGestures
                            val col = (x / colW.toPx()).toInt().coerceIn(0, days.lastIndex)
                            val hour = (p.y / hourH.toPx()).toInt().coerceIn(0, 23)
                            onSlot(days[col], hour * 60)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val u = m.ui.toFloat()
                    val h = hourH.toPx()
                    val g = gutter.toPx()
                    for (i in 0..24) drawRect(Pal.WoodDark, Offset(g, i * h), Size(size.width - g, u))
                    for (c in 0..days.size) drawRect(Pal.WoodDark, Offset(g + c * colW.toPx(), 0f), Size(u, size.height))
                    val ti = days.indexOf(today)
                    if (ti >= 0) {
                        val now = LocalTime.now()
                        drawRect(Pal.Red, Offset(g + ti * colW.toPx(), (now.hour + now.minute / 60f) * h), Size(colW.toPx(), u * 2))
                    }
                }
                for (hr in 0..23) {
                    PixelText("%02d".format(hr), style = type.caption, color = Pal.Paper, modifier = Modifier.offset(y = hourH * hr).padding(start = m.unit))
                }
                days.forEachIndexed { col, d ->
                    val timed = s.day(d).events.filter { !it.occ.allDay && !it.occ.multiDay && it.occ.startMinute != null }
                    val lanes = lanes(timed)
                    val laneCount = (lanes.values.maxOrNull() ?: 0) + 1
                    for (e in timed) {
                        val start = e.occ.startMinute!!
                        val end = (e.occ.endMinute ?: (start + 60)).coerceAtLeast(start + 20)
                        val lane = lanes[e] ?: 0
                        val w = colW / laneCount
                        Box(
                            Modifier
                                .offset(x = gutter + colW * col + w * lane, y = hourH * (start / 60f))
                                .size(w, hourH * ((end - start) / 60f))
                                .padding(m.unit / 2)
                                .clickable { onEvent(e) },
                        ) {
                            Canvas(Modifier.matchParentSize()) {
                                drawRect(Pal.WoodDark)
                                val u = m.ui.toFloat()
                                drawRect(Color(e.color), Offset(u, u), Size(size.width - 2 * u, size.height - 2 * u))
                            }
                            Column(Modifier.padding(m.unit)) {
                                PixelText(e.event.title, style = type.caption, color = Pal.Ink, maxLines = 2)
                                if (days.size == 1) PixelText(hhmm(start) + "–" + hhmm(end), style = type.caption, color = Pal.Ink)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Side-by-side lanes for overlapping timed events in one day. */
private fun lanes(events: List<EventItem>): Map<EventItem, Int> {
    val out = mutableMapOf<EventItem, Int>()
    val ends = mutableListOf<Int>()
    for (e in events.sortedBy { it.occ.startMinute }) {
        val start = e.occ.startMinute ?: 0
        val end = e.occ.endMinute ?: (start + 60)
        val free = ends.indexOfFirst { it <= start }
        val lane = if (free >= 0) free else ends.size.also { ends.add(0) }
        ends[lane] = end
        out[e] = lane
    }
    return out
}

// ------------------------------------------------------------------ agenda

@Composable
fun AgendaView(s: CalendarUi, vm: CalendarViewModel, actions: CalendarActions, extra: @Composable () -> Unit, onEventMenu: (EventItem) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    val days = generateSequence(s.selected) { it.plusDays(1) }.take(61).map { s.day(it) }.filter { it.events.isNotEmpty() || it.todos.isNotEmpty() }.toList()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        item { extra() }
        if (days.isEmpty()) item { PixelText("60 ngày tới chưa có gì.", style = type.body, color = Pal.Paper) }
        for (d in days) {
            item(key = d.day.toEpochDay()) {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    PixelText(
                        shortDate(d.day).uppercase() + if (d.day == today) " · HÔM NAY" else "",
                        style = type.bodyBold, color = Pal.Gold, modifier = Modifier.clickable { vm.select(d.day); vm.setView(CalView.MONTH) },
                    )
                    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                        Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                            for (e in d.events) EventRow(e, d.day, today, actions, onEventMenu)
                            for (t in d.todos) TodoRow(t, vm, actions, showDay = false)
                        }
                    }
                }
            }
        }
    }
}
