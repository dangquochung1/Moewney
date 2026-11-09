package app.meowney.core.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where money is: cash, a bank account, an e-wallet. */
@Entity(tableName = "wallet")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** CASH / BANK / EWALLET */
    val kind: String,
    val isDefault: Boolean = false,
    val archived: Boolean = false,
    val sort: Int = 0,
)

/** What money is for: one of the 6 jars or "Chưa phân bổ". */
@Entity(tableName = "jar", indices = [Index(value = ["code"], unique = true)])
data class JarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** [app.meowney.core.money.JarCode] name */
    val code: String,
    val name: String,
    val percent: Int,
    val isSavings: Boolean,
    val sort: Int,
)

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** EXPENSE / INCOME */
    val type: String,
    /** Cell index in assets/icons/raven16.png (16 columns of 16 px). */
    val icon: Int,
    val defaultJarId: Long?,
    val sort: Int,
    val archived: Boolean = false,
)

/**
 * One money movement. Wallet balances are the sum of these:
 * INCOME/OPENING add, EXPENSE subtracts, TRANSFER moves [amount] from [walletId] to
 * [toWalletId], ADJUST adds its signed [amount].
 */
@Entity(tableName = "txn", indices = [Index("day"), Index("walletId")])
data class TxnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val amount: Long,
    val walletId: Long,
    val toWalletId: Long? = null,
    val categoryId: Long? = null,
    /** For expenses: the jar it was paid from. */
    val jarId: Long? = null,
    val note: String = "",
    /** Epoch millis of when it happened (user can back-date). */
    val occurredAt: Long,
    /** LocalDate.toEpochDay() of [occurredAt], for fast daily queries. */
    val day: Long,
    val createdAt: Long,
    /** MANUAL / TEMPLATE / ONBOARDING / RECURRING / DEBT */
    val source: String = "MANUAL",
    /** For DEBT_IN / DEBT_OUT: the debt this payment belongs to. */
    val debtId: Long? = null,
)

/** One row of the jar ledger. Jar balances are the sum of these. */
@Entity(
    tableName = "jar_entry",
    foreignKeys = [ForeignKey(TxnEntity::class, ["id"], ["txnId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("txnId"), Index("jarId"), Index("day")],
)
data class JarEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jarId: Long,
    val amount: Long,
    /** [app.meowney.core.money.EntryKind] name */
    val kind: String,
    val peerJarId: Long? = null,
    val txnId: Long? = null,
    val day: Long,
)

@Entity(tableName = "coin_entry", indices = [Index("day")])
data class CoinEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val delta: Int,
    val reason: String,
    val refId: Long? = null,
    val day: Long,
)

/** A pinned one-tap expense on the home screen. */
@Entity(tableName = "quick_template")
data class QuickTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val amount: Long,
    val categoryId: Long,
    val pinned: Boolean = true,
    val sort: Int = 0,
)

/** A savings goal living inside a savings jar (Tiết kiệm dài hạn / Tự do tài chính). */
@Entity(tableName = "goal")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val jarId: Long,
    val target: Long,
    val deadlineDay: Long? = null,
    /** Share of the jar's part of every income that goes to this goal, 0..100. */
    val autoPercent: Int = 0,
    val createdDay: Long,
    val achievedDay: Long? = null,
    val archived: Boolean = false,
)

/** Money assigned to a goal. Goal balance = Σ amount. Kind AUTO / MANUAL / WITHDRAW. */
@Entity(
    tableName = "goal_entry",
    foreignKeys = [
        ForeignKey(GoalEntity::class, ["id"], ["goalId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TxnEntity::class, ["id"], ["txnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("goalId"), Index("txnId")],
)
data class GoalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val amount: Long,
    val kind: String,
    val txnId: Long? = null,
    val day: Long,
)

/** Money lent to or borrowed from a person. Payments are DEBT_IN / DEBT_OUT transactions. */
@Entity(tableName = "debt")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val person: String,
    /** LENT (they owe Sen) / BORROWED (Sen owes them) */
    val direction: String,
    val principal: Long,
    val walletId: Long,
    val jarId: Long,
    val day: Long,
    val dueDay: Long? = null,
    val note: String = "",
    val settledDay: Long? = null,
)

/** A bill that comes back: rent, electricity, Netflix... */
@Entity(tableName = "recurring")
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amount: Long,
    val categoryId: Long,
    val walletId: Long,
    val jarId: Long? = null,
    /** MONTHLY / WEEKLY / YEARLY */
    val rule: String,
    /** Day of month (1..31), day of week (1 = Monday) or day of the year's month. */
    val ruleDay: Int,
    /** Month for YEARLY (1..12), else 0. */
    val ruleMonth: Int = 0,
    val nextDueDay: Long,
    val active: Boolean = true,
)

/** What "hôm nay còn" was each day, for the monthly report. */
@Entity(tableName = "day_stat")
data class DayStatEntity(
    @PrimaryKey val day: Long,
    val allowanceBase: Long,
    val spent: Long,
    val logged: Boolean,
)

/** The cat's report card for a month, made once and kept. */
@Entity(tableName = "month_report")
data class MonthReportEntity(
    /** year * 100 + month */
    @PrimaryKey val yyyymm: Int,
    val grade: String,
    val score: Float,
    val coins: Int,
    /** JSON of the numbers shown on the card and charts. */
    val data: String,
    val createdDay: Long,
    val claimed: Boolean = false,
)
