package app.meowney.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A colour tag for events and to-dos. [kind] BIRTHDAY marks the birthday label. */
@Entity(tableName = "cal_label")
data class CalLabelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ARGB */
    val color: Long,
    val sort: Int = 0,
    /** NORMAL / BIRTHDAY */
    val kind: String = "NORMAL",
)

/**
 * A calendar event. Repeating events are stored once and expanded on the fly
 * (core/calendar/CalendarEngine.kt). Days are LocalDate.toEpochDay().
 */
@Entity(tableName = "event", indices = [Index("startDay")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val labelId: Long? = null,
    val allDay: Boolean = false,
    val startDay: Long,
    /** Minutes after midnight; null when all day. */
    val startMinute: Int? = null,
    /** Last day (same as startDay for a one-day event). */
    val endDay: Long,
    val endMinute: Int? = null,
    /** NONE / DAILY / WEEKLY / MONTHLY / YEARLY */
    val repeat: String = "NONE",
    val repeatUntil: Long? = null,
    /** Start days of single occurrences that were deleted, comma separated. */
    val skipDays: String = "",
    val remind: Boolean = true,
    /** Minutes before the start, comma separated (e.g. "15,60"). */
    val remindOffsets: String = "15",
    val location: String = "",
    val note: String = "",
    /** Expected spending on the day (0 = none) and its category. */
    val cost: Long = 0,
    val costCategoryId: Long? = null,
    val createdAt: Long,
)

/** A to-do. No [dueDay] = in "Chưa xếp lịch". */
@Entity(tableName = "todo", indices = [Index("dueDay")])
data class TodoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val labelId: Long? = null,
    val dueDay: Long? = null,
    val dueMinute: Int? = null,
    val difficulty: Int = 2,
    val starred: Boolean = false,
    val note: String = "",
    /** NONE / DAILY / WEEKLY / MONTHLY / YEARLY: finishing it creates the next one. */
    val repeat: String = "NONE",
    val remind: Boolean = true,
    val doneAt: Long? = null,
    val doneDay: Long? = null,
    val createdAt: Long,
    val sort: Int = 0,
)

@Entity(
    tableName = "todo_step",
    foreignKeys = [ForeignKey(TodoEntity::class, ["id"], ["todoId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("todoId")],
)
data class TodoStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val todoId: Long,
    val text: String,
    val done: Boolean = false,
    val sort: Int = 0,
)

@Dao
interface CalendarDao {
    @Query("SELECT * FROM cal_label ORDER BY sort, id")
    fun labels(): Flow<List<CalLabelEntity>>

    @Query("SELECT * FROM cal_label ORDER BY sort, id")
    suspend fun labelsNow(): List<CalLabelEntity>

    @Insert suspend fun insertLabels(l: List<CalLabelEntity>): List<Long>
    @Insert suspend fun insertLabel(l: CalLabelEntity): Long
    @Update suspend fun updateLabel(l: CalLabelEntity)
    @Delete suspend fun deleteLabel(l: CalLabelEntity)

    @Query("SELECT * FROM event")
    fun events(): Flow<List<EventEntity>>

    @Query("SELECT * FROM event")
    suspend fun eventsNow(): List<EventEntity>

    @Query("SELECT * FROM event WHERE id = :id")
    suspend fun event(id: Long): EventEntity?

    @Insert suspend fun insertEvent(e: EventEntity): Long
    @Update suspend fun updateEvent(e: EventEntity)

    @Query("DELETE FROM event WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    @Query("SELECT * FROM todo ORDER BY starred DESC, dueMinute IS NULL, dueMinute, sort, id")
    fun todos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todo")
    suspend fun todosNow(): List<TodoEntity>

    @Query("SELECT * FROM todo WHERE id = :id")
    suspend fun todo(id: Long): TodoEntity?

    @Insert suspend fun insertTodo(t: TodoEntity): Long
    @Update suspend fun updateTodo(t: TodoEntity)

    @Query("DELETE FROM todo WHERE id = :id")
    suspend fun deleteTodo(id: Long)

    @Query("SELECT * FROM todo_step ORDER BY sort, id")
    fun steps(): Flow<List<TodoStepEntity>>

    @Query("SELECT * FROM todo_step WHERE todoId = :todoId ORDER BY sort, id")
    suspend fun stepsOf(todoId: Long): List<TodoStepEntity>

    @Insert suspend fun insertSteps(s: List<TodoStepEntity>)
    @Update suspend fun updateStep(s: TodoStepEntity)

    @Query("DELETE FROM todo_step WHERE todoId = :todoId")
    suspend fun deleteStepsOf(todoId: Long)

    // ------------------------------------------------------------------ what else happened on a day

    @Query("SELECT day, SUM(amount) AS total FROM txn WHERE type = :type AND day BETWEEN :from AND :to GROUP BY day")
    fun totalsByDay(type: String, from: Long, to: Long): Flow<List<DaySum>>

    @Query("SELECT * FROM day_stat WHERE day BETWEEN :from AND :to")
    fun dayStats(from: Long, to: Long): Flow<List<DayStatEntity>>

    /** Days where every habit was done (the all-done bonus was paid). */
    @Query("SELECT DISTINCT day FROM coin_entry WHERE reason = 'habits_all' AND day BETWEEN :from AND :to")
    fun habitsAllDoneDays(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT day, COUNT(*) AS total FROM habit_log WHERE status IN ('DONE', 'KEPT') AND day BETWEEN :from AND :to GROUP BY day")
    fun habitsDoneByDay(from: Long, to: Long): Flow<List<DaySum>>

    @Query("DELETE FROM coin_entry WHERE reason = :reason AND refId = :refId")
    suspend fun deleteCoins(reason: String, refId: Long)
}
