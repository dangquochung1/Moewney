package app.meowney.core.data

import androidx.room.withTransaction
import app.meowney.core.db.CategoryEntity
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.DebtEntity
import app.meowney.core.db.RecurringEntity
import app.meowney.core.db.DefaultData
import app.meowney.core.db.GoalEntity
import app.meowney.core.db.GoalEntryEntity
import app.meowney.core.db.GoalRow
import app.meowney.core.db.JarEntity
import app.meowney.core.db.JarEntryEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.QuickTemplateEntity
import app.meowney.core.db.TxnEntity
import app.meowney.core.db.WalletEntity
import app.meowney.core.game.Reward
import app.meowney.core.game.RewardRules
import app.meowney.core.money.BorrowPlan
import app.meowney.core.money.EntryDraft
import app.meowney.core.money.EntryKind
import app.meowney.core.money.GoalEngine
import app.meowney.core.money.RecurrenceEngine
import app.meowney.core.money.Repeat
import app.meowney.core.money.JarCode
import app.meowney.core.money.JarInfo
import app.meowney.core.money.Loan
import app.meowney.core.money.LoanRow
import app.meowney.core.money.MoneyEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class JarState(val jar: JarEntity, val balance: Long) {
    val code get() = JarCode.valueOf(jar.code)
}

data class WalletState(val wallet: WalletEntity, val balance: Long)

data class WalletDraft(val name: String, val kind: String, val balance: Long)

/** Fields of an expense / income / transfer as the entry screen fills them. */
data class TxnDraft(
    val amount: Long,
    val walletId: Long,
    val toWalletId: Long? = null,
    val categoryId: Long? = null,
    val jarId: Long? = null,
    val note: String = "",
    val date: LocalDate,
    val source: String = "MANUAL",
)

/** What a save earned, so the UI can show it. [notes] are celebrations (goal milestones). */
data class SaveResult(val txnId: Long, val coins: Int, val notes: List<String> = emptyList())

/**
 * All money writes. Each operation is one database transaction, so the ledger is never
 * half-written: Σ wallets == Σ jars holds after every call.
 */
class MoneyRepository(private val db: MeowneyDb) {
    private val dao = db.money()
    private val plans = db.plans()

    // ------------------------------------------------------------------ reads

    val wallets = dao.wallets()
    val categories = dao.categories()
    val coins: Flow<Long> = dao.coinTotal()
    val templates = dao.templates()

    fun recentTxns(limit: Int = 300) = dao.recentTxns(limit)

    val jarStates: Flow<List<JarState>> = combine(dao.jars(), dao.jarBalances()) { jars, sums ->
        val byId = sums.associate { it.id to it.total }
        jars.map { JarState(it, byId[it.id] ?: 0) }
    }

    val walletStates: Flow<List<WalletState>> = combine(dao.wallets(), dao.walletBalances()) { ws, sums ->
        val byId = sums.associate { it.id to it.total }
        ws.map { WalletState(it, byId[it.id] ?: 0) }
    }

    val loans: Flow<List<Loan>> = dao.loanSums().map { rows -> MoneyEngine.loans(rows.map { it.toRow() }) }

    fun spentOn(day: LocalDate, jarIds: List<Long>) = dao.spentOn(day.toEpochDay(), jarIds)
    fun countOn(day: LocalDate) = dao.countOn(day.toEpochDay())
    fun allocatedSince(day: LocalDate, jarIds: List<Long>) = dao.allocatedSince(day.toEpochDay(), jarIds)
    fun noSpendClaims(day: LocalDate) = dao.countCoinsOn(day.toEpochDay(), RewardRules.R_NO_SPEND)
    fun entriesOfJar(jarId: Long) = dao.entriesOfJar(jarId)
    fun frequentExpenses(since: LocalDate, limit: Int) = dao.frequentExpenses(since.toEpochDay(), limit)

    suspend fun txn(id: Long) = dao.txn(id)
    suspend fun entriesOf(txnId: Long) = dao.entriesOf(txnId)
    suspend fun jarsNow() = dao.jarsNow()
    suspend fun categoriesNow() = dao.categoriesNow()
    suspend fun walletsNow() = dao.walletsNow()

    suspend fun defaultWalletId(): Long? = dao.walletsNow().let { ws ->
        ws.firstOrNull { it.isDefault && !it.archived }?.id ?: ws.firstOrNull { !it.archived }?.id
    }
    suspend fun jarInfos() = dao.jarsNow().map { JarInfo(it.id, JarCode.valueOf(it.code), it.percent) }

