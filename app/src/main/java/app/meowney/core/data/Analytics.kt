package app.meowney.core.data

import app.meowney.core.db.MeowneyDb
import app.meowney.core.money.AnalyticsEngine
import app.meowney.core.money.JarCode
import app.meowney.core.money.JarPlan
import app.meowney.core.money.JarUse
import app.meowney.core.money.MonthFlow
import app.meowney.core.money.Share
import app.meowney.core.money.ShareInput
import app.meowney.core.money.Trend
import java.time.LocalDate
import java.time.YearMonth

/** Reads the ledger for the analysis tab; the arithmetic lives in [AnalyticsEngine]. */
class Analytics(private val db: MeowneyDb) {
    private val dao = db.reports()
    private val money = db.money()

    private fun from(m: YearMonth) = m.atDay(1).toEpochDay()
    private fun to(m: YearMonth) = m.atEndOfMonth().toEpochDay()

    /** The month of the very first entry, or null for an empty book. */
    suspend fun firstMonth(): YearMonth? = dao.firstDay()?.let { YearMonth.from(LocalDate.ofEpochDay(it)) }

    /** Income against expense for the [count] months ending at [end]. */
    suspend fun trend(end: YearMonth, count: Int): Trend {
        val start = end.minusMonths(count - 1L)
        val income = dao.totalsByMonth("INCOME", from(start), to(end)).associate { it.ym to it.total }
        val expense = dao.totalsByMonth("EXPENSE", from(start), to(end)).associate { it.ym to it.total }
        return AnalyticsEngine.trend(AnalyticsEngine.flows(income, expense, end, count), firstMonth())
    }

    /** Where the month's money went (or came from), by category, with the change since last month. */
    suspend fun shares(month: YearMonth, income: Boolean): List<Share> {
        val cats = money.categoriesNow().associateBy { it.id }
        suspend fun rows(m: YearMonth) = if (income) dao.incomeByCategory(from(m), to(m)) else dao.expenseByCategory(from(m), to(m))
        val first = firstMonth()
        val last = month.minusMonths(1)
        // no "change" against a month from before the book existed
        val previous = if (first != null && !last.isBefore(first)) rows(last).associate { it.id to it.total }.ifEmpty { mapOf(AnalyticsEngine.OTHER to 0L) } else emptyMap()
        return AnalyticsEngine.shares(
            rows(month).mapNotNull { r -> cats[r.id]?.let { ShareInput(r.id, it.name, r.total, it.icon) } },
            previous,
        )
    }

    /** One category's spending (or income) over the [count] months ending at [end]. */
    suspend fun categoryTrend(categoryId: Long, end: YearMonth, count: Int): Trend {
        val start = end.minusMonths(count - 1L)
        val byMonth = dao.categoryByMonth(categoryId, from(start), to(end)).associate { it.ym to it.total }
        return AnalyticsEngine.trend(AnalyticsEngine.flows(emptyMap(), byMonth, end, count), firstMonth())
    }

    /**
     * Each jar's planned percentage of the income against what it really took this month:
     * spending for the spending jars, money put in for the savings jars. Any number of jars.
     */
    suspend fun jarPlans(month: YearMonth): List<JarPlan> {
        val spent = dao.spentByJar(from(month), to(month)).associate { it.id to it.total }
        val saved = dao.savedByJar(from(month), to(month)).associate { it.id to it.total }
        val jars = money.jarsNow().filter { it.code != JarCode.UNALLOC.name }.sortedBy { it.sort }
        return AnalyticsEngine.jarPlans(
            jars.map { j ->
                val savings = JarCode.valueOf(j.code).isSavings
                JarUse(j.id, j.name, j.code, j.percent, if (savings) saved[j.id] ?: 0L else spent[j.id] ?: 0L, savings)
            },
            income = dao.total("INCOME", from(month), to(month)),
        )
    }

    /** Categories that have at least one expense, most used first, for the trend picker. */
    suspend fun expenseCategories(): List<Pair<Long, String>> {
        val cats = money.categoriesNow().associateBy { it.id }
        return dao.expenseByCategory(0, Long.MAX_VALUE / 2).mapNotNull { r -> cats[r.id]?.let { r.id to it.name } }
    }
}
