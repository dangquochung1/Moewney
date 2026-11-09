package app.meowney.core.data

import androidx.room.withTransaction
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.HabitEntity
import app.meowney.core.db.HabitLogEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.format.formatVndShort
import app.meowney.core.game.RewardRules
import app.meowney.core.habit.HabitEngine
import app.meowney.core.habit.HabitProgress
import app.meowney.core.habit.HabitRule
import app.meowney.core.habit.HabitType
import app.meowney.core.habit.Outcome
import app.meowney.core.habit.PartOfDay
import app.meowney.core.habit.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

val HabitEntity.kind get() = HabitType.valueOf(type)
val HabitEntity.rule get() = HabitRule(Schedule.valueOf(schedule), weekdays, timesPerWeek)
val HabitEntity.part get() = PartOfDay.valueOf(partOfDay)
val HabitEntity.hasMoney get() = moneyAmount > 0 && moneyFromJarId != null && (moneyToGoalId != null || moneyToJarId != null)

/** A habit as the Today panel sees it. */
data class HabitToday(val habit: HabitEntity, val log: HabitLogEntity?, val weekDone: Int) {
    val value get() = log?.value ?: 0
    val failed get() = log?.status == "FAILED"

    /** Quit habits count as held until Sen says otherwise. */
    val done get() = if (habit.kind == HabitType.QUIT) !failed else log?.status == "DONE"

    /** Weekly habits drop out of the list once the week's count is reached (unless done today). */
    val visible get() = habit.rule.schedule != Schedule.WEEKLY || weekDone < habit.timesPerWeek || log?.status == "DONE"
}

/** What one tap earned, for the cat to say. */
data class HabitResult(val coins: Int = 0, val notes: List<String> = emptyList(), val allDone: Boolean = false, val moved: Long = 0)

/** What closing the past days did, for the cat's morning summary. */
data class CloseSummary(val kept: List<String> = emptyList(), val rested: List<String> = emptyList(), val missed: List<String> = emptyList(), val coins: Int = 0, val notes: List<String> = emptyList()) {
    val isEmpty get() = kept.isEmpty() && rested.isEmpty() && missed.isEmpty() && notes.isEmpty()
}

class HabitRepository(private val db: MeowneyDb, private val money: MoneyRepository) {
    private val dao = db.habits()
    private val coinsDao = db.money()
    private val closing = Mutex()

    val habits: Flow<List<HabitEntity>> = dao.habits()
    fun habit(id: Long) = dao.habitFlow(id)
    suspend fun habitNow(id: Long) = dao.habit(id)
    suspend fun habitsNow() = dao.habitsNow()
    fun logs(habitId: Long, from: LocalDate, to: LocalDate) = dao.logsBetweenFlow(habitId, from.toEpochDay(), to.toEpochDay())

    /** Today's due habits with their logs and this week's count. */
    fun today(day: LocalDate): Flow<List<HabitToday>> {
        val week = HabitEngine.weekStart(day)
        return combine(dao.habits(), dao.allLogsBetween(week.toEpochDay(), day.toEpochDay())) { habits, logs ->
            val byHabit = logs.groupBy { it.habitId }
            habits.filter { HabitEngine.isScheduled(it.rule, day) && !LocalDate.ofEpochDay(it.createdDay).isAfter(day) }.map { h ->
                val mine = byHabit[h.id].orEmpty()
                HabitToday(h, mine.firstOrNull { it.day == day.toEpochDay() }, mine.count { it.status == "DONE" })
            }.filter { it.visible }
        }
    }

    suspend fun save(h: HabitEntity, today: LocalDate = LocalDate.now()): Long =
        if (h.id == 0L) {
            dao.insertHabit(h.copy(createdDay = today.toEpochDay(), closedThrough = today.toEpochDay() - 1, sort = (dao.habitsNow().maxOfOrNull { it.sort } ?: 0) + 1))
        } else {
            h.id.also { dao.updateHabit(h) }
        }

    suspend fun archive(id: Long) = dao.habit(id)?.let { dao.updateHabit(it.copy(archived = true)) }

    // ------------------------------------------------------------------ today's taps

    /** Có/không: toggles done. */
    suspend fun toggle(habitId: Long, today: LocalDate = LocalDate.now()): HabitResult = db.withTransaction {
        val h = dao.habit(habitId) ?: return@withTransaction HabitResult()
        val log = logOf(h, today)
        if (log.status == "DONE") undone(h, log.copy(value = 0), today) else done(h, log.copy(value = maxOf(1, h.target)), today)
    }

    /** Marks a habit done if it is not yet (a notification's "Xong": never undoes). */
    suspend fun complete(habitId: Long, today: LocalDate = LocalDate.now()): HabitResult = db.withTransaction {
        val h = dao.habit(habitId) ?: return@withTransaction HabitResult()
        val log = logOf(h, today)
        if (log.status == "DONE") HabitResult() else done(h, log.copy(value = maxOf(log.value, h.target)), today)
    }

