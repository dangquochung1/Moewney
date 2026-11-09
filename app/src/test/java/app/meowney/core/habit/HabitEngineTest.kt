package app.meowney.core.habit

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class HabitEngineTest {
    private val mon = LocalDate.of(2026, 11, 9) // a Monday

    @Test fun weekdaysBitmask() {
        val monWedFri = HabitRule(Schedule.WEEKDAYS, weekdays = 0b0010101)
        assertThat(HabitEngine.isScheduled(monWedFri, mon)).isTrue()
        assertThat(HabitEngine.isScheduled(monWedFri, mon.plusDays(1))).isFalse()
        assertThat(HabitEngine.isScheduled(monWedFri, mon.plusDays(4))).isTrue()
        assertThat(HabitEngine.isScheduled(HabitRule(Schedule.WEEKDAYS, weekdays = 1 shl 6), mon.plusDays(6))).isTrue()
    }

    @Test fun dailyPeriodsOnlyScheduledDaysSinceCreation() {
        val rule = HabitRule(Schedule.WEEKDAYS, weekdays = 0b0010101)
        val periods = HabitEngine.periodsToClose(rule, created = mon, closedThrough = mon.minusDays(1), yesterday = mon.plusDays(6))
        assertThat(periods.map { it.start.dayOfWeek.value }).containsExactly(1, 3, 5).inOrder()
        assertThat(HabitEngine.periodsToClose(rule, mon, mon.plusDays(6), mon.plusDays(6))).isEmpty()
    }

    @Test fun weeklyClosesOnlyAfterSundayAndSkipsPartialFirstWeek() {
        val rule = HabitRule(Schedule.WEEKLY, timesPerWeek = 3)
        // created on Wednesday: that week is not judged
        val wed = mon.plusDays(2)
        assertThat(HabitEngine.periodsToClose(rule, wed, wed.minusDays(1), mon.plusDays(6))).isEmpty()
        // created Monday: closing on Saturday does nothing, on Sunday closes the week
        assertThat(HabitEngine.periodsToClose(rule, mon, mon.minusDays(1), mon.plusDays(5))).isEmpty()
        val week = HabitEngine.periodsToClose(rule, mon, mon.plusDays(5), mon.plusDays(6)).single()
        assertThat(week).isEqualTo(Period(mon, mon.plusDays(6)))
        // two weeks later, both weeks come out
        assertThat(HabitEngine.periodsToClose(rule, mon, mon.minusDays(1), mon.plusDays(14))).hasSize(2)
    }

    @Test fun strengthStreakAndTickets() {
        var p = HabitProgress()
        repeat(7) { p = HabitEngine.close(p, kept = true, weekly = false).first }
        assertThat(p.streak).isEqualTo(7)
        assertThat(p.tickets).isEqualTo(1)
        assertThat(p.strength).isWithin(0.1f).of(100f * (1 - Math.pow(0.88, 7.0).toFloat()))

        // a miss with a ticket is forgiven
        val (rested, o1) = HabitEngine.close(p, kept = false, weekly = false)
        assertThat(o1).isEqualTo(Outcome.RESTED)
        assertThat(rested.strength).isEqualTo(p.strength)
        assertThat(rested.streak).isEqualTo(7)
        assertThat(rested.tickets).isEqualTo(0)

        // without one the strength drops by a fifth and the streak restarts
        val (missed, o2) = HabitEngine.close(rested, kept = false, weekly = false)
        assertThat(o2).isEqualTo(Outcome.MISSED)
        assertThat(missed.strength).isWithin(0.01f).of(rested.strength * 0.8f)
        assertThat(missed.streak).isEqualTo(0)
        assertThat(missed.best).isEqualTo(7)
    }

    @Test fun ticketsCapAtThree() {
        var p = HabitProgress()
        repeat(50) { p = HabitEngine.close(p, kept = true, weekly = false).first }
        assertThat(p.tickets).isEqualTo(HabitEngine.MAX_TICKETS)
        assertThat(p.strength).isLessThan(100.001f)
    }

    @Test fun pomodoroRunsThroughPhases() {
        val cfg = PomodoroConfig(focus = 45, shortBreak = 10, longBreak = 20, longEvery = 2)
        val min = 60_000L
        var s = PomodoroEngine.start(habitId = 7, now = 0)
        assertThat(PomodoroEngine.remainingMs(cfg, s, 5 * min)).isEqualTo(40 * min)

        // paused for 5 minutes: the focus ends 5 minutes later
        s = PomodoroEngine.resume(PomodoroEngine.pause(s, 10 * min), 15 * min)
        assertThat(PomodoroEngine.endsAt(cfg, s)).isEqualTo(50 * min)

        // the app was closed for an hour: focus ended, break ended, now waiting
        val a = PomodoroEngine.advance(cfg, s, 120 * min)
        assertThat(a.focusEnded).hasSize(1)
        assertThat(a.state.phase).isEqualTo(Phase.READY)
        assertThat(a.state.focusDone).isEqualTo(1)

        // second focus is followed by the long break
        val second = PomodoroEngine.start(7, 200 * min, focusDone = a.state.focusDone)
        val b = PomodoroEngine.advance(cfg, second, 250 * min)
        assertThat(b.state.phase).isEqualTo(Phase.LONG_BREAK)
        assertThat(PomodoroEngine.remainingMs(cfg, b.state, 250 * min)).isEqualTo(15 * min)
    }
}
