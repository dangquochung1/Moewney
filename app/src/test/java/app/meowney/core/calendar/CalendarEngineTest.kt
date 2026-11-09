package app.meowney.core.calendar

import app.meowney.core.db.EventEntity
import app.meowney.core.db.TodoEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CalendarEngineTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    private fun event(
        start: LocalDate,
        end: LocalDate = start,
        repeat: EventRepeat = EventRepeat.NONE,
        minute: Int? = null,
        until: LocalDate? = null,
        skip: List<LocalDate> = emptyList(),
        offsets: String = "15",
        remind: Boolean = true,
        id: Long = 1,
    ) = EventEntity(
        id = id, title = "E$id", allDay = minute == null, startDay = start.toEpochDay(), startMinute = minute, endDay = end.toEpochDay(),
        repeat = repeat.name, repeatUntil = until?.toEpochDay(), skipDays = skip.joinToString(",") { it.toEpochDay().toString() },
        remind = remind, remindOffsets = offsets, createdAt = 0,
    )

    @Test fun oneOffAndMultiDayCutByTheWindow() {
        val trip = event(d(2026, 11, 12), d(2026, 11, 15))
        assertThat(CalendarEngine.occurrences(trip, d(2026, 11, 14), d(2026, 11, 20))).hasSize(1)
        assertThat(CalendarEngine.occurrences(trip, d(2026, 11, 16), d(2026, 11, 20))).isEmpty()
        val o = CalendarEngine.occurrences(trip, d(2026, 11, 1), d(2026, 11, 30)).single()
        assertThat(o.multiDay).isTrue()
        assertThat(o.covers(d(2026, 11, 13))).isTrue()
    }

    @Test fun weeklyWithSkipAndUntil() {
        val tue = d(2026, 11, 10)
        val meet = event(tue, repeat = EventRepeat.WEEKLY, minute = 9 * 60, until = d(2026, 12, 8), skip = listOf(d(2026, 11, 24)))
        val days = CalendarEngine.occurrences(meet, d(2026, 11, 1), d(2026, 12, 31)).map { it.start.dayOfMonth }
        assertThat(days).containsExactly(10, 17, 1, 8).inOrder()
    }

    @Test fun monthlyOn31AndYearlyOnLeapDay() {
        val rent = event(d(2026, 1, 31), repeat = EventRepeat.MONTHLY)
        val months = CalendarEngine.occurrences(rent, d(2026, 1, 1), d(2026, 4, 30)).map { it.start }
        assertThat(months).containsExactly(d(2026, 1, 31), d(2026, 2, 28), d(2026, 3, 31), d(2026, 4, 30)).inOrder()
        val bday = event(d(2024, 2, 29), repeat = EventRepeat.YEARLY)
        assertThat(CalendarEngine.occurrences(bday, d(2026, 1, 1), d(2026, 12, 31)).single().start).isEqualTo(d(2026, 2, 28))
        assertThat(CalendarEngine.countdown(d(2024, 2, 29), d(2026, 2, 20))).isEqualTo(8)
    }

    @Test fun dailyRepeatingMultiDayStartingBeforeTheWindow() {
        val e = event(d(2026, 11, 1), d(2026, 11, 2), repeat = EventRepeat.DAILY)
        // the one starting on the 9th still covers the 10th
        val o = CalendarEngine.occurrences(e, d(2026, 11, 10), d(2026, 11, 10))
        assertThat(o.map { it.start.dayOfMonth }).containsExactly(9, 10)
    }

    @Test fun bandsInAWeekDoNotOverlap() {
        val mon = d(2026, 11, 9)
        val a = Occurrence(1, d(2026, 11, 8), d(2026, 11, 11), null, null, true)
        val b = Occurrence(2, d(2026, 11, 10), d(2026, 11, 12), null, null, true)
        val c = Occurrence(3, d(2026, 11, 13), d(2026, 11, 14), null, null, true)
        val slots = CalendarEngine.weekSlots(listOf(a, b, c), mon)
        assertThat(slots[a]).isNotEqualTo(slots[b])
        assertThat(slots[c]).isEqualTo(0) // free again later in the week
    }

    @Test fun remindersForTimedEventsAndTodos() {
        val now = LocalDateTime.of(2026, 11, 9, 8, 0)
        val meet = event(d(2026, 11, 9), minute = 9 * 60, offsets = "15,60", repeat = EventRepeat.DAILY, id = 1)
        val off = event(d(2026, 11, 9), minute = 10 * 60, remind = false, id = 2)
        val allDay = event(d(2026, 11, 9), id = 3)
        val todo = TodoEntity(id = 5, title = "Nộp báo cáo", dueDay = d(2026, 11, 9).toEpochDay(), dueMinute = 17 * 60, createdAt = 0)
        val done = todo.copy(id = 6, doneAt = 1)
        val r = CalendarEngine.nextReminders(listOf(meet, off, allDay), listOf(todo, done), now, days = 1)
        // 9th: 08:00 (60 min before) is not after now, 08:45 is; then the to-do; then the next days
        assertThat(r.take(4).map { it.at.toString() }).containsExactly(
            "2026-11-09T08:45", "2026-11-09T17:00", "2026-11-10T08:00", "2026-11-10T08:45",
        ).inOrder()
        assertThat(r[1].todoId).isEqualTo(5L)
        assertThat(r.none { it.eventId == 2L || it.eventId == 3L || it.todoId == 6L }).isTrue()
    }

    @Test fun todoCoinsAndRepeat() {
        assertThat(CalendarEngine.todoCoins(3, onTime = true)).isEqualTo(8)
        assertThat(CalendarEngine.todoCoins(1, onTime = false)).isEqualTo(2)
        assertThat(CalendarEngine.nextDue(EventRepeat.MONTHLY, d(2026, 1, 31))).isEqualTo(d(2026, 2, 28))
        assertThat(CalendarEngine.nextDue(EventRepeat.WEEKLY, d(2026, 11, 9))).isEqualTo(d(2026, 11, 16))
        assertThat(CalendarEngine.nextDue(EventRepeat.NONE, d(2026, 11, 9))).isNull()
    }
}
