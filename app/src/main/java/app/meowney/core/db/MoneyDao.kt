package app.meowney.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class IdSum(val id: Long, val total: Long)

data class LoanSum(val jarId: Long, val peerJarId: Long, val kind: String, val total: Long)

data class CategoryAmountCount(val categoryId: Long, val amount: Long, val n: Int)

/** A transaction with the names the lists show. */
data class TxnRow(
    val id: Long,
    val type: String,
    val amount: Long,
    val walletId: Long,
    val toWalletId: Long?,
    val categoryId: Long?,
    val jarId: Long?,
    val note: String,
    val occurredAt: Long,
    val day: Long,
    val categoryName: String?,
    val categoryIcon: Int?,
    val walletName: String?,
    val toWalletName: String?,
    val jarName: String?,
    val jarCode: String?,
    val debtPerson: String? = null,
    val source: String = "MANUAL",
)

@Dao
interface MoneyDao {
    // ------------------------------------------------------------------ setup lists

    @Query("SELECT * FROM wallet ORDER BY archived, sort, id")
    fun wallets(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallet ORDER BY archived, sort, id")
    suspend fun walletsNow(): List<WalletEntity>

    @Query("SELECT * FROM jar ORDER BY sort")
    fun jars(): Flow<List<JarEntity>>

    @Query("SELECT * FROM jar ORDER BY sort")
    suspend fun jarsNow(): List<JarEntity>

    @Query("SELECT * FROM category WHERE archived = 0 ORDER BY type, sort")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category ORDER BY type, sort")
    suspend fun categoriesNow(): List<CategoryEntity>

    @Insert suspend fun insertWallet(w: WalletEntity): Long
    @Update suspend fun updateWallet(w: WalletEntity)
    @Insert suspend fun insertJars(j: List<JarEntity>): List<Long>
    @Update suspend fun updateJar(j: JarEntity)
    @Insert suspend fun insertCategories(c: List<CategoryEntity>): List<Long>

    @Query("UPDATE wallet SET isDefault = (id = :id)")
    suspend fun setDefaultWallet(id: Long)

    // ------------------------------------------------------------------ transactions

    @Insert suspend fun insertTxn(t: TxnEntity): Long
    @Update suspend fun updateTxn(t: TxnEntity)
    @Insert suspend fun insertEntries(e: List<JarEntryEntity>)

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun txn(id: Long): TxnEntity?

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun deleteTxn(id: Long)

    @Query("DELETE FROM jar_entry WHERE txnId = :txnId")
    suspend fun deleteEntriesOf(txnId: Long)

    @Query(
        """
        SELECT t.id, t.type, t.amount, t.walletId, t.toWalletId, t.categoryId, t.jarId, t.note, t.occurredAt, t.day,
               c.name AS categoryName, c.icon AS categoryIcon, w.name AS walletName, w2.name AS toWalletName,
               j.name AS jarName, j.code AS jarCode, d.person AS debtPerson, t.source AS source
        FROM txn t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN wallet w ON w.id = t.walletId
        LEFT JOIN wallet w2 ON w2.id = t.toWalletId
        LEFT JOIN jar j ON j.id = t.jarId
        LEFT JOIN debt d ON d.id = t.debtId
        WHERE t.type != 'OPENING'
        ORDER BY t.occurredAt DESC, t.id DESC
        LIMIT :limit
        """,
    )
    fun recentTxns(limit: Int): Flow<List<TxnRow>>

    @Query(
        """
        SELECT t.id, t.type, t.amount, t.walletId, t.toWalletId, t.categoryId, t.jarId, t.note, t.occurredAt, t.day,
               c.name AS categoryName, c.icon AS categoryIcon, w.name AS walletName, w2.name AS toWalletName,
               j.name AS jarName, j.code AS jarCode, d.person AS debtPerson, t.source AS source
        FROM txn t
        LEFT JOIN category c ON c.id = t.categoryId
        LEFT JOIN wallet w ON w.id = t.walletId
        LEFT JOIN wallet w2 ON w2.id = t.toWalletId
        LEFT JOIN jar j ON j.id = t.jarId
        LEFT JOIN debt d ON d.id = t.debtId
        ORDER BY t.occurredAt, t.id
        """,
    )
    suspend fun allTxnRows(): List<TxnRow>

    // ------------------------------------------------------------------ balances

    @Query("SELECT jarId AS id, COALESCE(SUM(amount), 0) AS total FROM jar_entry GROUP BY jarId")
    fun jarBalances(): Flow<List<IdSum>>

    @Query("SELECT jarId AS id, COALESCE(SUM(amount), 0) AS total FROM jar_entry GROUP BY jarId")
    suspend fun jarBalancesNow(): List<IdSum>

    /** Jar balances as if transaction [txnId] did not exist (used while editing it). */
    @Query("SELECT jarId AS id, COALESCE(SUM(amount), 0) AS total FROM jar_entry WHERE txnId IS NULL OR txnId != :txnId GROUP BY jarId")
    suspend fun jarBalancesExcluding(txnId: Long): List<IdSum>

    @Query("SELECT * FROM jar_entry WHERE txnId = :txnId")
    suspend fun entriesOf(txnId: Long): List<JarEntryEntity>

    @Query(
        """
        SELECT w.id AS id,
          COALESCE((SELECT SUM(CASE
              WHEN t.type IN ('INCOME', 'OPENING', 'ADJUST', 'DEBT_IN') THEN t.amount
              WHEN t.type IN ('EXPENSE', 'TRANSFER', 'DEBT_OUT') THEN -t.amount
              ELSE 0 END) FROM txn t WHERE t.walletId = w.id), 0)
          + COALESCE((SELECT SUM(t.amount) FROM txn t WHERE t.type = 'TRANSFER' AND t.toWalletId = w.id), 0) AS total
        FROM wallet w
        """,
    )
    fun walletBalances(): Flow<List<IdSum>>

    @Query(
        """
        SELECT jarId, peerJarId, kind, SUM(amount) AS total FROM jar_entry
        WHERE kind IN ('BORROW', 'REPAY') AND amount > 0 AND peerJarId IS NOT NULL
        GROUP BY jarId, peerJarId, kind
        """,
    )
    fun loanSums(): Flow<List<LoanSum>>

    @Query(
        """
        SELECT jarId, peerJarId, kind, SUM(amount) AS total FROM jar_entry
        WHERE kind IN ('BORROW', 'REPAY') AND amount > 0 AND peerJarId IS NOT NULL
        GROUP BY jarId, peerJarId, kind
        """,
    )
    suspend fun loanSumsNow(): List<LoanSum>

    @Query(
        """
        SELECT jarId, peerJarId, kind, SUM(amount) AS total FROM jar_entry
        WHERE kind IN ('BORROW', 'REPAY') AND amount > 0 AND peerJarId IS NOT NULL AND (txnId IS NULL OR txnId != :txnId)
        GROUP BY jarId, peerJarId, kind
        """,
    )
    suspend fun loanSumsExcluding(txnId: Long): List<LoanSum>

    /** What was spent today out of the given jars (positive number). */
    @Query("SELECT COALESCE(-SUM(amount), 0) FROM jar_entry WHERE day = :day AND kind = 'SPEND' AND jarId IN (:jarIds)")
    fun spentOn(day: Long, jarIds: List<Long>): Flow<Long>

    @Query("SELECT COUNT(*) FROM txn WHERE day = :day AND type IN ('EXPENSE', 'INCOME', 'TRANSFER')")
    fun countOn(day: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM txn WHERE day = :day AND type IN ('EXPENSE', 'INCOME', 'TRANSFER')")
    suspend fun countOnNow(day: Long): Int

    /** Allocated into a jar since a day (incomes, opening, repayments) - the "full" level of the coin jar. */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM jar_entry WHERE day >= :since AND amount > 0 AND kind IN ('ALLOC', 'OPENING') AND jarId IN (:jarIds)")
    fun allocatedSince(since: Long, jarIds: List<Long>): Flow<Long>

    @Query("SELECT * FROM jar_entry WHERE jarId = :jarId ORDER BY day DESC, id DESC LIMIT 200")
    fun entriesOfJar(jarId: Long): Flow<List<JarEntryEntity>>

    // ------------------------------------------------------------------ quick templates

    @Query("SELECT * FROM quick_template ORDER BY sort, id")
    fun templates(): Flow<List<QuickTemplateEntity>>

    @Insert suspend fun insertTemplate(t: QuickTemplateEntity): Long

    @Query("DELETE FROM quick_template WHERE id = :id")
    suspend fun deleteTemplate(id: Long)

    @Query(
        """
        SELECT categoryId, amount, COUNT(*) AS n FROM txn
        WHERE type = 'EXPENSE' AND day >= :since AND categoryId IS NOT NULL
        GROUP BY categoryId, amount ORDER BY n DESC, MAX(occurredAt) DESC LIMIT :limit
        """,
    )
    fun frequentExpenses(since: Long, limit: Int): Flow<List<CategoryAmountCount>>

    // ------------------------------------------------------------------ coins

    @Insert suspend fun insertCoins(c: List<CoinEntryEntity>)

    @Query("SELECT COALESCE(SUM(delta), 0) FROM coin_entry")
    fun coinTotal(): Flow<Long>

    @Query("SELECT COALESCE(SUM(delta), 0) FROM coin_entry WHERE day = :day AND reason = :reason")
    suspend fun coinsOn(day: Long, reason: String): Int

    @Query("SELECT COUNT(*) FROM coin_entry WHERE day = :day AND reason = :reason")
    fun countCoinsOn(day: Long, reason: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM coin_entry WHERE reason = :reason")
    suspend fun countReason(reason: String): Int
}
