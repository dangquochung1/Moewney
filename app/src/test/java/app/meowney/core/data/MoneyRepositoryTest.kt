package app.meowney.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meowney.core.db.MeowneyDb
import app.meowney.core.money.EntryKind
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MoneyRepositoryTest {
    private lateinit var db: MeowneyDb
    private lateinit var repo: MoneyRepository
    private val today = LocalDate.now()

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeowneyDb::class.java)
            .allowMainThreadQueries().build()
        repo = MoneyRepository(db)
        val jars = repo.jarInfos() // empty before onboarding; use defaults for the split
        val percents = mapOf(JarCode.NEC to 55, JarCode.FFA to 10, JarCode.LTSS to 10, JarCode.EDU to 10, JarCode.PLAY to 10, JarCode.GIVE to 5)
        assertThat(jars).isEmpty()
        val shares = mapOf(
            JarCode.NEC to 9_350_000L, JarCode.FFA to 1_700_000L, JarCode.LTSS to 1_700_000L,
            JarCode.EDU to 1_700_000L, JarCode.PLAY to 1_700_000L, JarCode.GIVE to 850_000L,
        )
        repo.onboard(
            listOf(WalletDraft("Tiền mặt", "CASH", 2_000_000), WalletDraft("Ngân hàng", "BANK", 15_000_000)),
            defaultWallet = 0, jarPercents = percents, openingShares = shares, today = today,
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun jar(code: JarCode) = repo.jarsNow().first { it.code == code.name }.id
    private suspend fun cash() = repo.wallets.first().first { it.name == "Tiền mặt" }.id
    private suspend fun cat(name: String) = repo.categories.first().first { it.name == name && it.type == "EXPENSE" }.id

    private suspend fun assertBalanced() {
        val wallets = repo.walletStates.first().sumOf { it.balance }
        val jars = repo.jarStates.first().sumOf { it.balance }
        assertThat(jars).isEqualTo(wallets)
    }

    @Test fun onboardingPoursIntoJars() = runBlocking {
        val balances = repo.balancesNow()
        assertThat(balances[jar(JarCode.NEC)]).isEqualTo(9_350_000)
        assertThat(balances[jar(JarCode.GIVE)]).isEqualTo(850_000)
        assertThat(repo.walletStates.first().sumOf { it.balance }).isEqualTo(17_000_000)
        assertThat(repo.coins.first()).isEqualTo(20)
        assertBalanced()
    }

    @Test fun expenseWithBorrowAndDeleteCascade() = runBlocking {
        val play = jar(JarCode.PLAY)
        val amount = 2_000_000L // PLAY has 1.7tr
        val balances = repo.balancesNow()
        val plan = MoneyEngine.borrowPlan(MoneyEngine.shortfall(amount, play, balances), play, balances, repo.jarInfos(), false)
        val result = repo.addExpense(TxnDraft(amount, cash(), categoryId = cat("Mua sắm"), jarId = play, date = today), plan)
        assertThat(result.coins).isEqualTo(7) // first log of the day + 2

        assertThat(repo.balancesNow()[play]).isEqualTo(0)
        assertThat(repo.loans.first().single().amount).isEqualTo(300_000)
        assertBalanced()

        repo.deleteTxn(result.txnId)
        assertThat(repo.entriesOf(result.txnId)).isEmpty()
        assertThat(repo.balancesNow()[play]).isEqualTo(1_700_000)
        assertThat(repo.loans.first()).isEmpty()
        assertBalanced()
    }

    @Test fun incomeRepaysLoanAndEditKeepsId() = runBlocking {
        val play = jar(JarCode.PLAY)
        val balances = repo.balancesNow()
        val plan = MoneyEngine.borrowPlan(MoneyEngine.shortfall(2_000_000, play, balances), play, balances, repo.jarInfos(), false)
        repo.addExpense(TxnDraft(2_000_000, cash(), categoryId = cat("Mua sắm"), jarId = play, date = today), plan)

        val entries = MoneyEngine.allocateIncome(10_000_000, repo.jarInfos(), repo.loansNow())
        val income = repo.addIncome(TxnDraft(10_000_000, cash(), date = today), entries)
        assertThat(repo.loans.first()).isEmpty()
        assertThat(repo.entriesOf(income.txnId).map { it.kind }).contains(EntryKind.REPAY.name)
        assertBalanced()

        // edit the income amount: same id, entries rewritten
        val before = repo.txn(income.txnId)!!.occurredAt
        Thread.sleep(5)
        val edited = MoneyEngine.allocateIncome(5_000_000, repo.jarInfos(), repo.loansNow(excludingTxn = income.txnId))
        val again = repo.addIncome(TxnDraft(5_000_000, cash(), date = today), edited, editing = income.txnId)
        assertThat(again.txnId).isEqualTo(income.txnId)
        assertThat(repo.txn(income.txnId)!!.amount).isEqualTo(5_000_000)
        assertThat(repo.txn(income.txnId)!!.occurredAt).isEqualTo(before) // editing keeps the time
        assertThat(repo.loans.first()).isEmpty() // the edited income still repays the loan
        assertBalanced()
    }

    @Test fun transferAndAdjustStayBalanced() = runBlocking {
        val bank = repo.wallets.first().first { it.name == "Ngân hàng" }.id
        repo.transfer(TxnDraft(500_000, bank, toWalletId = cash(), date = today))
        val states = repo.walletStates.first().associate { it.wallet.id to it.balance }
        assertThat(states[cash()]).isEqualTo(2_500_000)
        repo.adjustWallet(cash(), currentBalance = 2_500_000, realBalance = 2_480_000)
        assertThat(repo.walletStates.first().first { it.wallet.id == cash() }.balance).isEqualTo(2_480_000)
        assertBalanced()
    }

    @Test fun goalsFillFromIncomeRewardOnceAndBuy() = runBlocking {
        val ltss = jar(JarCode.LTSS)
        val goalId = repo.saveGoal(
            app.meowney.core.db.GoalEntity(name = "Laptop", jarId = ltss, target = 2_000_000, autoPercent = 50, createdDay = today.toEpochDay()),
        )
        // 10tr income -> LTSS gets 1tr -> goal gets 500k (25%)
        val income = repo.addIncome(TxnDraft(10_000_000, cash(), date = today), MoneyEngine.allocateIncome(10_000_000, repo.jarInfos(), repo.loansNow()))
        var goal = repo.goals.first().single()
        assertThat(goal.saved).isEqualTo(500_000)
        assertThat(income.notes.single()).contains("25%")
        assertThat(income.coins).isEqualTo(7 + 10 + 50) // first log + log, income, 25% milestone

        // deleting the income removes its automatic part; re-adding does not pay the milestone twice
        repo.deleteTxn(income.txnId)
        assertThat(repo.goals.first().single().saved).isEqualTo(0)
        val again = repo.addIncome(TxnDraft(10_000_000, cash(), date = today), MoneyEngine.allocateIncome(10_000_000, repo.jarInfos(), repo.loansNow()))
        assertThat(again.notes).isEmpty()

        // manual top-up to the target, then buy it
        val top = repo.fundGoal(goalId, 1_500_000)
        assertThat(top.notes).hasSize(3) // 50, 75, 100
        goal = repo.goals.first().single()
        assertThat(goal.saved).isEqualTo(2_000_000)
        repo.buyGoal(goalId, cash(), cat("Mua sắm"))
        assertThat(repo.goals.first()).isEmpty()
        assertBalanced()
    }

    @Test fun savingsJarLendsOnlyFreeMoney() = runBlocking {
        val ltss = jar(JarCode.LTSS)
        val goalId = repo.saveGoal(
            app.meowney.core.db.GoalEntity(name = "Quỹ", jarId = ltss, target = 10_000_000, createdDay = today.toEpochDay()),
        )
        repo.fundGoal(goalId, 1_500_000) // LTSS 1.7tr, 1.5tr held -> 200k free
        assertThat(repo.freeInJar(ltss)).isEqualTo(200_000)
        val free = app.meowney.core.money.GoalEngine.freeBalances(repo.balancesNow(), repo.goalTotalsByJarNow())
        assertThat(free[ltss]).isEqualTo(200_000)
    }

    @Test fun debtsMoveWalletsAndJarsButAreNotSpending() = runBlocking {
        val nec = jar(JarCode.NEC)
        val lend = repo.createDebt("Lan", lent = true, amount = 500_000, walletId = cash(), jarId = nec, dueDay = null, note = "")
        assertThat(repo.walletStates.first().first { it.wallet.id == cash() }.balance).isEqualTo(1_500_000)
        assertThat(repo.balancesNow()[nec]).isEqualTo(8_850_000)
        repo.payDebt(lend, 200_000, cash())
        var lan = repo.debts.first().single()
        assertThat(lan.left).isEqualTo(300_000)
        assertThat(lan.settledDay).isNull()
        repo.payDebt(lend, 300_000, cash())
        lan = repo.debts.first().single()
        assertThat(lan.settledDay).isNotNull()
        assertThat(repo.balancesNow()[nec]).isEqualTo(9_350_000)
        // borrowing into "Chưa phân bổ", then deleting it, leaves no trace
        val borrow = repo.createDebt("Mẹ", lent = false, amount = 1_000_000, walletId = cash(), jarId = jar(JarCode.UNALLOC), dueDay = null, note = "")
        assertBalanced()
        repo.deleteDebt(borrow)
        assertThat(repo.balancesNow()[jar(JarCode.UNALLOC)] ?: 0).isEqualTo(0)
        // nothing here counts as spending today
        assertThat(repo.spentOn(today, listOf(nec)).first()).isEqualTo(0)
        assertBalanced()
    }

    @Test fun recurringBillAdvancesAfterPaying() = runBlocking {
        val id = repo.saveRecurring(
            app.meowney.core.db.RecurringEntity(
                name = "Tiền nhà", amount = 3_000_000, categoryId = cat("Nhà & hoá đơn"), walletId = cash(), jarId = jar(JarCode.NEC),
                rule = "MONTHLY", ruleDay = 31, nextDueDay = java.time.LocalDate.of(2027, 1, 31).toEpochDay(),
            ),
        )
        repo.advanceRecurring(id)
        assertThat(repo.recurringNow().single().nextDueDay).isEqualTo(java.time.LocalDate.of(2027, 2, 28).toEpochDay())
        repo.advanceRecurring(id)
        assertThat(repo.recurringNow().single().nextDueDay).isEqualTo(java.time.LocalDate.of(2027, 3, 31).toEpochDay())
    }

    @Test fun payingABillIsNotTodaysSpending() = runBlocking {
        val nec = jar(JarCode.NEC)
        val spendIds = listOf(nec, jar(JarCode.PLAY))
        val period = MoneyEngine.period(today, 0)
        suspend fun left(reserved: Long): Long {
            val balance = repo.balancesNow().filterKeys { it in spendIds }.values.sum()
            return MoneyEngine.allowance(today, period, balance, repo.spentOn(today, spendIds).first(), reserved).left
        }
        repo.addExpense(TxnDraft(50_000, cash(), categoryId = cat("Ăn uống"), jarId = nec, date = today), app.meowney.core.money.BorrowPlan(emptyList(), 0))
        val before = left(reserved = 3_000_000) // the rent is set aside while it is due
        repo.addExpense(
            TxnDraft(3_000_000, cash(), categoryId = cat("Nhà & hoá đơn"), jarId = nec, date = today, source = "RECURRING"),
            app.meowney.core.money.BorrowPlan(emptyList(), 0),
        )
        assertThat(repo.spentOn(today, spendIds).first()).isEqualTo(50_000)
        assertThat(left(reserved = 0)).isEqualTo(before)
        // and a bill is not offered as a one-tap expense
        assertThat(repo.frequentExpenses(today.minusDays(60), 8).first().map { it.amount }).doesNotContain(3_000_000L)
    }

    @Test fun noSpendOnlyOnceAndOnlyWithoutSpending() = runBlocking {
        assertThat(repo.claimNoSpend(today, 0)).isEqualTo(5)
        assertThat(repo.claimNoSpend(today, 0)).isEqualTo(0)
    }
}