    suspend fun balancesNow(excludingTxn: Long? = null): Map<Long, Long> =
        (if (excludingTxn == null) dao.jarBalancesNow() else dao.jarBalancesExcluding(excludingTxn))
            .associate { it.id to it.total }

    suspend fun loansNow(excludingTxn: Long? = null): List<Loan> = MoneyEngine.loans(
        (if (excludingTxn == null) dao.loanSumsNow() else dao.loanSumsExcluding(excludingTxn)).map { it.toRow() },
    )

    // ------------------------------------------------------------------ onboarding

    /** First setup: jars, categories, wallets with their opening balances poured into the jars. */
    suspend fun onboard(
        wallets: List<WalletDraft>,
        defaultWallet: Int,
        jarPercents: Map<JarCode, Int>,
        openingShares: Map<JarCode, Long>,
        today: LocalDate,
    ) = db.withTransaction {
        if (dao.jarsNow().isNotEmpty()) return@withTransaction
        val jarIds = dao.insertJars(
            DefaultData.jars.mapIndexed { i, s ->
                JarEntity(code = s.code.name, name = s.name, percent = jarPercents[s.code] ?: s.percent, isSavings = s.code.isSavings, sort = i)
            },
        )
        val jarByCode = DefaultData.jars.mapIndexed { i, s -> s.code to jarIds[i] }.toMap()
        dao.insertCategories(
            DefaultData.expenseCategories.mapIndexed { i, c ->
                CategoryEntity(name = c.name, type = "EXPENSE", icon = c.icon, defaultJarId = c.jar?.let { jarByCode[it] }, sort = i)
            } + DefaultData.incomeCategories.mapIndexed { i, c ->
                CategoryEntity(name = c.name, type = "INCOME", icon = c.icon, defaultJarId = null, sort = i)
            },
        )
        val now = System.currentTimeMillis()
        wallets.forEachIndexed { i, w ->
            val id = dao.insertWallet(WalletEntity(name = w.name, kind = w.kind, isDefault = i == defaultWallet, sort = i))
            if (w.balance != 0L) {
                dao.insertTxn(
                    TxnEntity(type = "OPENING", amount = w.balance, walletId = id, occurredAt = now, day = today.toEpochDay(), createdAt = now, source = "ONBOARDING"),
                )
            }
        }
        val total = wallets.sumOf { it.balance }
        val shares = openingShares.mapKeys { jarByCode.getValue(it.key) }.filterValues { it != 0L }
        val rest = total - shares.values.sum()
        val entries = shares.map { (jar, v) -> JarEntryEntity(jarId = jar, amount = v, kind = EntryKind.OPENING.name, day = today.toEpochDay()) } +
            if (rest != 0L) listOf(JarEntryEntity(jarId = jarByCode.getValue(JarCode.UNALLOC), amount = rest, kind = EntryKind.OPENING.name, day = today.toEpochDay())) else emptyList()
        dao.insertEntries(entries)
        dao.insertCoins(listOf(RewardRules.onWelcome().toEntity(today, null)))
    }

    // ------------------------------------------------------------------ writes

    suspend fun addExpense(d: TxnDraft, plan: BorrowPlan, editing: Long? = null): SaveResult =
        save(d.copy(), "EXPENSE", editing) { txnId ->
            MoneyEngine.expenseEntries(d.amount, d.jarId!!, plan)
        }

    suspend fun addIncome(d: TxnDraft, entries: List<EntryDraft>, editing: Long? = null): SaveResult =
        save(d, "INCOME", editing, extraReward = if (editing == null) RewardRules.onIncomePoured() else null) { entries }

    suspend fun transfer(d: TxnDraft, editing: Long? = null): SaveResult =
        save(d, "TRANSFER", editing) { emptyList() }

