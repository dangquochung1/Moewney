package app.meowney.core.data

import androidx.room.withTransaction
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.DayStatEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.MonthReportEntity
import app.meowney.core.game.RewardRules
import app.meowney.core.money.EntryKind
import app.meowney.core.money.JarCode
import app.meowney.core.money.LoanRow
import app.meowney.core.money.MoneyEngine
import app.meowney.core.money.MonthNumbers
import app.meowney.core.money.MonthScore
import app.meowney.core.money.ReportEngine
import app.meowney.core.notes.MoodEngine
import app.meowney.core.notes.MoodSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth

/** One bar of a report chart. [code] is a jar code, [icon] a Raven icon index. */
@Serializable
data class Bar(val label: String, val amount: Long, val code: String = "", val icon: Int = -1)

/** Everything a report card shows. Stored as JSON so an old card never changes. */
@Serializable
data class ReportData(
    val yyyymm: Int,
    val from: Long,
    val to: Long,
    val income: Long,
    val expense: Long,
    val saved: Long,
    val planned: Long,
    val days: Int,
    val loggedDays: Int,
    val statDays: Int,
    val daysWithin: Int,
    val hasJarLoans: Boolean,
    val hasNegativeJar: Boolean,
    val byJar: List<Bar>,
    val byCategory: List<Bar>,
    /** Spending per day of the month, index 0 = the 1st. */
    val byDay: List<Long>,
    /** Diary moods of the month; null when nothing was written (and on cards kept before GĐ5). */
    val mood: MoodSummary? = null,
) {
    val numbers get() = MonthNumbers(days, loggedDays, saved, planned, statDays, daysWithin, hasJarLoans, hasNegativeJar)
    val score: MonthScore get() = ReportEngine.score(numbers)
}

/** A card to show: a kept one ([kept] != null) or this month's running preview. */
data class MonthReport(val month: YearMonth, val data: ReportData, val kept: MonthReportEntity?) {
    val final get() = kept != null
    val claimed get() = kept?.claimed == true
}

fun YearMonth.code() = year * 100 + monthValue
fun yearMonthOf(code: Int): YearMonth = YearMonth.of(code / 100, code % 100)

class Reports(private val db: MeowneyDb) {
    private val dao = db.reports()
    private val plans = db.plans()
    private val money = db.money()
    private val json = Json { ignoreUnknownKeys = true }

    /** Remembers what "hôm nay còn" was today (the limit the report checks spending against). */
    suspend fun recordDay(day: LocalDate, allowanceBase: Long, spent: Long, logged: Boolean) =
        plans.upsertDayStat(DayStatEntity(day.toEpochDay(), allowanceBase, spent, logged))

    /** Months that have data, newest first (this month included). */
    suspend fun months(today: LocalDate): List<YearMonth> {
        val first = dao.firstDay()?.let { YearMonth.from(LocalDate.ofEpochDay(it)) } ?: return emptyList()
        val out = mutableListOf<YearMonth>()
        var m = YearMonth.from(today)
        while (!m.isBefore(first)) {
            out += m
            m = m.minusMonths(1)
        }
        return out
    }

    /** A month's card. Past months are kept the first time they are looked at. */
    suspend fun load(month: YearMonth, today: LocalDate): MonthReport? {
        val current = YearMonth.from(today)
        if (month.isBefore(current)) {
            plans.report(month.code())?.let { kept ->
                return MonthReport(month, json.decodeFromString(ReportData.serializer(), kept.data), kept)
            }
            val data = compute(month, today) ?: return null
            val s = data.score
            val entity = MonthReportEntity(
                yyyymm = month.code(), grade = s.grade, score = s.total, coins = s.coins,
                data = json.encodeToString(ReportData.serializer(), data), createdDay = today.toEpochDay(),
            )
            plans.insertReport(entity)
            return MonthReport(month, data, plans.report(month.code()) ?: entity)
        }
        val data = compute(month, today) ?: return null
        return MonthReport(month, data, null)
    }

