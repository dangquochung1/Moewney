package app.meowney.feature.calendar

import app.meowney.core.calendar.Occurrence
import app.meowney.core.data.CalDay
import app.meowney.core.data.EventItem
import app.meowney.core.data.TodoItem
import app.meowney.core.db.EventEntity
import app.meowney.core.db.TodoEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class CalendarViewsTest {
    private val day = LocalDate.of(2026, 11, 12) // a Thursday

    private fun event(id: Long, title: String, start: Int?, allDay: Boolean = false, from: LocalDate = day, to: LocalDate = day): EventItem {
        val e = EventEntity(id = id, title = title, startDay = from.toEpochDay(), endDay = to.toEpochDay(), startMinute = start, allDay = allDay, createdAt = 0)
        return EventItem(e, Occurrence(id, from, to, start, null, allDay), null)
    }

    private fun todo(id: Long, title: String, minute: Int?) =
        TodoItem(TodoEntity(id = id, title = title, dueDay = day.toEpochDay(), dueMinute = minute, createdAt = 0), emptyList(), null)

    private fun AgendaLine.title() = when (this) {
        is AgendaLine.Event -> item.event.title
        is AgendaLine.Todo -> item.todo.title
    }

    @Test fun aDayReadsAllDayFirstThenByTimeThenUntimedTodos() {
        val d = CalDay(
            day,
            events = listOf(event(1, "Họp", 8 * 60), event(2, "Sinh nhật Mẹ", null, allDay = true), event(3, "Nha sĩ", 14 * 60 + 30), event(4, "Đi Đà Lạt", 9 * 60, from = day.minusDays(1), to = day.plusDays(1))),
            todos = listOf(todo(5, "Mua quà", null), todo(6, "Nộp báo cáo", 10 * 60), todo(7, "Gọi điện", 7 * 60)),
        )
        val lines = agendaOf(d)
        assertThat(lines.map { it.title() }).containsExactly(
            "Sinh nhật Mẹ", "Đi Đà Lạt", // all day / several days: no time
            "Gọi điện", "Họp", "Nộp báo cáo", "Nha sĩ", // by time, events and to-dos mixed
            "Mua quà", // a to-do without a time
        ).inOrder()
        assertThat(lines.take(2).map { it.minute }).containsExactly(null, null)
        assertThat(agendaOf(CalDay(day))).isEmpty()
    }

    @Test fun habitsLineOnlyForTodayAndPastDays() {
        val today = day
        assertThat(habitsLine(today, today, CalDay(today), 2 to 5)).isEqualTo("Thói quen 2/5")
        assertThat(habitsLine(today, today, CalDay(today), 5 to 5)).isEqualTo("Thói quen 5/5 ✓")
        assertThat(habitsLine(today, today, CalDay(today), 0 to 0)).isNull()
        assertThat(habitsLine(today.minusDays(1), today, CalDay(today.minusDays(1), habitsDone = 3), null)).isEqualTo("Thói quen: xong 3")
        assertThat(habitsLine(today.plusDays(1), today, CalDay(today.plusDays(1), habitsDone = 3), 1 to 2)).isNull()
    }

    @Test fun aWeekOpensOnTodayOrElseItsMonday() {
        val today = day
        assertThat(dayPickedInWeek(today.plusDays(2), today)).isEqualTo(today) // same week (Sat)
        assertThat(dayPickedInWeek(today.plusWeeks(1), today)).isEqualTo(LocalDate.of(2026, 11, 16))
        assertThat(dayPickedInWeek(today.minusWeeks(1), today)).isEqualTo(LocalDate.of(2026, 11, 2))
        // Monday and Sunday of this week still pick today
        assertThat(dayPickedInWeek(LocalDate.of(2026, 11, 9), today)).isEqualTo(today)
        assertThat(dayPickedInWeek(LocalDate.of(2026, 11, 15), today)).isEqualTo(today)
    }
}