    private suspend fun save(
        d: TxnDraft,
        type: String,
        editing: Long?,
        extraReward: Reward? = null,
        entries: (Long) -> List<EntryDraft>,
    ): SaveResult = db.withTransaction {
        val nowMs = System.currentTimeMillis()
        val occurredAt = occurredAt(d.date)
        val base = TxnEntity(
            type = type, amount = d.amount, walletId = d.walletId, toWalletId = d.toWalletId, categoryId = d.categoryId,
            jarId = d.jarId, note = d.note.trim(), occurredAt = occurredAt, day = d.date.toEpochDay(), createdAt = nowMs, source = d.source,
        )
        val id = if (editing != null) {
            val old = dao.txn(editing) ?: error("transaction $editing is gone")
            dao.deleteEntriesOf(editing)
            // keep the original time unless the day was changed
            val at = if (old.day == base.day) old.occurredAt else base.occurredAt
            dao.updateTxn(base.copy(id = editing, createdAt = old.createdAt, occurredAt = at, source = old.source))
            editing
        } else {
            dao.insertTxn(base)
        }
        val written = entries(id)
        dao.insertEntries(written.map { it.toEntity(id, d.date) })
        val notes = mutableListOf<String>()
        var coins = 0
        if (type == "INCOME") {
            plans.deleteGoalEntriesOf(id)
            coins += fillGoalsFromIncome(id, written, d.date, notes)
        }
        if (editing == null) {
            val today = LocalDate.now()
            val day = today.toEpochDay()
            val first = dao.countOnNow(day) == 1 && d.date == today
            val rewards = RewardRules.onLog(dao.coinsOn(day, RewardRules.R_LOG), first) + listOfNotNull(extraReward)
            dao.insertCoins(rewards.map { it.toEntity(today, id) })
            coins += rewards.sumOf { it.delta }
        }
        SaveResult(id, coins, notes)
    }

    // ------------------------------------------------------------------ recurring bills

    val recurring = plans.recurring()

    suspend fun recurringNow() = plans.recurringNow()

    suspend fun saveRecurring(r: RecurringEntity): Long = if (r.id == 0L) plans.insertRecurring(r) else r.id.also { plans.updateRecurring(r) }

    suspend fun deleteRecurring(id: Long) = plans.deleteRecurring(id)

    /** Moves a bill to its next due date (after paying or skipping this one). */
    suspend fun advanceRecurring(id: Long) {
        val r = plans.recurringById(id) ?: return
        val next = RecurrenceEngine.after(Repeat.valueOf(r.rule), r.ruleDay, r.ruleMonth, LocalDate.ofEpochDay(r.nextDueDay))
        plans.updateRecurring(r.copy(nextDueDay = next.toEpochDay()))
    }

    suspend fun debtsDue(day: LocalDate) = plans.debtsDue(day.toEpochDay())

    // ------------------------------------------------------------------ debts

    val debts = plans.debts()

    /**
     * Lending ([lent] = true) takes money out of a wallet and a jar; borrowing puts it in.
     * Neither is an expense or income.
     */
    suspend fun createDebt(
        person: String, lent: Boolean, amount: Long, walletId: Long, jarId: Long,
        dueDay: Long?, note: String, today: LocalDate = LocalDate.now(),
    ): Long = db.withTransaction {
        val debtId = plans.insertDebt(
            DebtEntity(person = person, direction = if (lent) "LENT" else "BORROWED", principal = amount, walletId = walletId, jarId = jarId, day = today.toEpochDay(), dueDay = dueDay, note = note),
        )
        debtTxn(debtId, if (lent) "DEBT_OUT" else "DEBT_IN", amount, walletId, jarId, "DEBT", note, today)
        debtId
    }

    /** A repayment: they pay Sen back (LENT) or Sen pays them (BORROWED). Settles the debt when done. */
    suspend fun payDebt(debtId: Long, amount: Long, walletId: Long, today: LocalDate = LocalDate.now()) = db.withTransaction {
        val d = plans.debt(debtId) ?: return@withTransaction
        val lent = d.direction == "LENT"
        debtTxn(debtId, if (lent) "DEBT_IN" else "DEBT_OUT", amount, walletId, d.jarId, "DEBT_PAY", "", today)
        if (d.paid + amount >= d.principal) {
            plans.debt(debtId)?.let { row ->
                plans.updateDebt(DebtEntity(row.id, row.person, row.direction, row.principal, row.walletId, row.jarId, row.day, row.dueDay, row.note, today.toEpochDay()))
            }
        }
    }

    suspend fun deleteDebt(debtId: Long) = db.withTransaction {
        for (t in plans.txnIdsOfDebt(debtId)) {
            dao.deleteEntriesOf(t)
            dao.deleteTxn(t)
        }
        plans.deleteDebt(debtId)
    }

