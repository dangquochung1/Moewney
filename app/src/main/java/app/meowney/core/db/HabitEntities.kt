package app.meowney.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A habit. Types, schedules and parts of the day are the enums in core/habit/HabitEngine.kt. */
@Entity(tableName = "habit")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** CHECK / COUNT / TIMER / QUIT */
    val type: String,
    /** Cell in assets/icons/raven16.png. */
    val icon: Int,
    /** Times a day (COUNT) or minutes a day (TIMER); 1 for CHECK and QUIT. */
    val target: Int = 1,
    val unit: String = "",
    /** DAILY / WEEKDAYS / WEEKLY */
    val schedule: String = "DAILY",
    /** For WEEKDAYS: bit 0 = Monday ... bit 6 = Sunday. */
    val weekdays: Int = 0x7F,
    /** For WEEKLY: how many days a week. */
    val timesPerWeek: Int = 0,
    /** MORNING / ALL / AFTERNOON / EVENING */
    val partOfDay: String = "ALL",
    /** Minutes after midnight, null = no reminder. */
    val reminderMinute: Int? = null,
    /** 1 easy, 2 medium, 3 hard: the coins of each completion. */
    val difficulty: Int = 2,
    /** Money moved when the habit is kept (0 = not linked). */
    val moneyAmount: Long = 0,
    val moneyFromJarId: Long? = null,
    /** Destination: a goal (inside its savings jar) or else a jar. */
    val moneyToGoalId: Long? = null,
    val moneyToJarId: Long? = null,
    /** 0..100, see HabitEngine.close. */
    val strength: Float = 0f,
    val streak: Int = 0,
    val bestStreak: Int = 0,
    /** Rest tickets: a missed period is forgiven while there is one. */
    val tickets: Int = 0,
    /** Every period up to this epoch day has been closed. */
    val closedThrough: Long,
    val createdDay: Long,
    val archived: Boolean = false,
    val sort: Int = 0,
)

/**
 * One habit on one day. Status: OPEN (today, not done yet), DONE, FAILED ("Lỡ rồi" on a quit
 * habit), and after closing KEPT (quit habit held), MISSED, RESTED (missed, a ticket used).
 */
@Entity(
    tableName = "habit_log",
    foreignKeys = [
        ForeignKey(HabitEntity::class, ["id"], ["habitId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TxnEntity::class, ["id"], ["txnId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["habitId", "day"], unique = true), Index("txnId"), Index("day")],
)
data class HabitLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val day: Long,
    /** Count done (COUNT) or minutes focused (TIMER); 1 when a CHECK is done. */
    val value: Int = 0,
    val status: String = "OPEN",
    /** The JAR_MOVE transaction of the money link, if any. */
    val txnId: Long? = null,
)

/** One Pomodoro focus block. Minutes count toward [habitId] when set. */
@Entity(
    tableName = "focus_session",
    foreignKeys = [ForeignKey(HabitEntity::class, ["id"], ["habitId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("habitId"), Index("day")],
)
data class FocusSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long? = null,
    val startedAt: Long,
    val focusMinutes: Int,
    val completed: Boolean,
    val day: Long,
)

@Dao
interface HabitDao {
    @Query("SELECT * FROM habit WHERE archived = 0 ORDER BY sort, id")
    fun habits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habit WHERE archived = 0 ORDER BY sort, id")
    suspend fun habitsNow(): List<HabitEntity>

    @Query("SELECT * FROM habit WHERE id = :id")
    suspend fun habit(id: Long): HabitEntity?

    @Query("SELECT * FROM habit WHERE id = :id")
    fun habitFlow(id: Long): Flow<HabitEntity?>

    @Insert suspend fun insertHabit(h: HabitEntity): Long

    @Update suspend fun updateHabit(h: HabitEntity)

    @Query("SELECT * FROM habit_log WHERE day = :day")
    fun logsOn(day: Long): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_log WHERE habitId = :habitId AND day = :day")
    suspend fun log(habitId: Long, day: Long): HabitLogEntity?

    @Query("SELECT * FROM habit_log WHERE habitId = :habitId AND day BETWEEN :from AND :to ORDER BY day")
    suspend fun logsBetween(habitId: Long, from: Long, to: Long): List<HabitLogEntity>

    @Query("SELECT * FROM habit_log WHERE day BETWEEN :from AND :to")
    fun allLogsBetween(from: Long, to: Long): Flow<List<HabitLogEntity>>

    @Query("SELECT * FROM habit_log WHERE habitId = :habitId AND day BETWEEN :from AND :to ORDER BY day")
    fun logsBetweenFlow(habitId: Long, from: Long, to: Long): Flow<List<HabitLogEntity>>

    @Insert suspend fun insertLog(l: HabitLogEntity): Long

    @Update suspend fun updateLog(l: HabitLogEntity)

    @Insert suspend fun insertFocus(s: FocusSessionEntity): Long

    @Query("SELECT COALESCE(SUM(focusMinutes), 0) FROM focus_session WHERE day = :day AND completed = 1")
    fun focusMinutesOn(day: Long): Flow<Int>

    @Query("DELETE FROM coin_entry WHERE reason = :reason AND refId = :refId")
    suspend fun deleteCoins(reason: String, refId: Long)

    @Query("SELECT COUNT(*) FROM coin_entry WHERE reason = :reason AND day = :day")
    suspend fun countCoinsOn(reason: String, day: Long): Int

    @Query("DELETE FROM coin_entry WHERE reason = :reason AND day = :day")
    suspend fun deleteCoinsOn(reason: String, day: Long)
}
