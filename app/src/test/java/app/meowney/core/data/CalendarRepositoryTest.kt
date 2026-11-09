package app.meowney.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meowney.core.db.EventEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.TodoEntity
import app.meowney.core.db.TodoStepEntity
import app.meowney.core.money.BorrowPlan
import app.meowney.core.money.JarCode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarRepositoryTest {
    private lateinit var db: MeowneyDb
    private lateinit var money: MoneyRepository
    private lateinit var cal: CalendarRepository
    private val today = LocalDate.now()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeowneyDb::class.java)
            .allowMainThreadQueries().build()
        money = MoneyRepository(db)
        cal = CalendarRepository(db)
        val percents = mapOf(JarCode.NEC to 55, JarCode.FFA to 10, JarCode.LTSS to 10, JarCode.EDU to 10, JarCode.PLAY to 10, JarCode.GIVE to 5)
        money.onboard(listOf(WalletDraft("Tiền mặt", "CASH", 1_000_000)), 0, percents, mapOf(JarCode.NEC to 1_000_000L), today.minusDays(10))
        cal.ensureLabels()
    }

    @After fun tearDown() = db.close()

    private suspend fun coins() = money.coins.first()
    private fun todo(title: String, due: LocalDate?, difficulty: Int = 2, repeat: String = "NONE") =
        TodoEntity(title = title, dueDay = due?.toEpochDay(), difficulty = difficulty, repeat = repeat, createdAt = 0)

    @Test fun defaultLabelsOnce() = runBlocking<Unit> {
        cal.ensureLabels()
        val labels = cal.labels.first()
        assertThat(labels).hasSize(6)
        assertThat(labels.single { it.kind == "BIRTHDAY" }.name).isEqualTo("Sinh nhật")
    }

    @Test fun todoCoinsOnTimeLateAndUndo() = runBlocking<Unit> {
        val onTime = cal.saveTodo(todo("Nộp báo cáo", today, difficulty = 3), emptyList())
        val late = cal.saveTodo(todo("Gọi điện", today.minusDays(2), difficulty = 1), emptyList())
        val before = coins()
        assertThat(cal.toggleTodo(onTime)).isEqualTo(8)
        assertThat(cal.toggleTodo(late)).isEqualTo(2)
        assertThat(coins()).isEqualTo(before + 10)
        cal.toggleTodo(onTime)
        assertThat(coins()).isEqualTo(before + 2)
    }

    @Test fun stepsFinishTheTodoAndRepeatingMakesTheNextOne() = runBlocking<Unit> {
        val id = cal.saveTodo(
            todo("Dọn nhà", today, repeat = "WEEKLY"),
            listOf(TodoStepEntity(todoId = 0, text = "Quét"), TodoStepEntity(todoId = 0, text = "Lau")),
        )
        val steps = cal.stepsOf(id)
        assertThat(cal.toggleStep(steps[0])).isEqualTo(0)
        assertThat(cal.todo(id)!!.doneAt).isNull()
        assertThat(cal.toggleStep(cal.stepsOf(id)[1])).isEqualTo(6)
        assertThat(cal.todo(id)!!.doneAt).isNotNull()
        val next = cal.todosNow().single { it.id != id }
        assertThat(next.dueDay).isEqualTo(today.plusWeeks(1).toEpochDay())
        assertThat(cal.stepsOf(next.id).map { it.done }).containsExactly(false, false)

        // unticking a step reopens it and removes the untouched next copy
        cal.toggleStep(cal.stepsOf(id)[0])
        assertThat(cal.todo(id)!!.doneAt).isNull()
        assertThat(cal.todosNow()).hasSize(1)
    }

    @Test fun inboxAndOverdue() = runBlocking<Unit> {
        cal.saveTodo(todo("Mua quà", null), emptyList())
        cal.saveTodo(todo("Trả sách", today.minusDays(1)), emptyList())
        cal.saveTodo(todo("Hôm nay", today), emptyList())
        val (inbox, overdue) = cal.inboxAndOverdue(today).first()
        assertThat(inbox.map { it.todo.title }).containsExactly("Mua quà")
        assertThat(overdue.map { it.todo.title }).containsExactly("Trả sách")
    }

    @Test fun daysGatherEventsTodosAndSpending() = runBlocking<Unit> {
        val label = cal.labels.first().first()
        cal.saveEvent(EventEntity(title = "Đi Đà Lạt", labelId = label.id, allDay = true, startDay = today.toEpochDay(), endDay = today.plusDays(2).toEpochDay(), createdAt = 0))
        cal.saveEvent(EventEntity(title = "Họp", startDay = today.toEpochDay(), startMinute = 9 * 60, endDay = today.toEpochDay(), endMinute = 10 * 60, repeat = "DAILY", createdAt = 0))
        cal.saveTodo(todo("Nộp báo cáo", today.plusDays(1)), emptyList())
        val nec = money.jarsNow().first { it.code == "NEC" }.id
        val food = money.categories.first().first { it.name == "Ăn uống" }.id
        val wallet = money.wallets.first().first().id
        money.addExpense(TxnDraft(50_000, wallet, categoryId = food, jarId = nec, date = today), BorrowPlan(emptyList(), 0))

        val days = cal.days(today, today.plusDays(3)).first()
        assertThat(days[today]!!.events.map { it.event.title }).containsExactly("Đi Đà Lạt", "Họp").inOrder()
        assertThat(days[today]!!.spent).isEqualTo(50_000)
        assertThat(days[today.plusDays(1)]!!.todos.single().todo.title).isEqualTo("Nộp báo cáo")
        assertThat(days[today.plusDays(2)]!!.events.map { it.event.title }).contains("Đi Đà Lạt")
        assertThat(days[today.plusDays(3)]!!.events.map { it.event.title }).containsExactly("Họp")

        // "Xoá lần này" of the daily meeting
        val meetId = cal.eventsNow().first { it.title == "Họp" }.id
        cal.skipOccurrence(meetId, today.plusDays(3))
        assertThat(cal.days(today, today.plusDays(3)).first()[today.plusDays(3)]!!.events).isEmpty()
    }
}