    private suspend fun debtTxn(debtId: Long, type: String, amount: Long, walletId: Long, jarId: Long, source: String, note: String, today: LocalDate) {
        val now = System.currentTimeMillis()
        val id = dao.insertTxn(
            TxnEntity(type = type, amount = amount, walletId = walletId, jarId = jarId, note = note, occurredAt = now, day = today.toEpochDay(), createdAt = now, source = source, debtId = debtId),
        )
        val signed = if (type == "DEBT_IN") amount else -amount
        dao.insertEntries(listOf(JarEntryEntity(jarId = jarId, amount = signed, kind = EntryKind.DEBT.name, txnId = id, day = today.toEpochDay())))
    }

    // ------------------------------------------------------------------ goals

    val goals = plans.goals()
    fun goalEntries(goalId: Long) = plans.goalEntries(goalId)
    val goalTotalsByJar: Flow<Map<Long, Long>> = plans.goalTotalsByJarFlow().map { r -> r.associate { it.id to it.total } }

    suspend fun goalTotalsByJarNow(): Map<Long, Long> = plans.goalTotalsByJar().associate { it.id to it.total }

    suspend fun saveGoal(g: GoalEntity): Long = if (g.id == 0L) plans.insertGoal(g) else g.id.also { plans.updateGoal(g) }

    suspend fun goal(id: Long) = plans.goal(id)

    suspend fun archiveGoal(id: Long) = plans.goal(id)?.let { plans.updateGoal(it.copy(archived = true)) }

    /** Jar money not held by any goal. */
    suspend fun freeInJar(jarId: Long): Long = (balancesNow()[jarId] ?: 0) - (goalTotalsByJarNow()[jarId] ?: 0)

    /** "Nạp thêm" (amount > 0) or "Rút" (amount < 0). Notes are milestone celebrations. */
    suspend fun fundGoal(goalId: Long, amount: Long, today: LocalDate = LocalDate.now()): SaveResult = db.withTransaction {
        val goal = plans.goalsNow().first { it.id == goalId }
        plans.insertGoalEntries(
            listOf(GoalEntryEntity(goalId = goalId, amount = amount, kind = if (amount >= 0) "MANUAL" else "WITHDRAW", day = today.toEpochDay())),
        )
        val notes = mutableListOf<String>()
        val coins = rewardMilestones(goal, goal.saved, goal.saved + amount, today, notes)
        SaveResult(0, coins, notes)
    }

    /** "Mua rồi!": spend the goal's money from its jar and close the goal. */
    suspend fun buyGoal(goalId: Long, walletId: Long, categoryId: Long?, today: LocalDate = LocalDate.now()): SaveResult = db.withTransaction {
        val goal = plans.goalsNow().first { it.id == goalId }
        val now = System.currentTimeMillis()
        val id = dao.insertTxn(
            TxnEntity(
                type = "EXPENSE", amount = goal.saved, walletId = walletId, categoryId = categoryId, jarId = goal.jarId,
                note = goal.name, occurredAt = now, day = today.toEpochDay(), createdAt = now, source = "GOAL",
            ),
        )
        dao.insertEntries(listOf(JarEntryEntity(jarId = goal.jarId, amount = -goal.saved, kind = EntryKind.SPEND.name, txnId = id, day = today.toEpochDay())))
        plans.insertGoalEntries(listOf(GoalEntryEntity(goalId = goalId, amount = -goal.saved, kind = "WITHDRAW", txnId = id, day = today.toEpochDay())))
        plans.goal(goalId)?.let { plans.updateGoal(it.copy(achievedDay = today.toEpochDay(), archived = true)) }
        SaveResult(id, 0, listOf("Sen mua được ${goal.name} rồi! Trẫm tự hào ghê."))
    }

    /** Splits an income's share of each savings jar into that jar's open goals. */
    private suspend fun fillGoalsFromIncome(txnId: Long, entries: List<EntryDraft>, date: LocalDate, notes: MutableList<String>): Int {
        val open = plans.goalsNow().filter { it.achievedDay == null && it.autoPercent > 0 }
        if (open.isEmpty()) return 0
        var coins = 0
        for ((jarId, goals) in open.groupBy { it.jarId }) {
            val share = entries.filter { it.jarId == jarId }.sumOf { it.amount }
            val parts = GoalEngine.autoShares(share, goals.map { it.id to it.autoPercent })
            if (parts.isEmpty()) continue
            plans.insertGoalEntries(
                parts.map { (g, v) -> GoalEntryEntity(goalId = g, amount = v, kind = "AUTO", txnId = txnId, day = date.toEpochDay()) },
            )
            for (g in goals) coins += rewardMilestones(g, g.saved, g.saved + (parts[g.id] ?: 0), LocalDate.now(), notes)
        }
        return coins
    }