    /** Today's due habits, read once (reminders, evening message). */
    suspend fun todayNow(today: LocalDate = LocalDate.now()) = dueToday(today)

    /** Đếm số lượng: +[delta] (may be negative) or exactly [value]. */
    suspend fun count(habitId: Long, delta: Int = 1, value: Int? = null, today: LocalDate = LocalDate.now()): HabitResult = db.withTransaction {
        val h = dao.habit(habitId) ?: return@withTransaction HabitResult()
        val log = logOf(h, today)
        setValue(h, log, (value ?: (log.value + delta)).coerceAtLeast(0), today)
    }

    /** Hẹn giờ: focused minutes from a Pomodoro block. */
    suspend fun addMinutes(habitId: Long, minutes: Int, day: LocalDate = LocalDate.now()): HabitResult = db.withTransaction {
        val h = dao.habit(habitId) ?: return@withTransaction HabitResult()
        if (day != LocalDate.now()) return@withTransaction HabitResult()
        val log = logOf(h, day)
        setValue(h, log, log.value + minutes, day)
    }

    /** Bỏ thói quen: "Lỡ rồi" (or take it back). */
    suspend fun setFailed(habitId: Long, failed: Boolean, today: LocalDate = LocalDate.now()) = db.withTransaction {
        val h = dao.habit(habitId) ?: return@withTransaction
        val log = logOf(h, today)
        dao.updateLog(log.copy(status = if (failed) "FAILED" else "OPEN"))
        if (failed) dao.deleteCoinsOn(RewardRules.R_HABITS_ALL, today.toEpochDay())
    }

    private suspend fun setValue(h: HabitEntity, log: HabitLogEntity, value: Int, today: LocalDate): HabitResult {
        val reached = value >= h.target
        return when {
            reached && log.status != "DONE" -> done(h, log.copy(value = value), today)
            !reached && log.status == "DONE" -> undone(h, log.copy(value = value), today)
            else -> {
                dao.updateLog(log.copy(value = value))
                HabitResult()
            }
        }
    }

    private suspend fun logOf(h: HabitEntity, day: LocalDate): HabitLogEntity =
        dao.log(h.id, day.toEpochDay()) ?: HabitLogEntity(habitId = h.id, day = day.toEpochDay()).let { it.copy(id = dao.insertLog(it)) }

    private suspend fun done(h: HabitEntity, log: HabitLogEntity, today: LocalDate): HabitResult {
        var coins = RewardRules.habitCoins(h.difficulty)
        coinsDao.insertCoins(listOf(CoinEntryEntity(delta = coins, reason = RewardRules.R_HABIT, refId = log.id, day = today.toEpochDay())))
        val notes = mutableListOf<String>()
        var moved = 0L
        var txnId: Long? = null
        if (h.hasMoney) {
            val r = moveMoney(h, today)
            if (r == null) {
                notes += "Lọ không đủ tiền nên hôm nay chưa chuyển ${formatVndShort(h.moneyAmount)} được."
            } else {
                txnId = r.txnId
                moved = h.moneyAmount
                coins += r.coins
                notes += r.notes
            }
        }
        dao.updateLog(log.copy(status = "DONE", txnId = txnId))
        val allDone = allDoneBonus(today)
        if (allDone) coins += RewardRules.ALL_HABITS_DONE
        return HabitResult(coins, notes, allDone, moved)
    }

    private suspend fun undone(h: HabitEntity, log: HabitLogEntity, today: LocalDate): HabitResult {
        dao.deleteCoins(RewardRules.R_HABIT, log.id)
        log.txnId?.let { money.deleteTxn(it) }
        dao.updateLog(log.copy(status = "OPEN", txnId = null))
        dao.deleteCoinsOn(RewardRules.R_HABITS_ALL, today.toEpochDay())
        return HabitResult()
    }

    /** +10 once a day when every due habit (at least one that is not a quit habit) is done. */
    private suspend fun allDoneBonus(today: LocalDate): Boolean {
        val day = today.toEpochDay()
        if (dao.countCoinsOn(RewardRules.R_HABITS_ALL, day) > 0) return false
        val items = dueToday(today)
        if (items.none { it.habit.kind != HabitType.QUIT } || !items.all { it.done }) return false
        coinsDao.insertCoins(listOf(CoinEntryEntity(delta = RewardRules.ALL_HABITS_DONE, reason = RewardRules.R_HABITS_ALL, day = day)))
        return true
    }

