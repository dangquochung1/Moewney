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

    @Test fun noSpendOnlyOnceAndOnlyWithoutSpending() = runBlocking {
        assertThat(repo.claimNoSpend(today, 0)).isEqualTo(5)
        assertThat(repo.claimNoSpend(today, 0)).isEqualTo(0)
    }
}
