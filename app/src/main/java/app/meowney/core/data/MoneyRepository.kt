package app.meowney.core.data

import androidx.room.withTransaction
import app.meowney.core.db.CategoryEntity
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.DefaultData
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

/** What a save earned, so the UI can show it. */
data class SaveResult(val txnId: Long, val coins: Int)

/**
 * All money writes. Each operation is one database transaction, so the ledger is never
 * half-written: Σ wallets == Σ jars holds after every call.
 */
class MoneyRepository(private val db: MeowneyDb) {
    private val dao = db.money()

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
        dao.insertEntries(entries(id).map { it.toEntity(id, d.date) })
        var coins = 0
        if (editing == null) {
            val today = LocalDate.now()
            val day = today.toEpochDay()
            val first = dao.countOnNow(day) == 1 && d.date == today
            val rewards = RewardRules.onLog(dao.coinsOn(day, RewardRules.R_LOG), first) + listOfNotNull(extraReward)
            dao.insertCoins(rewards.map { it.toEntity(today, id) })
            coins = rewards.sumOf { it.delta }
        }
        SaveResult(id, coins)
    }

    suspend fun deleteTxn(id: Long) = db.withTransaction {
        dao.deleteEntriesOf(id)
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
