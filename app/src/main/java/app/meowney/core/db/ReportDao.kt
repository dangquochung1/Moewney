package app.meowney.core.db

import androidx.room.Dao
import androidx.room.Query

data class DaySum(val day: Long, val total: Long)

/** A total for one month; [ym] is year * 100 + month. */
data class MonthSum(val ym: Int, val total: Long)

/** Read-only numbers for the monthly report card. All ranges are inclusive epoch days. */
@Dao
interface ReportDao {
    @Query("SELECT MIN(day) FROM txn")
    suspend fun firstDay(): Long?

    /** Days with a real entry (not the onboarding opening balances). */
    @Query("SELECT DISTINCT day FROM txn WHERE day BETWEEN :from AND :to AND type IN ('EXPENSE', 'INCOME', 'TRANSFER', 'DEBT_IN', 'DEBT_OUT')")
    suspend fun loggedDays(from: Long, to: Long): List<Long>

    @Query("SELECT DISTINCT day FROM coin_entry WHERE day BETWEEN :from AND :to AND reason = :reason")
    suspend fun coinDays(reason: String, from: Long, to: Long): List<Long>

    @Query("SELECT day, SUM(amount) AS total FROM txn WHERE type = 'EXPENSE' AND day BETWEEN :from AND :to GROUP BY day")
    suspend fun expenseByDay(from: Long, to: Long): List<DaySum>

    @Query("SELECT jarId AS id, -SUM(amount) AS total FROM jar_entry WHERE kind = 'SPEND' AND day BETWEEN :from AND :to GROUP BY jarId")
    suspend fun spentByJar(from: Long, to: Long): List<IdSum>

    @Query(
        """
        SELECT categoryId AS id, SUM(amount) AS total FROM txn
        WHERE type = 'EXPENSE' AND categoryId IS NOT NULL AND day BETWEEN :from AND :to
        GROUP BY categoryId ORDER BY total DESC
        """,
    )
    suspend fun expenseByCategory(from: Long, to: Long): List<IdSum>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM txn WHERE type = :type AND day BETWEEN :from AND :to")
    suspend fun total(type: String, from: Long, to: Long): Long

    /** Totals of one transaction type per month. [day] is an epoch day, so day * 86400 is that date at midnight UTC. */
    @Query(
        """
        SELECT CAST(strftime('%Y%m', day * 86400, 'unixepoch') AS INTEGER) AS ym, SUM(amount) AS total FROM txn
        WHERE type = :type AND day BETWEEN :from AND :to GROUP BY ym
        """,
    )
    suspend fun totalsByMonth(type: String, from: Long, to: Long): List<MonthSum>

    /** One category per month (spending or income, whichever the category is). */
    @Query(
        """
        SELECT CAST(strftime('%Y%m', day * 86400, 'unixepoch') AS INTEGER) AS ym, SUM(amount) AS total FROM txn
        WHERE type IN ('EXPENSE', 'INCOME') AND categoryId = :categoryId AND day BETWEEN :from AND :to GROUP BY ym
        """,
    )
    suspend fun categoryByMonth(categoryId: Long, from: Long, to: Long): List<MonthSum>

    @Query(
        """
        SELECT categoryId AS id, SUM(amount) AS total FROM txn
        WHERE type = 'INCOME' AND categoryId IS NOT NULL AND day BETWEEN :from AND :to
        GROUP BY categoryId ORDER BY total DESC
        """,
    )
    suspend fun incomeByCategory(from: Long, to: Long): List<IdSum>

    /** What went into each jar apart from opening balances and spending (see [savedInto]). */
    @Query("SELECT jarId AS id, COALESCE(SUM(amount), 0) AS total FROM jar_entry WHERE kind NOT IN ('OPENING', 'SPEND') AND day BETWEEN :from AND :to GROUP BY jarId")
    suspend fun savedByJar(from: Long, to: Long): List<IdSum>

    /**
     * What went into the savings jars: allocations, moves and repayments in, minus money
     * moved or lent out. Spending straight from a savings jar (buying a goal) is not "unsaving".
     */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM jar_entry WHERE jarId IN (:jarIds) AND kind NOT IN ('OPENING', 'SPEND') AND day BETWEEN :from AND :to")
    suspend fun savedInto(jarIds: List<Long>, from: Long, to: Long): Long

    @Query("SELECT jarId AS id, COALESCE(SUM(amount), 0) AS total FROM jar_entry WHERE day <= :to GROUP BY jarId")
    suspend fun jarBalancesAt(to: Long): List<IdSum>

    @Query(
        """
        SELECT jarId, peerJarId, kind, SUM(amount) AS total FROM jar_entry
        WHERE kind IN ('BORROW', 'REPAY') AND amount > 0 AND peerJarId IS NOT NULL AND day <= :to
        GROUP BY jarId, peerJarId, kind
        """,
    )
    suspend fun loanSumsAt(to: Long): List<LoanSum>
}