    private suspend fun dueToday(today: LocalDate): List<HabitToday> {
        val week = HabitEngine.weekStart(today).toEpochDay()
        return dao.habitsNow().filter { HabitEngine.isScheduled(it.rule, today) && it.createdDay <= today.toEpochDay() }.map { h ->
            val logs = dao.logsBetween(h.id, week, today.toEpochDay())
            HabitToday(h, logs.firstOrNull { it.day == today.toEpochDay() }, logs.count { it.status == "DONE" })
        }.filter { it.visible }
    }

    private suspend fun moveMoney(h: HabitEntity, day: LocalDate): SaveResult? =
        money.habitMove(h.name, h.moneyAmount, h.moneyFromJarId!!, h.moneyToJarId, h.moneyToGoalId, day)

    // ------------------------------------------------------------------ closing past days

    /**
     * Judges every day (or week) that ended since the last close, up to [yesterday]: quit
     * habits that were not marked "Lỡ rồi" are kept (coins + money), others are kept when done.
     * Safe to call often: each period is closed once.
     */
    suspend fun closeThrough(yesterday: LocalDate = LocalDate.now().minusDays(1)): CloseSummary = closing.withLock {
        db.withTransaction {
            val kept = mutableListOf<String>()
            val rested = mutableListOf<String>()
            val missed = mutableListOf<String>()
            val notes = mutableListOf<String>()
            var coins = 0
            for (h in dao.habitsNow()) {
                val periods = HabitEngine.periodsToClose(h.rule, LocalDate.ofEpochDay(h.createdDay), LocalDate.ofEpochDay(h.closedThrough), yesterday)
                if (periods.isEmpty()) {
                    if (h.closedThrough < yesterday.toEpochDay()) dao.updateHabit(h.copy(closedThrough = yesterday.toEpochDay()))
                    continue
                }
                var p = HabitProgress(h.strength, h.streak, h.bestStreak, h.tickets)
                val weekly = h.rule.schedule == Schedule.WEEKLY
                for (period in periods) {
                    val logs = dao.logsBetween(h.id, period.start.toEpochDay(), period.end.toEpochDay())
                    val held = when {
                        weekly -> logs.count { it.status == "DONE" } >= h.timesPerWeek
                        h.kind == HabitType.QUIT -> logs.none { it.status == "FAILED" }
                        else -> logs.any { it.status == "DONE" }
                    }
                    val (next, outcome) = HabitEngine.close(p, held, weekly)
                    if (!weekly) {
                        val day = period.start
                        val log = dao.log(h.id, day.toEpochDay()) ?: HabitLogEntity(habitId = h.id, day = day.toEpochDay()).let { it.copy(id = dao.insertLog(it)) }
                        when (outcome) {
                            Outcome.KEPT -> if (h.kind == HabitType.QUIT) {
                                val c = RewardRules.habitCoins(h.difficulty)
                                coinsDao.insertCoins(listOf(CoinEntryEntity(delta = c, reason = RewardRules.R_HABIT, refId = log.id, day = yesterday.toEpochDay() + 1)))
                                coins += c
                                var txnId: Long? = null
                                if (h.hasMoney) {
                                    val r = moveMoney(h, day)
                                    if (r != null) {
                                        txnId = r.txnId
                                        coins += r.coins
                                        notes += "${h.name}: chuyển ${formatVndShort(h.moneyAmount)} rồi."
                                        notes += r.notes
                                    } else {
                                        notes += "${h.name}: lọ không đủ nên chưa chuyển ${formatVndShort(h.moneyAmount)}."
                                    }
                                }
                                dao.updateLog(log.copy(status = "KEPT", txnId = txnId))
                            }
                            Outcome.RESTED -> dao.updateLog(log.copy(status = "RESTED"))
                            Outcome.MISSED -> if (log.status != "FAILED") dao.updateLog(log.copy(status = "MISSED"))
                        }
                    }
                    when (outcome) {
                        Outcome.KEPT -> if (h.kind == HabitType.QUIT || weekly) kept += h.name
                        Outcome.RESTED -> rested += h.name
                        Outcome.MISSED -> missed += h.name
                    }
                    if (outcome == Outcome.KEPT) {
                        RewardRules.streakMilestone(next.streak)?.let { c ->
                            coinsDao.insertCoins(listOf(CoinEntryEntity(delta = c, reason = RewardRules.R_HABIT_STREAK, refId = h.id, day = yesterday.toEpochDay() + 1)))
                            coins += c
                            notes += "${h.name}: chuỗi ${next.streak} lần! +$c xu"
                        }
                    }
                    p = next
                }
                dao.updateHabit(
                    h.copy(strength = p.strength, streak = p.streak, bestStreak = p.best, tickets = p.tickets, closedThrough = yesterday.toEpochDay()),
                )
            }
            CloseSummary(kept.distinct(), rested.distinct(), missed.distinct(), coins, notes)
        }
    }
}
