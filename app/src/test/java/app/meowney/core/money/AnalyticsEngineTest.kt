package app.meowney.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.YearMonth

class AnalyticsEngineTest {
    private val nov = YearMonth.of(2026, 11)

    @Test fun flowsFillMissingMonthsAndCrossTheYear() {
        val flows = AnalyticsEngine.flows(
            income = mapOf(202611 to 3_000_000L, 202609 to 4_500_000L),
            expense = mapOf(202611 to 1_200_000L, 202610 to 2_600_000L),
            end = nov, count = 3,
        )
        assertThat(flows.map { it.month }).containsExactly(YearMonth.of(2026, 9), YearMonth.of(2026, 10), nov).inOrder()
        assertThat(flows.map { it.income }).containsExactly(4_500_000L, 0L, 3_000_000L).inOrder()
        assertThat(flows.map { it.expense }).containsExactly(0L, 2_600_000L, 1_200_000L).inOrder()

        val jan = AnalyticsEngine.flows(emptyMap(), mapOf(202512 to 5L), YearMonth.of(2026, 1), 2)
        assertThat(jan.map { it.month }).containsExactly(YearMonth.of(2025, 12), YearMonth.of(2026, 1)).inOrder()
        assertThat(jan.first().expense).isEqualTo(5)
    }

    @Test fun averagesOnlyCountMonthsInUse() {
        val flows = AnalyticsEngine.flows(mapOf(202610 to 3_000_000L, 202611 to 5_000_000L), mapOf(202611 to 1_000_000L), nov, 6)
        // the book started in October: 2 months, not 6
        val t = AnalyticsEngine.trend(flows, first = YearMonth.of(2026, 10))
        assertThat(t.activeMonths).isEqualTo(2)
        assertThat(t.avgIncome).isEqualTo(4_000_000)
        assertThat(t.avgExpense).isEqualTo(500_000)
        assertThat(t.top).isEqualTo(5_000_000)
        // nothing logged yet: no division by zero
        assertThat(AnalyticsEngine.trend(flows, first = null).avgIncome).isEqualTo(0)
    }

    @Test fun sharesAddUpToAHundredAndFoldTheTail() {
        val rows = listOf(520L, 210L, 150L, 140L, 100L, 50L, 20L, 10L).mapIndexed { i, v -> ShareInput(i + 1L, "c$i", v * 1_000) }
        val shares = AnalyticsEngine.shares(rows, top = 6)
        assertThat(shares).hasSize(7)
        assertThat(shares.sumOf { it.percent }).isEqualTo(100)
        assertThat(shares.first().percent).isEqualTo(43)
        assertThat(shares.last().key).isEqualTo(AnalyticsEngine.OTHER)
        assertThat(shares.last().amount).isEqualTo(30_000)
        assertThat(shares.map { it.amount }).isInOrder(Comparator.reverseOrder<Long>())
        // three equal thirds still make 100
        val thirds = AnalyticsEngine.shares(listOf(ShareInput(1, "a", 1), ShareInput(2, "b", 1), ShareInput(3, "c", 1)))
        assertThat(thirds.map { it.percent }.sorted()).containsExactly(33, 33, 34).inOrder()
        assertThat(AnalyticsEngine.shares(emptyList())).isEmpty()
        assertThat(AnalyticsEngine.shares(listOf(ShareInput(1, "a", 0)))).isEmpty()
    }

    @Test fun sharesCompareWithLastMonth() {
        val rows = listOf(ShareInput(1, "Ăn uống", 500_000), ShareInput(2, "Đi lại", 200_000))
        val shares = AnalyticsEngine.shares(rows, previous = mapOf(1L to 420_000L))
        assertThat(shares[0].change).isEqualTo(80_000)
        assertThat(shares[1].change).isEqualTo(200_000) // nothing last month
        // no previous month at all: nothing to compare
        assertThat(AnalyticsEngine.shares(rows).map { it.change }).containsExactly(null, null)
    }

    @Test fun jarPlansCompareThePlanWithWhatHappened() {
        val jars = listOf(
            JarUse(1, "Thiết yếu", "NEC", 55, 610_000),
            JarUse(2, "Hưởng thụ", "PLAY", 10, 240_000),
            JarUse(3, "Tiết kiệm", "LTSS", 10, 200_000, savings = true),
            JarUse(4, "Cho đi", "GIVE", 5, 0),
        )
        // against an income of 1 million
        val plans = AnalyticsEngine.jarPlans(jars, income = 1_000_000)
        assertThat(plans.map { it.actual }).containsExactly(61, 24, 20, 0).inOrder()
        assertThat(plans[1].over).isTrue()   // spent 24% against a plan of 10%
        assertThat(plans[0].over).isFalse()  // 61% against 55% is within a fifth
        assertThat(plans[2].over).isFalse()  // saving double the plan is not a problem
        assertThat(plans[3].over).isFalse()
        // a month without income is measured against what was used (1.050.000)
        assertThat(AnalyticsEngine.jarPlans(jars, income = 0).map { it.actual }).containsExactly(58, 23, 19, 0).inOrder()
        // an empty month is all zeros, not a crash
        assertThat(AnalyticsEngine.jarPlans(listOf(JarUse(1, "a", "NEC", 55, 0)), income = 0).single().actual).isEqualTo(0)
    }

    @Test fun aKeyAlwaysGetsTheSameColourSlot() {
        assertThat(AnalyticsEngine.slot(7, 8)).isEqualTo(AnalyticsEngine.slot(7, 8))
        assertThat(AnalyticsEngine.slot(AnalyticsEngine.OTHER, 8)).isEqualTo(7)
        assertThat((1L..40L).map { AnalyticsEngine.slot(it, 8) }.all { it in 0..6 }).isTrue()
    }
}
