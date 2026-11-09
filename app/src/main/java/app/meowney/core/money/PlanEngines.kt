package app.meowney.core.money

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** How a bill repeats. */
enum class Repeat { MONTHLY, WEEKLY, YEARLY }

/** Due dates of recurring bills. */
object RecurrenceEngine {
    /**
     * The first due date on or after [from]. Day 31 in a 30-day month (or 29/30/31 in February)
     * falls on the month's last day; 29/2 yearly falls on 28/2 in other years.
     */
    fun onOrAfter(repeat: Repeat, day: Int, month: Int, from: LocalDate): LocalDate = when (repeat) {
        Repeat.WEEKLY -> from.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day.coerceIn(1, 7))))
        Repeat.MONTHLY -> {
            val here = clampDay(from.year, from.monthValue, day)
            if (here >= from) here else from.plusMonths(1).let { clampDay(it.year, it.monthValue, day) }
        }
        Repeat.YEARLY -> {
            val here = clampDay(from.year, month.coerceIn(1, 12), day)
            if (here >= from) here else clampDay(from.year + 1, month.coerceIn(1, 12), day)
        }
    }

    /** The due date after the one on [current]. */
    fun after(repeat: Repeat, day: Int, month: Int, current: LocalDate): LocalDate =
        onOrAfter(repeat, day, month, current.plusDays(1))

    private fun clampDay(year: Int, month: Int, day: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        return first.withDayOfMonth(day.coerceIn(1, first.lengthOfMonth()))
    }
}

/** Savings goals inside a jar. */
object GoalEngine {
    val MILESTONES = listOf(25, 50, 75, 100)
    val MILESTONE_COINS = mapOf(25 to 50, 50 to 100, 75 to 150, 100 to 300)

    /**
     * What each open goal gets from [jarShare] (the jar's part of an income): its autoPercent,
     * rounded down, never more than the share in total (percentages over 100 are scaled).
     */
    fun autoShares(jarShare: Long, goals: List<Pair<Long, Int>>): Map<Long, Long> {
        if (jarShare <= 0) return emptyMap()
        val active = goals.filter { it.second > 0 }
        val total = active.sumOf { it.second }.coerceAtLeast(100)
        return active.associate { (id, pct) -> id to jarShare * pct / total }.filterValues { it > 0 }
    }

    /** Milestones (25/50/75/100) passed when a goal goes from [before] to [after]. */
    fun crossed(target: Long, before: Long, after: Long): List<Int> {
        if (target <= 0) return emptyList()
        return MILESTONES.filter { m -> before * 100 < target * m && after * 100 >= target * m }
    }

    /** Jar balances where savings jars only offer what their goals do not hold. */
    fun freeBalances(balances: Map<Long, Long>, goalTotalsByJar: Map<Long, Long>): Map<Long, Long> =
        balances.mapValues { (jar, b) -> b - (goalTotalsByJar[jar] ?: 0) }
}

/** Numbers of one month that the report card scores. */
data class MonthNumbers(
    val daysInMonth: Int,
    /** Days with any transaction or a "không tiêu" claim. */
    val loggedDays: Int,
    /** Money put into the savings jars and goals this month. */
    val saved: Long,
    /** What the jar percentages planned for savings from this month's income. */
    val plannedSaving: Long,
    /** Days with a recorded allowance... */
    val statDays: Int,
    /** ...of which spending stayed within it. */
    val daysWithin: Int,
    val hasJarLoans: Boolean,
    val hasNegativeJar: Boolean,
)

data class MonthScore(
    val logging: Float,
    val saving: Float,
    val limit: Float,
    val noDebt: Float,
) {
    val total get() = (logging + saving + limit + noDebt) / 4f
    val grade get() = when {
        total >= 0.9f -> "S"
        total >= 0.75f -> "A"
        total >= 0.55f -> "B"
        else -> "C"
    }
    val coins get() = when (grade) {
        "S" -> 300
        "A" -> 200
        "B" -> 100
        else -> 50
    }
}

/** The monthly report card. Every criterion is 0..1; nothing to do counts as done. */
object ReportEngine {
    fun score(n: MonthNumbers): MonthScore = MonthScore(
        logging = ratio(n.loggedDays.toLong(), n.daysInMonth.toLong()),
        saving = if (n.plannedSaving <= 0) 1f else ratio(n.saved, n.plannedSaving),
        limit = if (n.statDays == 0) 1f else ratio(n.daysWithin.toLong(), n.statDays.toLong()),
        noDebt = when {
            n.hasNegativeJar -> 0f
            n.hasJarLoans -> 0.5f
            else -> 1f
        },
    )

    private fun ratio(a: Long, b: Long) = if (b <= 0) 1f else (a.toFloat() / b).coerceIn(0f, 1f)
}
