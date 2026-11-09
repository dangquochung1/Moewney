package app.meowney.core.data

import androidx.room.withTransaction
import app.meowney.core.calendar.CalendarEngine
import app.meowney.core.calendar.EventRepeat
import app.meowney.core.calendar.Occurrence
import app.meowney.core.db.CalLabelEntity
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.EventEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.TodoEntity
import app.meowney.core.db.TodoStepEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** An event occurrence with what it needs to be drawn. */
data class EventItem(val event: EventEntity, val occ: Occurrence, val label: CalLabelEntity?) {
    val color get() = label?.color ?: DEFAULT_COLOR
    val birthday get() = label?.kind == "BIRTHDAY"
}

data class TodoItem(val todo: TodoEntity, val steps: List<TodoStepEntity>, val label: CalLabelEntity?) {
    val done get() = todo.doneAt != null
    val color get() = label?.color ?: DEFAULT_COLOR
    val day: LocalDate? get() = todo.dueDay?.let(LocalDate::ofEpochDay)
}

/** Everything on one calendar day. */
data class CalDay(
    val day: LocalDate,
    val events: List<EventItem> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val spent: Long = 0,
    val earned: Long = 0,
    /** Day-to-day spending went over that day's "hôm nay còn". */
    val overLimit: Boolean = false,
    val habitsDone: Int = 0,
    val habitsAllDone: Boolean = false,
)

const val DEFAULT_COLOR = 0xFF6FA8DCL

/** Defaults; the birthday one is special (countdown, the cat's hat). */
val DEFAULT_LABELS = listOf(
    CalLabelEntity(name = "Học", color = 0xFF6FA8DCL, sort = 0),
    CalLabelEntity(name = "Làm", color = 0xFFE86A33L, sort = 1),
    CalLabelEntity(name = "Cá nhân", color = 0xFFA77BCAL, sort = 2),
    CalLabelEntity(name = "Gia đình", color = 0xFF5CBF6AL, sort = 3),
    CalLabelEntity(name = "Sức khoẻ", color = 0xFFE86A5AL, sort = 4),
    CalLabelEntity(name = "Sinh nhật", color = 0xFFF08DB3L, sort = 5, kind = "BIRTHDAY"),
)

/** Colours a label can take. */
val LABEL_COLORS = listOf(
    0xFF6FA8DCL, 0xFFE86A33L, 0xFFA77BCAL, 0xFF5CBF6AL, 0xFFE86A5AL, 0xFFF08DB3L,
    0xFFF2C14EL, 0xFF4FB5A5L, 0xFF8A5536L, 0xFF9AA5B1L,
)

class CalendarRepository(private val db: MeowneyDb) {
    private val dao = db.calendar()
    private val coins = db.money()

    val labels = dao.labels()
    val events = dao.events()
    val todos = dao.todos()

    suspend fun ensureLabels() {
        if (dao.labelsNow().isEmpty()) dao.insertLabels(DEFAULT_LABELS)
    }

