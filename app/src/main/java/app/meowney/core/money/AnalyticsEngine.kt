package app.meowney.core.money

import java.time.YearMonth

/** Income and expense of one month. */
data class MonthFlow(val month: YearMonth, val income: Long, val expense: Long)

/** [months] oldest first; the averages only count the months the book was in use. */
data class Trend(val months: List<MonthFlow>, val avgIncome: Long, val avgExpense: Long, val activeMonths: Int) {
    val top get() = months.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0L
}

/** One row going into a share chart: a category (or wallet, jar...) and its amount. */
data class ShareInput(val key: Long, val label: String, val amount: Long, val icon: Int = -1)

/**
 * One slice of a share chart. [key] is the thing's own id, so its colour stays the same from
 * month to month ([AnalyticsEngine.OTHER] for the folded tail). [change] is the difference
 * to the previous month, null when there was nothing to compare with.
 */
data class Share(val key: Long, val label: String, val amount: Long, val percent: Int, val icon: Int = -1, val change: Long? = null)

/** A jar's planned share of the income against what really happened. Both in percent. */
data class JarPlan(val jarId: Long, val name: String, val code: String, val planned: Int, val actual: Int, val amount: Long, val savings: Boolean = false) {
    /** Spent more than a fifth over the plan (and at least 3 points). Saving more than planned is never "over". */
    val over get() = !savings && actual > planned && actual - planned >= maxOf(3, planned / 5)
}

/** What a jar did this month: [used] = spent from it, or for a savings jar what went into it. */
data class JarUse(val jarId: Long, val name: String, val code: String, val percent: Int, val used: Long, val savings: Boolean = false)

/** The numbers behind the analysis tab, free of Android and the database. */
object AnalyticsEngine {
    const val OTHER = -1L

    fun code(m: YearMonth) = m.year * 100 + m.monthValue

    /**
     * The [count] months ending at [end], oldest first. [income] and [expense] are totals by
     * month code (yyyymm); a month without rows is a zero, not a gap.
     */
    fun flows(income: Map<Int, Long>, expense: Map<Int, Long>, end: YearMonth, count: Int): List<MonthFlow> =
        (count - 1 downTo 0).map { back ->
            val m = end.minusMonths(back.toLong())
            MonthFlow(m, income[code(m)] ?: 0L, expense[code(m)] ?: 0L)
        }

    /**
     * Averages over the months since [first] (the first month with any entry) only, so three
     * weeks of use are not divided by six months.
     */
    fun trend(months: List<MonthFlow>, first: YearMonth?): Trend {
        val active = months.filter { first != null && !it.month.isBefore(first) }
        val n = active.size
        return Trend(
            months = months,
            avgIncome = if (n == 0) 0 else active.sumOf { it.income } / n,
            avgExpense = if (n == 0) 0 else active.sumOf { it.expense } / n,
            activeMonths = n,
        )
    }

    /**
     * The biggest [top] rows plus one "Khác" row for the rest. Percentages are whole numbers
     * that add up to exactly 100 (largest remainders get the spare points).
     */
    fun shares(rows: List<ShareInput>, previous: Map<Long, Long> = emptyMap(), top: Int = 6, otherLabel: String = "Khác"): List<Share> {
        val sorted = rows.filter { it.amount > 0 }.sortedWith(compareByDescending<ShareInput> { it.amount }.thenBy { it.key })
        val total = sorted.sumOf { it.amount }
        if (total <= 0) return emptyList()
        val head = sorted.take(top)
        val tail = sorted.drop(top)
        val merged = head + if (tail.isEmpty()) emptyList() else listOf(ShareInput(OTHER, otherLabel, tail.sumOf { it.amount }))
        val floors = merged.map { (it.amount * 100 / total).toInt() }
        var spare = 100 - floors.sum()
        val order = merged.indices.sortedByDescending { merged[it].amount * 100 % total }
        val percents = floors.toMutableList()
        for (i in order) {
            if (spare <= 0) break
            percents[i]++
            spare--
        }
        val tailBefore = tail.sumOf { previous[it.key] ?: 0L }
        return merged.mapIndexed { i, r ->
            val before = if (r.key == OTHER) tailBefore.takeIf { previous.isNotEmpty() } else previous[r.key] ?: 0L.takeIf { previous.isNotEmpty() }
            Share(r.key, r.label, r.amount, percents[i], r.icon, before?.let { r.amount - it })
        }
    }

    /**
     * Each jar's planned percentage of the income against what it really took of this month's
     * [income]. A month without income is measured against the money that was used instead,
     * so the bars still say where it went.
     */
    fun jarPlans(jars: List<JarUse>, income: Long): List<JarPlan> {
        val base = if (income > 0) income else jars.sumOf { it.used.coerceAtLeast(0) }
        return jars.map { j ->
            val used = j.used.coerceAtLeast(0)
            JarPlan(j.jarId, j.name, j.code, j.percent, if (base <= 0) 0 else Math.round(used * 100.0 / base).toInt(), used, j.savings)
        }
    }

    /** A steady colour slot for a key: the same category always lands on the same slot. */
    fun slot(key: Long, slots: Int): Int = if (key == OTHER) slots - 1 else (Math.floorMod(key, (slots - 1).toLong())).toInt()
}
