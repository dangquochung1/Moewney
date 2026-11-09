package app.meowney.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class PlanEnginesTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun monthlyDay31ClampsToShortMonths() {
        val r = Repeat.MONTHLY
        assertThat(RecurrenceEngine.onOrAfter(r, 31, 0, d(2027, 1, 15))).isEqualTo(d(2027, 1, 31))
        assertThat(RecurrenceEngine.after(r, 31, 0, d(2027, 1, 31))).isEqualTo(d(2027, 2, 28))
        assertThat(RecurrenceEngine.after(r, 31, 0, d(2027, 2, 28))).isEqualTo(d(2027, 3, 31))
        assertThat(RecurrenceEngine.after(r, 31, 0, d(2028, 1, 31))).isEqualTo(d(2028, 2, 29))
        assertThat(RecurrenceEngine.onOrAfter(r, 5, 0, d(2026, 11, 5))).isEqualTo(d(2026, 11, 5))
        assertThat(RecurrenceEngine.onOrAfter(r, 5, 0, d(2026, 12, 6))).isEqualTo(d(2027, 1, 5))
    }

    @Test fun weeklyAndYearly() {
        // 2026-11-09 is a Monday
        assertThat(RecurrenceEngine.onOrAfter(Repeat.WEEKLY, 1, 0, d(2026, 11, 9))).isEqualTo(d(2026, 11, 9))
        assertThat(RecurrenceEngine.after(Repeat.WEEKLY, 1, 0, d(2026, 11, 9))).isEqualTo(d(2026, 11, 16))
        assertThat(RecurrenceEngine.onOrAfter(Repeat.WEEKLY, 7, 0, d(2026, 11, 9))).isEqualTo(d(2026, 11, 15))
        assertThat(RecurrenceEngine.onOrAfter(Repeat.YEARLY, 29, 2, d(2027, 1, 1))).isEqualTo(d(2027, 2, 28))
        assertThat(RecurrenceEngine.after(Repeat.YEARLY, 29, 2, d(2027, 2, 28))).isEqualTo(d(2028, 2, 29))
    }

    @Test fun goalSharesNeverExceedTheJarShare() {
        assertThat(GoalEngine.autoShares(1_000_000, listOf(1L to 50, 2L to 30))).containsExactly(1L, 500_000L, 2L, 300_000L)
        val over = GoalEngine.autoShares(1_000_000, listOf(1L to 80, 2L to 80))
        assertThat(over.values.sum()).isAtMost(1_000_000)
        assertThat(GoalEngine.autoShares(0, listOf(1L to 50))).isEmpty()
        assertThat(GoalEngine.autoShares(999, listOf(1L to 0))).isEmpty()
    }

    @Test fun milestonesCountOnce() {
        assertThat(GoalEngine.crossed(20_000_000, 0, 5_000_000)).containsExactly(25)
        assertThat(GoalEngine.crossed(20_000_000, 5_000_000, 16_000_000)).containsExactly(50, 75).inOrder()
        assertThat(GoalEngine.crossed(20_000_000, 16_000_000, 25_000_000)).containsExactly(100)
        assertThat(GoalEngine.crossed(20_000_000, 25_000_000, 26_000_000)).isEmpty()
        assertThat(GoalEngine.crossed(20_000_000, 6_000_000, 4_000_000)).isEmpty()
    }

    @Test fun savingsJarsLendOnlyFreeMoney() {
        val free = GoalEngine.freeBalances(mapOf(3L to 1_700_000L, 1L to 9_000_000L), mapOf(3L to 1_500_000L))
        assertThat(free).containsExactly(3L, 200_000L, 1L, 9_000_000L)
    }

    private fun numbers(logged: Int = 30, saved: Long = 2_000_000, within: Int = 30, loans: Boolean = false, negative: Boolean = false) =
        MonthNumbers(30, logged, saved, 2_000_000, 30, within, loans, negative)

    @Test fun reportGrades() {
        assertThat(ReportEngine.score(numbers()).grade).isEqualTo("S")
        assertThat(ReportEngine.score(numbers(logged = 24, within = 24)).grade).isEqualTo("S") // (.8 + 1 + .8 + 1) / 4 = .9
        assertThat(ReportEngine.score(numbers(logged = 21, within = 24)).grade).isEqualTo("A") // (.7 + 1 + .8 + 1) / 4 = .875
        assertThat(ReportEngine.score(numbers(logged = 15, within = 20, loans = true)).grade).isEqualTo("B")
        assertThat(ReportEngine.score(numbers(logged = 3, saved = 0, within = 5, negative = true)).grade).isEqualTo("C")
        assertThat(ReportEngine.score(numbers(logged = 3, saved = 0, within = 5, negative = true)).coins).isEqualTo(50)
        // nothing planned / no stats counts as done, so a new user is not punished
        assertThat(ReportEngine.score(MonthNumbers(30, 30, 0, 0, 0, 0, false, false)).grade).isEqualTo("S")
    }
}