    /** Last month's card if its chest is still closed: shown on the first open of a month. */
    suspend fun pending(today: LocalDate): YearMonth? {
        val last = YearMonth.from(today).minusMonths(1)
        val r = load(last, today) ?: return null
        return if (r.claimed) null else last
    }

    /** Opens the chest once. Returns the coins (0 if already opened). */
    suspend fun claim(month: YearMonth, today: LocalDate): Int = db.withTransaction {
        val kept = plans.report(month.code()) ?: return@withTransaction 0
        if (kept.claimed) return@withTransaction 0
        plans.updateReport(kept.copy(claimed = true))
        money.insertCoins(listOf(CoinEntryEntity(delta = kept.coins, reason = R_REPORT, refId = month.code().toLong(), day = today.toEpochDay())))
        kept.coins
    }

    suspend fun compute(month: YearMonth, today: LocalDate): ReportData? {
        val firstDay = dao.firstDay() ?: return null
        val monthStart = month.atDay(1).toEpochDay()
        val monthEnd = month.atEndOfMonth().toEpochDay()
        val from = maxOf(monthStart, firstDay)
        val to = minOf(monthEnd, today.toEpochDay())
        if (from > to) return null

        val jars = money.jarsNow()
        val spendIds = jars.filter { it.code == JarCode.NEC.name || it.code == JarCode.PLAY.name }.map { it.id }
        val savingIds = jars.filter { JarCode.valueOf(it.code).isSavings }.map { it.id }
        val savingPercent = jars.filter { it.id in savingIds }.sumOf { it.percent }

        val logged = dao.loggedDays(from, to).toSet() + dao.coinDays(RewardRules.R_NO_SPEND, from, to)
        val spentInLimit = dao.spentByDayInJars(spendIds, from, to).associate { it.day to it.total }
        val stats = plans.dayStats(from, to)
        val within = stats.count { (spentInLimit[it.day] ?: 0L) <= it.allowanceBase.coerceAtLeast(0) }

        val income = dao.total("INCOME", from, to)
        val balances = dao.jarBalancesAt(to)
        val loans = MoneyEngine.loans(dao.loanSumsAt(to).map { LoanRow(it.jarId, it.peerJarId, EntryKind.valueOf(it.kind), it.total) })

        val jarById = jars.associateBy { it.id }
        val byJar = dao.spentByJar(from, to).filter { it.total > 0 }.mapNotNull { s ->
            jarById[s.id]?.let { Bar(it.name, s.total, code = it.code) }
        }.sortedByDescending { it.amount }
        val cats = money.categoriesNow().associateBy { it.id }
        val byCategory = dao.expenseByCategory(from, to).take(5).mapNotNull { s ->
            cats[s.id]?.let { Bar(it.name, s.total, icon = it.icon) }
        }
        val perDay = dao.expenseByDay(from, to).associate { it.day to it.total }
        val byDay = (monthStart..monthEnd).map { perDay[it] ?: 0L }
        val moods = db.notes().diariesNow(from, to).mapNotNull { it.day?.let { d -> it.mood?.let { m -> d to m } } }.toMap()
        val mood = MoodEngine.month(moods, from, to, emptyMap(), emptyMap()).summary.takeIf { it.written > 0 }

        return ReportData(
            yyyymm = month.code(),
            from = from,
            to = to,
            income = income,
            expense = dao.total("EXPENSE", from, to),
            saved = dao.savedInto(savingIds, from, to).coerceAtLeast(0),
            planned = income * savingPercent / 100,
            days = (to - from + 1).toInt(),
            loggedDays = logged.size,
            statDays = stats.size,
            daysWithin = within,
            hasJarLoans = loans.isNotEmpty(),
            hasNegativeJar = balances.any { it.total < 0 },
            byJar = byJar,
            byCategory = byCategory,
            byDay = byDay,
            mood = mood,
        )
    }

    companion object {
        const val R_REPORT = "report"
    }
}
