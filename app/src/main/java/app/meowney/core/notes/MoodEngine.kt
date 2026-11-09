package app.meowney.core.notes

import kotlinx.serialization.Serializable

/** The mood line of the monthly report card (not part of the grade). */
@Serializable
data class MoodSummary(val written: Int, val good: Int, val ok: Int, val bad: Int)

/** One month of moods for the stats page. Averages are null when there are no such days. */
data class MoodMonth(
    /** Days per mood, index 0 = rất tệ. */
    val counts: List<Int>,
    val written: Int,
    val days: Int,
    val spentGood: Long?,
    val spentBad: Long?,
    /** Habits done in % on good and bad days. */
    val habitsGood: Int?,
    val habitsBad: Int?,
) {
    val summary get() = MoodSummary(written, counts[3] + counts[4], counts[2], counts[0] + counts[1])
}

object MoodEngine {
    const val DIARY_COINS = 3

    /** Bonus for a writing streak of exactly that many days. */
    val STREAK_BONUS = mapOf(7 to 10, 30 to 30, 100 to 100)

    /**
     * Days written in a row up to [today]. If today is not written yet the streak still counts
     * up to yesterday (it is only broken once today is over).
     */
    fun streak(written: Set<Long>, today: Long): Int {
        var d = if (today in written) today else today - 1
        var n = 0
        while (d in written) { n++; d-- }
        return n
    }

    /** Three bad days in a row ending today. */
    fun lowStreak(moods: Map<Long, Int>, today: Long): Boolean =
        (0..2).all { (moods[today - it] ?: 99) <= 2 }

    fun month(
        moods: Map<Long, Int>,
        from: Long,
        to: Long,
        spent: Map<Long, Long>,
        habits: Map<Long, Pair<Int, Int>>,
    ): MoodMonth {
        val inMonth = moods.filterKeys { it in from..to }
        val counts = List(5) { i -> inMonth.values.count { it == i + 1 } }
        val good = inMonth.filterValues { it >= 4 }.keys
        val bad = inMonth.filterValues { it <= 2 }.keys
        fun avgSpent(days: Set<Long>) = if (days.isEmpty()) null else days.sumOf { spent[it] ?: 0L } / days.size
        fun habitPct(days: Set<Long>): Int? {
            val r = days.mapNotNull { habits[it] }.filter { it.second > 0 }
            if (r.isEmpty()) return null
            return r.sumOf { it.first } * 100 / r.sumOf { it.second }
        }
        return MoodMonth(counts, inMonth.size, (to - from + 1).toInt(), avgSpent(good), avgSpent(bad), habitPct(good), habitPct(bad))
    }
}
