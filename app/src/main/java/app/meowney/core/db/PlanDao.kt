package app.meowney.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A goal with how much it holds. */
data class GoalRow(
    val id: Long,
    val name: String,
    val jarId: Long,
    val target: Long,
    val deadlineDay: Long?,
    val autoPercent: Int,
    val createdDay: Long,
    val achievedDay: Long?,
    val archived: Boolean,
    val saved: Long,
)

/** A debt with how much has been paid back. */
data class DebtRow(
    val id: Long,
    val person: String,
    val direction: String,
    val principal: Long,
    val walletId: Long,
    val jarId: Long,
    val day: Long,
    val dueDay: Long?,
    val note: String,
    val settledDay: Long?,
    val paid: Long,
) {
    val left get() = principal - paid
}

/** Goals, debts, recurring bills, daily stats and month reports. */
@Dao
interface PlanDao {
    // ------------------------------------------------------------------ goals

    @Query(
        """
        SELECT g.*, COALESCE((SELECT SUM(e.amount) FROM goal_entry e WHERE e.goalId = g.id), 0) AS saved
        FROM goal g WHERE g.archived = 0 ORDER BY g.achievedDay IS NOT NULL, g.createdDay, g.id
        """,
    )
    fun goals(): Flow<List<GoalRow>>

    @Query(
        """
        SELECT g.*, COALESCE((SELECT SUM(e.amount) FROM goal_entry e WHERE e.goalId = g.id), 0) AS saved
        FROM goal g WHERE g.archived = 0 ORDER BY g.createdDay, g.id
        """,
    )
    suspend fun goalsNow(): List<GoalRow>

    @Query("SELECT * FROM goal WHERE id = :id")
    suspend fun goal(id: Long): GoalEntity?

    @Insert suspend fun insertGoal(g: GoalEntity): Long
    @Update suspend fun updateGoal(g: GoalEntity)
    @Insert suspend fun insertGoalEntries(e: List<GoalEntryEntity>)

    @Query("DELETE FROM goal_entry WHERE txnId = :txnId")
    suspend fun deleteGoalEntriesOf(txnId: Long)

    @Query("SELECT * FROM goal_entry WHERE goalId = :goalId ORDER BY day DESC, id DESC")
    fun goalEntries(goalId: Long): Flow<List<GoalEntryEntity>>

    /** Money held by active goals, per jar. */
    @Query(
        """
        SELECT g.jarId AS id, COALESCE(SUM(e.amount), 0) AS total FROM goal g
        JOIN goal_entry e ON e.goalId = g.id WHERE g.archived = 0 GROUP BY g.jarId
        """,
    )
    suspend fun goalTotalsByJar(): List<IdSum>

    @Query(
        """
        SELECT g.jarId AS id, COALESCE(SUM(e.amount), 0) AS total FROM goal g
        JOIN goal_entry e ON e.goalId = g.id WHERE g.archived = 0 GROUP BY g.jarId
        """,
    )
    fun goalTotalsByJarFlow(): Flow<List<IdSum>>

    // ------------------------------------------------------------------ debts

    @Query(
        """
        SELECT d.*, COALESCE((SELECT SUM(t.amount) FROM txn t WHERE t.debtId = d.id AND t.source = 'DEBT_PAY'), 0) AS paid
        FROM debt d ORDER BY d.settledDay IS NOT NULL, d.dueDay IS NULL, d.dueDay, d.day DESC
        """,
    )
    fun debts(): Flow<List<DebtRow>>

    @Query(
        """
        SELECT d.*, COALESCE((SELECT SUM(t.amount) FROM txn t WHERE t.debtId = d.id AND t.source = 'DEBT_PAY'), 0) AS paid
        FROM debt d WHERE d.id = :id
        """,
    )
    suspend fun debt(id: Long): DebtRow?

    @Query(
        """
        SELECT d.*, COALESCE((SELECT SUM(t.amount) FROM txn t WHERE t.debtId = d.id AND t.source = 'DEBT_PAY'), 0) AS paid
        FROM debt d WHERE d.settledDay IS NULL AND d.dueDay IS NOT NULL AND d.dueDay <= :day
        """,
    )
    suspend fun debtsDue(day: Long): List<DebtRow>

    @Insert suspend fun insertDebt(d: DebtEntity): Long
    @Update suspend fun updateDebt(d: DebtEntity)

    @Query("DELETE FROM debt WHERE id = :id")
    suspend fun deleteDebt(id: Long)

    @Query("SELECT id FROM txn WHERE debtId = :debtId")
    suspend fun txnIdsOfDebt(debtId: Long): List<Long>

    // ------------------------------------------------------------------ recurring

    @Query("SELECT * FROM recurring ORDER BY active DESC, nextDueDay, id")
    fun recurring(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring WHERE active = 1 ORDER BY nextDueDay, id")
    suspend fun recurringNow(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun recurringById(id: Long): RecurringEntity?

    @Insert suspend fun insertRecurring(r: RecurringEntity): Long
    @Update suspend fun updateRecurring(r: RecurringEntity)

    @Query("DELETE FROM recurring WHERE id = :id")
    suspend fun deleteRecurring(id: Long)

    // ------------------------------------------------------------------ stats & reports

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayStat(s: DayStatEntity)

    @Query("SELECT * FROM day_stat WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun dayStats(from: Long, to: Long): List<DayStatEntity>

    @Query("SELECT * FROM month_report WHERE yyyymm = :yyyymm")
    suspend fun report(yyyymm: Int): MonthReportEntity?

    @Query("SELECT * FROM month_report ORDER BY yyyymm DESC")
    fun reports(): Flow<List<MonthReportEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReport(r: MonthReportEntity): Long

    @Update suspend fun updateReport(r: MonthReportEntity)
}
