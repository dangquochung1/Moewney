package app.meowney.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meowney.core.db.MeowneyDb
import app.meowney.core.money.BorrowPlan
import app.meowney.core.money.JarCode
import app.meowney.core.money.MoneyEngine
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
import java.time.YearMonth

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportsTest {
    private lateinit var db: MeowneyDb
    private lateinit var repo: MoneyRepository
    private lateinit var reports: Reports
    private val today = LocalDate.now()
    private val last = YearMonth.from(today).minusMonths(1)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeowneyDb::class.java)
            .allowMainThreadQueries().build()
        repo = MoneyRepository(db)
        reports = Reports(db)
        val percents = mapOf(JarCode.NEC to 55, JarCode.FFA to 10, JarCode.LTSS to 10, JarCode.EDU to 10, JarCode.PLAY to 10, JarCode.GIVE to 5)
        repo.onboard(
            listOf(WalletDraft("Tiền mặt", "CASH", 10_000_000)),
            defaultWallet = 0, jarPercents = percents,
            openingShares = mapOf(JarCode.NEC to 10_000_000L), today = last.atDay(1),
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun jar(code: JarCode) = repo.jarsNow().first { it.code == code.name }.id
    private suspend fun cash() = repo.wallets.first().first().id
    private suspend fun cat(name: String) = repo.categories.first().first { it.name == name && it.type == "EXPENSE" }.id

    private suspend fun spend(day: LocalDate, amount: Long, category: String = "Ăn uống") {
        repo.addExpense(TxnDraft(amount, cash(), categoryId = cat(category), jarId = jar(JarCode.NEC), date = day), BorrowPlan(emptyList(), 0))
    }

    @Test fun lastMonthIsScoredFromTheLedger() = runBlocking {
        for (d in 1..10) {
            val day = last.atDay(d)
            spend(day, 100_000)
            reports.recordDay(day, 300_000, 100_000, true)
        }
        val day11 = last.atDay(11)
        spend(day11, 500_000, "Mua sắm") // over the 300k limit
        reports.recordDay(day11, 300_000, 500_000, true)
        repo.addIncome(TxnDraft(10_000_000, cash(), date = last.atDay(2)), MoneyEngine.allocateIncome(10_000_000, repo.jarInfos(), emptyList()))

        val d = reports.compute(last, today)!!
        assertThat(d.days).isEqualTo(last.lengthOfMonth())
        assertThat(d.loggedDays).isEqualTo(11)
        assertThat(d.planned).isEqualTo(2_000_000) // 20% of the income goes to the 2 savings jars
        assertThat(d.saved).isEqualTo(2_000_000)
        assertThat(d.statDays).isEqualTo(11)
        assertThat(d.daysWithin).isEqualTo(10)
        assertThat(d.hasJarLoans).isFalse()
        assertThat(d.byDay[10]).isEqualTo(500_000)
        assertThat(d.byCategory.first().label).isEqualTo("Ăn uống") // 1tr beats 500k
        assertThat(d.byJar.single().amount).isEqualTo(1_500_000)
        assertThat(d.score.saving).isEqualTo(1f)
    }

    @Test fun keptOnceAndChestOpensOnce() = runBlocking {
        spend(last.atDay(3), 50_000)
        assertThat(reports.pending(today)).isEqualTo(last)
        val card = reports.load(last, today)!!
        assertThat(card.final).isTrue()

        // later spending in that month does not change a kept card
        spend(last.atDay(4), 70_000)
        assertThat(reports.load(last, today)!!.data.expense).isEqualTo(50_000)

        val coinsBefore = repo.coins.first()
        val won = reports.claim(last, today)
        assertThat(won).isEqualTo(card.kept!!.coins)
        assertThat(reports.claim(last, today)).isEqualTo(0)
        assertThat(repo.coins.first()).isEqualTo(coinsBefore + won)
        assertThat(reports.pending(today)).isNull()

        // this month is a running preview, never kept
        val now = reports.load(YearMonth.from(today), today)!!
        assertThat(now.final).isFalse()
        assertThat(reports.months(today)).containsExactly(YearMonth.from(today), last).inOrder()
    }
}
