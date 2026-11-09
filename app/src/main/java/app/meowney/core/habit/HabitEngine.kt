package app.meowney.core.habit

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class HabitType(val label: String) {
    CHECK("Có/không"),
    COUNT("Đếm số lượng"),
    TIMER("Hẹn giờ"),
    QUIT("Bỏ thói quen"),
}

enum class Schedule { DAILY, WEEKDAYS, WEEKLY }

/** In the order the Today panel shows them. */
enum class PartOfDay(val label: String, val fromHour: Int) {
    MORNING("Sáng", 5),
    ALL("Cả ngày", 0),
    AFTERNOON("Chiều", 12),
    EVENING("Tối", 18),
    ;

    companion object {
        fun now(hour: Int): PartOfDay = when (hour) {
            in 5..11 -> MORNING
            in 12..17 -> AFTERNOON
            else -> EVENING
        }
    }
}

/** When a habit is expected. [weekdays]: bit 0 = Monday ... bit 6 = Sunday. */
data class HabitRule(val schedule: Schedule, val weekdays: Int = 0x7F, val timesPerWeek: Int = 0)

/** A stretch that is judged as a whole: one day, or a Monday..Sunday week for WEEKLY habits. */
data class Period(val start: LocalDate, val end: LocalDate)

data class HabitProgress(val strength: Float = 0f, val streak: Int = 0, val best: Int = 0, val tickets: Int = 0)

enum class Outcome { KEPT, RESTED, MISSED }

object HabitEngine {
    const val TICKET_EVERY = 7
    const val MAX_TICKETS = 3

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Does the habit show up on [day]? Weekly habits show every day until the week is done. */
    fun isScheduled(rule: HabitRule, day: LocalDate): Boolean = when (rule.schedule) {
        Schedule.DAILY, Schedule.WEEKLY -> true
        Schedule.WEEKDAYS -> rule.weekdays and (1 shl (day.dayOfWeek.value - 1)) != 0
    }

    /**
     * Periods that ended after [closedThrough] and no later than [yesterday], oldest first.
     * A week that began before the habit existed is skipped (no fair target for it).
     */
    fun periodsToClose(rule: HabitRule, created: LocalDate, closedThrough: LocalDate, yesterday: LocalDate): List<Period> {
        if (!yesterday.isAfter(closedThrough)) return emptyList()
        val out = mutableListOf<Period>()
        if (rule.schedule == Schedule.WEEKLY) {
            var sunday = weekStart(closedThrough.plusDays(1)).plusDays(6)
            while (!sunday.isAfter(yesterday)) {
                val monday = sunday.minusDays(6)
                if (!monday.isBefore(created)) out += Period(monday, sunday)
                sunday = sunday.plusWeeks(1)
            }
        } else {
            var d = closedThrough.plusDays(1)
            while (!d.isAfter(yesterday)) {
                if (!d.isBefore(created) && isScheduled(rule, d)) out += Period(d, d)
                d = d.plusDays(1)
            }
        }
        return out
    }

    /**
     * Judges one period. Kept: strength climbs toward 100 and the streak grows, every 7th
     * keeps earns a rest ticket. Missed: a ticket forgives it; without one the strength drops
     * a little (never to 0 at once) and the streak restarts.
     */
    fun close(p: HabitProgress, kept: Boolean, weekly: Boolean): Pair<HabitProgress, Outcome> {
        val gain = if (weekly) 0.3f else 0.12f
        val loss = if (weekly) 0.35f else 0.2f
        return when {
            kept -> {
                val streak = p.streak + 1
                val tickets = if (streak % TICKET_EVERY == 0) minOf(MAX_TICKETS, p.tickets + 1) else p.tickets
                HabitProgress(p.strength + (100f - p.strength) * gain, streak, maxOf(p.best, streak), tickets) to Outcome.KEPT
            }
            p.tickets > 0 -> p.copy(tickets = p.tickets - 1) to Outcome.RESTED
            else -> p.copy(strength = p.strength * (1f - loss), streak = 0) to Outcome.MISSED
        }
    }
}

// ------------------------------------------------------------------ Pomodoro

data class PomodoroConfig(val focus: Int = 45, val shortBreak: Int = 10, val longBreak: Int = 20, val longEvery: Int = 4)

enum class Phase { FOCUS, SHORT_BREAK, LONG_BREAK, READY }

/**
 * A running Pomodoro, stored as timestamps so it survives the app being closed.
 * [focusDone] = focus blocks finished in this run. [pausedAt] set = paused.
 */
data class PomodoroState(
    val phase: Phase,
    val phaseStart: Long,
    val focusDone: Int = 0,
    val habitId: Long? = null,
    val pausedAt: Long? = null,
)

object PomodoroEngine {
    fun lengthMs(cfg: PomodoroConfig, phase: Phase): Long = 60_000L * when (phase) {
        Phase.FOCUS -> cfg.focus
        Phase.SHORT_BREAK -> cfg.shortBreak
        Phase.LONG_BREAK -> cfg.longBreak
        Phase.READY -> 0
    }

    fun endsAt(cfg: PomodoroConfig, s: PomodoroState): Long = s.phaseStart + lengthMs(cfg, s.phase)

    fun remainingMs(cfg: PomodoroConfig, s: PomodoroState, now: Long): Long =
        (endsAt(cfg, s) - (s.pausedAt ?: now)).coerceAtLeast(0)

    fun start(habitId: Long?, now: Long, focusDone: Int = 0) = PomodoroState(Phase.FOCUS, now, focusDone, habitId)

    fun pause(s: PomodoroState, now: Long) = if (s.pausedAt == null) s.copy(pausedAt = now) else s

    /** Resuming shifts the start so the paused time does not count. */
    fun resume(s: PomodoroState, now: Long) = s.pausedAt?.let { s.copy(phaseStart = s.phaseStart + (now - it), pausedAt = null) } ?: s

    /** What ended while time passed: finished focus blocks and the state now. */
    data class Advance(val state: PomodoroState, val focusEnded: List<Long>)

    /**
     * Moves through every phase that has ended by [now]. A focus block ends into a break,
     * a break ends into READY (the next focus waits for Sen to start it).
     */
    fun advance(cfg: PomodoroConfig, s: PomodoroState, now: Long): Advance {
        var state = s
        val ended = mutableListOf<Long>()
        while (state.pausedAt == null && state.phase != Phase.READY && now >= endsAt(cfg, state)) {
            val end = endsAt(cfg, state)
            state = if (state.phase == Phase.FOCUS) {
                ended += state.phaseStart
                val done = state.focusDone + 1
                val next = if (done % cfg.longEvery == 0) Phase.LONG_BREAK else Phase.SHORT_BREAK
                state.copy(phase = next, phaseStart = end, focusDone = done)
            } else {
                state.copy(phase = Phase.READY, phaseStart = end)
            }
        }
        return Advance(state, ended)
    }
}
