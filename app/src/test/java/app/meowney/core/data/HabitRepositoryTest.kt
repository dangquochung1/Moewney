package app.meowney.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meowney.core.db.GoalEntity
import app.meowney.core.db.HabitEntity
import app.meowney.core.db.MeowneyDb
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
class HabitRepositoryTest {
    private lateinit var db: MeowneyDb
    private lateinit var money: MoneyRepository
    private lateinit var habits: HabitRepository
    private val today = LocalDate.now()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeowneyDb::class.java)
            .allowMainThreadQueries().build()
        money = MoneyRepository(db)
        habits = HabitRepository(db, money)
        val percents = mapOf(JarCode.NEC to 55, JarCode.FFA to 10, JarCode.LTSS to 10, JarCode.EDU to 10, JarCode.PLAY to 10, JarCode.GIVE to 5)
        money.onboard(
            listOf(WalletDraft("Tiền mặt", "CASH", 1_000_000)), defaultWallet = 0, jarPercents = percents,
            openingShares = mapOf(JarCode.NEC to 800_000L, JarCode.PLAY to 200_000L), today = today.minusDays(30),
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun jar(code: JarCode) = money.jarsNow().first { it.code == code.name }.id
    private suspend fun coins() = money.coins.first()
    private suspend fun assertBalanced() {
        assertThat(money.jarStates.first().sumOf { it.balance }).isEqualTo(money.walletStates.first().sumOf { it.balance })
    }

    private suspend fun habit(name: String, type: String, created: LocalDate = today, target: Int = 1, difficulty: Int = 2, schedule: String = "DAILY", times: Int = 0, money: HabitEntity.() -> HabitEntity = { this }): Long =
        habits.save(
            HabitEntity(name = name, type = type, icon = 0, target = target, difficulty = difficulty, schedule = schedule, timesPerWeek = times, closedThrough = 0, createdDay = 0).money(),
            today = created,
        )

    @Test fun checkGivesCoinsByDifficultyAndUndoTakesThemBack() = runBlocking {
        val hard = habit("Tập thể dục", "CHECK", difficulty = 3)
        habit("Uống nước", "COUNT", target = 3, difficulty = 1)
        val before = coins()
        assertThat(habits.toggle(hard).coins).isEqualTo(6)
        assertThat(coins()).isEqualTo(before + 6)
        habits.toggle(hard)
        assertThat(coins()).isEqualTo(before)
    }

    @Test fun countReachesTargetAndAllDoneBonusComesOnce() = runBlocking {
        val water = habit("Uống nước", "COUNT", target = 3, difficulty = 1)
        val gym = habit("Tập thể dục", "CHECK")
        habit("Không trà sữa", "QUIT") // held by default, does not block the bonus
        val before = coins()
        habits.count(water)
        habits.count(water)
        assertThat(habits.today(today).first().first { it.habit.id == water }.done).isFalse()
        val third = habits.count(water)
        assertThat(third.coins).isEqualTo(2)
        assertThat(third.allDone).isFalse()
        val last = habits.toggle(gym)
        assertThat(last.allDone).isTrue()
        assertThat(coins()).isEqualTo(before + 2 + 4 + 10)

        // dropping below the target undoes it and the bonus
        habits.count(water, delta = -1)
        assertThat(coins()).isEqualTo(before + 4)
    }

    @Test fun moneyLinkMovesIntoGoalAndUndoes() = runBlocking {
        val ltss = jar(JarCode.LTSS)
        val goal = money.saveGoal(GoalEntity(name = "Laptop", jarId = ltss, target = 1_000_000, createdDay = today.toEpochDay()))
        val play = jar(JarCode.PLAY)
        val gym = habit("Tập thể dục", "CHECK") { copy(moneyAmount = 30_000, moneyFromJarId = play, moneyToGoalId = goal) }
        val r = habits.toggle(gym)
        assertThat(r.moved).isEqualTo(30_000)
        assertThat(money.goals.first().first { it.id == goal }.saved).isEqualTo(30_000)
        assertThat(money.balancesNow()[play]).isEqualTo(170_000)
        assertThat(money.balancesNow()[ltss]).isEqualTo(30_000)
        assertBalanced()

        habits.toggle(gym)
        assertThat(money.goals.first().first { it.id == goal }.saved).isEqualTo(0)
        assertThat(money.balancesNow()[play]).isEqualTo(200_000)
        assertBalanced()
    }

    @Test fun moneyLinkSkipsWhenTheJarIsShort() = runBlocking {
        val gym = habit("Tập thể dục", "CHECK") { copy(moneyAmount = 300_000, moneyFromJarId = runBlocking { jar(JarCode.PLAY) }, moneyToJarId = runBlocking { jar(JarCode.EDU) }) }
        val r = habits.toggle(gym)
        assertThat(r.moved).isEqualTo(0)
        assertThat(r.notes.single()).contains("không đủ")
        assertThat(r.coins).isEqualTo(4 + 10) // still done, and it is the only habit today
    }

    @Test fun closingKeepsQuitHabitsAndMovesMoneyEachDay() = runBlocking {
        val play = jar(JarCode.PLAY)
        val edu = jar(JarCode.EDU)
        val quit = habit("Không trà sữa", "QUIT", created = today.minusDays(3)) { copy(moneyAmount = 30_000, moneyFromJarId = play, moneyToJarId = edu) }
        habits.setFailed(quit, true, today = today.minusDays(2))
        val before = coins()

        val s = habits.closeThrough(today.minusDays(1))
        assertThat(s.kept).containsExactly("Không trà sữa")
        assertThat(s.missed).containsExactly("Không trà sữa")
        assertThat(coins()).isEqualTo(before + 2 * 4)
        assertThat(money.balancesNow()[edu]).isEqualTo(60_000) // 2 kept days
        assertBalanced()

        val h = habits.habitNow(quit)!!
        assertThat(h.closedThrough).isEqualTo(today.minusDays(1).toEpochDay())
        assertThat(h.streak).isEqualTo(1) // kept, missed, kept

        // closing again changes nothing
        assertThat(habits.closeThrough(today.minusDays(1)).isEmpty).isTrue()
        assertThat(coins()).isEqualTo(before + 8)
    }

    @Test fun ticketForgivesAMissAfterSevenKeptDays() = runBlocking {
        val start = today.minusDays(9)
        val gym = habit("Tập thể dục", "CHECK", created = start)
        for (i in 0..6) habits.toggle(gym, today = start.plusDays(i.toLong()))
        // day 7 and 8 missed: the first uses the ticket, the second breaks the streak
        val s = habits.closeThrough(today.minusDays(1))
        assertThat(s.rested).containsExactly("Tập thể dục")
        assertThat(s.missed).containsExactly("Tập thể dục")
        assertThat(s.notes.single()).contains("chuỗi 7")
        val h = habits.habitNow(gym)!!
        assertThat(h.streak).isEqualTo(0)
        assertThat(h.bestStreak).isEqualTo(7)
        assertThat(h.tickets).isEqualTo(0)
    }

    @Test fun weeklyHabitHidesWhenTheWeekIsDone() = runBlocking {
        val run = habit("Chạy bộ", "CHECK", schedule = "WEEKLY", times = 1, created = today.minusDays(14))
        assertThat(habits.today(today).first().map { it.habit.id }).contains(run)
        habits.toggle(run)
        assertThat(habits.today(today).first().single { it.habit.id == run }.done).isTrue()
        // the next day of the same week it is gone (unless the week rolled over)
        val tomorrow = today.plusDays(1)
        val ids = habits.today(tomorrow).first().map { it.habit.id }
        if (app.meowney.core.habit.HabitEngine.weekStart(tomorrow) == app.meowney.core.habit.HabitEngine.weekStart(today)) {
            assertThat(ids).doesNotContain(run)
        } else {
            assertThat(ids).contains(run)
        }
    }
}