    /** All the days from [from] to [to] with their events, to-dos, money and habits. */
    fun days(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, CalDay>> {
        val f = from.toEpochDay()
        val t = to.toEpochDay()
        val items = combine(dao.labels(), dao.events(), dao.todos(), dao.steps()) { labels, events, todos, steps ->
            val byId = labels.associateBy { it.id }
            val stepsOf = steps.groupBy { it.todoId }
            val evs = events.flatMap { e -> CalendarEngine.occurrences(e, from, to).map { EventItem(e, it, e.labelId?.let(byId::get)) } }
            val tds = todos.filter { it.dueDay != null && it.dueDay in f..t }.map { TodoItem(it, stepsOf[it.id].orEmpty(), it.labelId?.let(byId::get)) }
            evs to tds
        }
        val money = combine(
            dao.totalsByDay("EXPENSE", f, t), dao.totalsByDay("INCOME", f, t), dao.dayStats(f, t),
            dao.habitsAllDoneDays(f, t), dao.habitsDoneByDay(f, t),
        ) { spent, earned, stats, allDone, habits ->
            DayExtras(
                spent.associate { it.day to it.total }, earned.associate { it.day to it.total },
                stats.filter { it.spent > it.allowanceBase.coerceAtLeast(0) }.map { it.day }.toSet(),
                allDone.toSet(), habits.associate { it.day to it.total.toInt() },
            )
        }
        return combine(items, money) { (evs, tds), x ->
            generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.associateWith { day ->
                val e = day.toEpochDay()
                CalDay(
                    day,
                    events = evs.filter { it.occ.covers(day) }.sortedWith(compareBy<EventItem> { !it.occ.allDay && !it.occ.multiDay }.thenBy { it.occ.startMinute ?: -1 }),
                    todos = tds.filter { it.todo.dueDay == e }.sortedWith(compareBy<TodoItem> { it.done }.thenByDescending { it.todo.starred }.thenBy { it.todo.dueMinute ?: Int.MAX_VALUE }),
                    spent = x.spent[e] ?: 0,
                    earned = x.earned[e] ?: 0,
                    overLimit = e in x.over,
                    habitsDone = x.habits[e] ?: 0,
                    habitsAllDone = e in x.allDone,
                )
            }
        }
    }

    private data class DayExtras(val spent: Map<Long, Long>, val earned: Map<Long, Long>, val over: Set<Long>, val allDone: Set<Long>, val habits: Map<Long, Int>)

    /** To-dos without a day, and unfinished ones whose day has passed. */
    fun inboxAndOverdue(today: LocalDate): Flow<Pair<List<TodoItem>, List<TodoItem>>> =
        combine(dao.labels(), dao.todos(), dao.steps()) { labels, todos, steps ->
            val byId = labels.associateBy { it.id }
            val stepsOf = steps.groupBy { it.todoId }
            fun item(t: TodoEntity) = TodoItem(t, stepsOf[t.id].orEmpty(), t.labelId?.let(byId::get))
            val open = todos.filter { it.doneAt == null }
            open.filter { it.dueDay == null }.map(::item) to
                open.filter { it.dueDay != null && it.dueDay < today.toEpochDay() }.sortedBy { it.dueDay }.map(::item)
        }

    // ------------------------------------------------------------------ labels

    suspend fun saveLabel(l: CalLabelEntity) = if (l.id == 0L) dao.insertLabel(l.copy(sort = (dao.labelsNow().maxOfOrNull { it.sort } ?: 0) + 1)) else l.id.also { dao.updateLabel(l) }
    suspend fun deleteLabel(l: CalLabelEntity) = dao.deleteLabel(l)

    // ------------------------------------------------------------------ events

    suspend fun event(id: Long) = dao.event(id)
    suspend fun eventsNow() = dao.eventsNow()
    suspend fun todosNow() = dao.todosNow()

    suspend fun saveEvent(e: EventEntity): Long = if (e.id == 0L) dao.insertEvent(e.copy(createdAt = System.currentTimeMillis())) else e.id.also { dao.updateEvent(e) }

    suspend fun deleteEvent(id: Long) = dao.deleteEvent(id)

    /** "Xoá lần này" of a repeating event. */
    suspend fun skipOccurrence(id: Long, day: LocalDate) {
        val e = dao.event(id) ?: return
        val days = (e.skipDays.split(',').filter { it.isNotBlank() } + day.toEpochDay().toString()).distinct()
        dao.updateEvent(e.copy(skipDays = days.joinToString(",")))
    }

    // ------------------------------------------------------------------ to-dos

    suspend fun todo(id: Long) = dao.todo(id)
    suspend fun stepsOf(id: Long) = dao.stepsOf(id)

    /** Saves a to-do with its steps (replacing the old ones). */
    suspend fun saveTodo(t: TodoEntity, steps: List<TodoStepEntity>): Long = db.withTransaction {
        val id = if (t.id == 0L) dao.insertTodo(t.copy(createdAt = System.currentTimeMillis())) else t.id.also { dao.updateTodo(t) }
        dao.deleteStepsOf(id)
        dao.insertSteps(steps.mapIndexed { i, s -> s.copy(id = 0, todoId = id, sort = i) })
        id
    }

    suspend fun deleteTodo(id: Long) = db.withTransaction {
        dao.deleteCoins(R_TODO, id)
        dao.deleteTodo(id)
    }

    /** Ticks a to-do (coins; a repeating one gets its next copy) or unticks it (coins back). */
    suspend fun toggleTodo(id: Long, today: LocalDate = LocalDate.now()): Int = db.withTransaction {
        val t = dao.todo(id) ?: return@withTransaction 0
        if (t.doneAt == null) complete(t, today) else {
            undo(t)
            0
        }
    }

    /** Ticks one step; all steps done finishes the to-do, unticking one reopens it. */
    suspend fun toggleStep(step: TodoStepEntity, today: LocalDate = LocalDate.now()): Int = db.withTransaction {
        dao.updateStep(step.copy(done = !step.done))
        val t = dao.todo(step.todoId) ?: return@withTransaction 0
        val steps = dao.stepsOf(t.id)
        when {
            steps.all { it.done } && t.doneAt == null -> complete(t, today)
            !steps.all { it.done } && t.doneAt != null -> {
                undo(t)
                0
            }
            else -> 0
        }
    }

    private suspend fun complete(t: TodoEntity, today: LocalDate): Int {
        val onTime = t.dueDay == null || today.toEpochDay() <= t.dueDay
        val c = CalendarEngine.todoCoins(t.difficulty, onTime)
        dao.updateTodo(t.copy(doneAt = System.currentTimeMillis(), doneDay = today.toEpochDay()))
        coins.insertCoins(listOf(CoinEntryEntity(delta = c, reason = R_TODO, refId = t.id, day = today.toEpochDay())))
        val next = t.dueDay?.let { CalendarEngine.nextDue(EventRepeat.valueOf(t.repeat), LocalDate.ofEpochDay(it)) }
        if (next != null) {
            val copy = t.copy(id = 0, dueDay = next.toEpochDay(), doneAt = null, doneDay = null, createdAt = System.currentTimeMillis())
            val newId = dao.insertTodo(copy)
            dao.insertSteps(dao.stepsOf(t.id).map { it.copy(id = 0, todoId = newId, done = false) })
        }
        return c
    }

    private suspend fun undo(t: TodoEntity) {
        dao.updateTodo(t.copy(doneAt = null, doneDay = null))
        dao.deleteCoins(R_TODO, t.id)
        // the next copy of a repeating to-do goes away again if it is untouched
        val next = t.dueDay?.let { CalendarEngine.nextDue(EventRepeat.valueOf(t.repeat), LocalDate.ofEpochDay(it)) } ?: return
        dao.todosNow().firstOrNull { it.title == t.title && it.dueDay == next.toEpochDay() && it.doneAt == null && it.repeat == t.repeat && it.id != t.id }
            ?.let { dao.deleteTodo(it.id) }
    }

    companion object {
        const val R_TODO = "todo"
    }
}