    private suspend fun rewardMilestones(goal: GoalRow, before: Long, after: Long, today: LocalDate, notes: MutableList<String>): Int {
        var coins = 0
        for (m in GoalEngine.crossed(goal.target, before, after)) {
            val reason = "goal_${goal.id}_$m"
            if (dao.countReason(reason) > 0) continue
            val c = GoalEngine.MILESTONE_COINS.getValue(m)
            dao.insertCoins(listOf(CoinEntryEntity(delta = c, reason = reason, refId = goal.id, day = today.toEpochDay())))
            coins += c
            notes += if (m == 100) "Mục tiêu ${goal.name} đủ tiền rồi! Sen giỏi quá. +$c xu" else "${goal.name} đạt $m%! +$c xu"
        }
        return coins
    }

    suspend fun deleteTxn(id: Long) = db.withTransaction {
        dao.deleteEntriesOf(id)
        plans.deleteGoalEntriesOf(id)
        dao.deleteTxn(id)
    }

    /** Moves money between jars by hand (no wallet changes). */
    suspend fun moveBetweenJars(from: Long, to: Long, amount: Long, day: LocalDate = LocalDate.now()) = db.withTransaction {
        dao.insertEntries(MoneyEngine.moveEntries(from, to, amount).map { it.toEntity(null, day) })
    }

    /** Sets a wallet to its real balance; the difference goes to "Chưa phân bổ". */
    suspend fun adjustWallet(walletId: Long, currentBalance: Long, realBalance: Long, today: LocalDate = LocalDate.now()) = db.withTransaction {
        val diff = realBalance - currentBalance
        if (diff == 0L) return@withTransaction
        val now = System.currentTimeMillis()
        val id = dao.insertTxn(TxnEntity(type = "ADJUST", amount = diff, walletId = walletId, note = "Điều chỉnh số dư", occurredAt = now, day = today.toEpochDay(), createdAt = now))
        val unalloc = dao.jarsNow().first { it.code == JarCode.UNALLOC.name }.id
        dao.insertEntries(listOf(JarEntryEntity(jarId = unalloc, amount = diff, kind = EntryKind.ADJUST.name, txnId = id, day = today.toEpochDay())))
    }

    suspend fun addWallet(name: String, kind: String) = dao.insertWallet(WalletEntity(name = name, kind = kind, sort = 99))
    suspend fun updateWallet(w: WalletEntity) = dao.updateWallet(w)
    suspend fun setDefaultWallet(id: Long) = dao.setDefaultWallet(id)
    suspend fun updateJar(j: JarEntity) = dao.updateJar(j)

    suspend fun pinTemplate(label: String, amount: Long, categoryId: Long) =
        dao.insertTemplate(QuickTemplateEntity(label = label, amount = amount, categoryId = categoryId))

    suspend fun unpinTemplate(id: Long) = dao.deleteTemplate(id)

    /** "Hôm nay Sen không tiêu gì": returns the coins earned (0 if not allowed). */
    suspend fun claimNoSpend(today: LocalDate, spentToday: Long): Int = db.withTransaction {
        val claimed = dao.coinsOn(today.toEpochDay(), RewardRules.R_NO_SPEND) > 0
        val reward = RewardRules.onNoSpend(claimed, spentToday) ?: return@withTransaction 0
        dao.insertCoins(listOf(reward.toEntity(today, null)))
        reward.delta
    }

    suspend fun exportRows() = dao.allTxnRows()

    // ------------------------------------------------------------------ helpers

    private fun occurredAt(date: LocalDate): Long {
        val now = LocalDateTime.now()
        val time = if (date == now.toLocalDate()) now.toLocalTime() else LocalTime.NOON
        return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun EntryDraft.toEntity(txnId: Long?, date: LocalDate) =
        JarEntryEntity(jarId = jarId, amount = amount, kind = kind.name, peerJarId = peerJarId, txnId = txnId, day = date.toEpochDay())

    private fun Reward.toEntity(day: LocalDate, ref: Long?) = CoinEntryEntity(delta = delta, reason = reason, refId = ref, day = day.toEpochDay())

    private fun app.meowney.core.db.LoanSum.toRow() = LoanRow(jarId, peerJarId, EntryKind.valueOf(kind), total)
}
