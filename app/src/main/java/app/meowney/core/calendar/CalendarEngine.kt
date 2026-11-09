package app.meowney.core.calendar

import app.meowney.core.db.EventEntity
import app.meowney.core.db.TodoEntity
import app.meowney.core.game.RewardRules
import app.meowney.core.money.Repeat
import app.meowney.core.money.RecurrenceEngine
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

enum class EventRepeat(val label: String) {
    NONE("Không lặp"),
    DAILY("Hằng ngày"),
    WEEKLY("Hằng tuần"),
    MONTHLY("Hằng tháng"),
    YEARLY("Hằng năm"),
}

/** One time an event happens. Multi-day occurrences keep their length. */
data class Occurrence(
    val eventId: Long,
    val start: LocalDate,
    val end: LocalDate,
    val startMinute: Int?,
    val endMinute: Int?,
    val allDay: Boolean,
) {
    val multiDay get() = end.isAfter(start)
    fun covers(day: LocalDate) = !day.isBefore(start) && !day.isAfter(end)
}

/** A reminder due at [at]: an event occurrence or a to-do. */
data class ReminderAt(val at: LocalDateTime, val eventId: Long?, val todoId: Long?, val day: LocalDate, val title: String, val minutesBefore: Int)

val EventEntity.rep get() = EventRepeat.valueOf(repeat)
val EventEntity.start: LocalDate get() = LocalDate.ofEpochDay(startDay)
val EventEntity.end: LocalDate get() = LocalDate.ofEpochDay(endDay)
val EventEntity.skipped: Set<LocalDate> get() = skipDays.split(',').mapNotNull { it.trim().toLongOrNull()?.let(LocalDate::ofEpochDay) }.toSet()
val EventEntity.offsets: List<Int> get() = remindOffsets.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted()

object CalendarEngine {
    /** Occurrences of [e] that touch [from]..[to] (inclusive), oldest first. */
    fun occurrences(e: EventEntity, from: LocalDate, to: LocalDate): List<Occurrence> {
        val length = ChronoUnit.DAYS.between(e.start, e.end).coerceAtLeast(0)
        val until = e.repeatUntil?.let(LocalDate::ofEpochDay)
        val skip = e.skipped
        val out = mutableListOf<Occurrence>()
        fun add(s: LocalDate) {
            if (s.isBefore(e.start) || (until != null && s.isAfter(until)) || s in skip) return
            val end = s.plusDays(length)
            if (s.isAfter(to) || end.isBefore(from)) return
            out += Occurrence(e.id, s, end, e.startMinute, e.endMinute, e.allDay)
        }
        // an occurrence that started up to [length] days before [from] still shows
        val windowStart = maxOf(e.start, from.minusDays(length))
        when (e.rep) {
            EventRepeat.NONE -> add(e.start)
            EventRepeat.DAILY -> {
                var d = windowStart
                while (!d.isAfter(to)) {
                    add(d)
                    d = d.plusDays(1)
                }
            }
            EventRepeat.WEEKLY -> {
                val weeks = ChronoUnit.WEEKS.between(e.start, windowStart).coerceAtLeast(0)
                var d = e.start.plusWeeks(weeks)
                if (d.isBefore(windowStart)) d = d.plusWeeks(1)
                while (!d.isAfter(to)) {
                    add(d)
                    d = d.plusWeeks(1)
                }
            }
            EventRepeat.MONTHLY, EventRepeat.YEARLY -> {
                val r = if (e.rep == EventRepeat.MONTHLY) Repeat.MONTHLY else Repeat.YEARLY
                var d = RecurrenceEngine.onOrAfter(r, e.start.dayOfMonth, e.start.monthValue, windowStart)
                while (!d.isAfter(to)) {
                    add(d)
                    d = RecurrenceEngine.after(r, e.start.dayOfMonth, e.start.monthValue, d)
                }
            }
        }
        return out
    }

    /**
     * Rows for multi-day bands in one week: each band keeps one row across the days it
     * covers, and two bands never share a row on the same day. Longest first.
     */
    fun weekSlots(bands: List<Occurrence>, monday: LocalDate): Map<Occurrence, Int> {
        val sunday = monday.plusDays(6)
        val inWeek = bands.filter { !it.end.isBefore(monday) && !it.start.isAfter(sunday) }
            .sortedWith(compareBy<Occurrence> { maxOf(it.start, monday) }.thenByDescending { ChronoUnit.DAYS.between(it.start, it.end) })
        val rows = mutableListOf<MutableList<Occurrence>>()
        val out = mutableMapOf<Occurrence, Int>()
        for (o in inWeek) {
            val free = rows.indexOfFirst { row -> row.none { it.start <= o.end && o.start <= it.end } }
            val i = if (free >= 0) {
                free
            } else {
                rows.add(mutableListOf())
                rows.lastIndex
            }
            rows[i] += o
            out[o] = i
        }
        return out
    }

    /**
     * Exact reminders after [now] within [days]: timed events (each offset before the start)
     * and to-dos with a time. All-day events and to-dos without a time go in the 8:00
     * morning message instead.
     */
    fun nextReminders(events: List<EventEntity>, todos: List<TodoEntity>, now: LocalDateTime, days: Long = 8): List<ReminderAt> {
        val from = now.toLocalDate()
        val to = from.plusDays(days)
        val out = mutableListOf<ReminderAt>()
        for (e in events) {
            if (!e.remind || e.allDay || e.startMinute == null) continue
            for (o in occurrences(e, from, to.plusDays(1))) {
                val start = o.start.atStartOfDay().plusMinutes(e.startMinute.toLong())
                for (off in e.offsets) {
                    val at = start.minusMinutes(off.toLong())
                    if (at.isAfter(now)) out += ReminderAt(at, e.id, null, o.start, e.title, off)
                }
            }
        }
        for (t in todos) {
            if (!t.remind || t.doneAt != null || t.dueDay == null || t.dueMinute == null) continue
            val at = LocalDate.ofEpochDay(t.dueDay).atStartOfDay().plusMinutes(t.dueMinute.toLong())
            if (at.isAfter(now) && !at.toLocalDate().isAfter(to)) out += ReminderAt(at, null, t.id, at.toLocalDate(), t.title, 0)
        }
        return out.sortedBy { it.at }
    }

    /** Days until the next yearly occurrence of [birthday] (0 = today). */
    fun countdown(birthday: LocalDate, today: LocalDate): Long {
        val next = RecurrenceEngine.onOrAfter(Repeat.YEARLY, birthday.dayOfMonth, birthday.monthValue, today)
        return ChronoUnit.DAYS.between(today, next)
    }

    /** Coins for finishing a to-do: by difficulty, +2 when done on or before its day. */
    fun todoCoins(difficulty: Int, onTime: Boolean) = RewardRules.habitCoins(difficulty) + if (onTime) ON_TIME_BONUS else 0

    const val ON_TIME_BONUS = 2

    /** The next due day of a repeating to-do after [due]. */
    fun nextDue(repeat: EventRepeat, due: LocalDate): LocalDate? = when (repeat) {
        EventRepeat.NONE -> null
        EventRepeat.DAILY -> due.plusDays(1)
        EventRepeat.WEEKLY -> due.plusWeeks(1)
        EventRepeat.MONTHLY -> RecurrenceEngine.after(Repeat.MONTHLY, due.dayOfMonth, 0, due)
        EventRepeat.YEARLY -> RecurrenceEngine.after(Repeat.YEARLY, due.dayOfMonth, due.monthValue, due)
    }
}
