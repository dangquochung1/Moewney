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
    /** MANUAL / TEMPLATE / ONBOARDING */
    val source: String = "MANUAL",
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
