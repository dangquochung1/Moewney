package app.meowney.core.money

import app.meowney.core.game.RewardRules
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class MoneyEngineTest {
    private val jars = listOf(
        JarInfo(1, JarCode.NEC, 55),
        JarInfo(2, JarCode.FFA, 10),
        JarInfo(3, JarCode.LTSS, 10),
        JarInfo(4, JarCode.EDU, 10),
        JarInfo(5, JarCode.PLAY, 10),
        JarInfo(6, JarCode.GIVE, 5),
        JarInfo(7, JarCode.UNALLOC, 0),
    )

    // ------------------------------------------------------------------ split

    @Test fun splitMatchesTheOnboardingExample() {
        val shares = MoneyEngine.split(17_000_000, jars)
        assertThat(shares).containsExactly(1L, 9_350_000L, 2L, 1_700_000L, 3L, 1_700_000L, 4L, 1_700_000L, 5L, 1_700_000L, 6L, 850_000L)
    }

    @Test fun splitAlwaysAddsUpExactly() {
        val random = Random(1)
        repeat(2_000) {
            val amount = random.nextLong(0, 50_000_000_000)
            val pcts = List(6) { random.nextInt(0, 60) }
            val js = jars.take(6).mapIndexed { i, j -> j.copy(percent = pcts[i]) } + jars.last()
            assertThat(MoneyEngine.split(amount, js).values.sum()).isEqualTo(amount)
        }
    }

    @Test fun splitWithNoPercentagesGoesToUnallocated() {
        val js = jars.map { it.copy(percent = 0) }
        assertThat(MoneyEngine.split(123, js)).containsExactly(7L, 123L)
    }

    // ------------------------------------------------------------------ loans

    @Test fun loansNetBorrowAgainstRepay() {
        val rows = listOf(
            LoanRow(jarId = 5, peerJarId = 1, kind = EntryKind.BORROW, total = 300_000), // PLAY borrowed from NEC
            LoanRow(jarId = 1, peerJarId = 5, kind = EntryKind.REPAY, total = 100_000),  // NEC got 100k back
        )
        assertThat(MoneyEngine.loans(rows)).containsExactly(Loan(lenderJarId = 1, borrowerJarId = 5, amount = 200_000))
    }

    @Test fun incomeRepaysLoansBeforeTheJarKeepsItsShare() {
        val loans = listOf(Loan(lenderJarId = 1, borrowerJarId = 5, amount = 200_000))
        val entries = MoneyEngine.allocateIncome(1_000_000, jars, loans)
        assertThat(entries.sumOf { it.amount }).isEqualTo(1_000_000)
        // PLAY's share is 100k, so it can repay 100k of the 200k now.
        assertThat(entries).contains(EntryDraft(5, -100_000, EntryKind.REPAY, 1))
        assertThat(entries).contains(EntryDraft(1, 100_000, EntryKind.REPAY, 5))
        val play = entries.filter { it.jarId == 5L }.sumOf { it.amount }
        assertThat(play).isEqualTo(0)
    }

    // ------------------------------------------------------------------ borrowing

    @Test fun borrowPlanUsesUnallocatedThenRichestSpendingJar() {
        val balances = mapOf(1L to 500_000L, 2L to 900_000L, 3L to 900_000L, 4L to 50_000L, 5L to 10_000L, 6L to 0L, 7L to 30_000L)
        val shortfall = MoneyEngine.shortfall(100_000, 5, balances)
        assertThat(shortfall).isEqualTo(90_000)
        val plan = MoneyEngine.borrowPlan(shortfall, 5, balances, jars, allowSavings = false)
        assertThat(plan.sources).containsExactly(7L to 30_000L, 1L to 60_000L).inOrder()
        assertThat(plan.uncovered).isEqualTo(0)
    }

    @Test fun savingsJarsOnlyWhenAllowed() {
        val balances = mapOf(1L to 0L, 2L to 900_000L, 3L to 100_000L, 4L to 0L, 5L to 0L, 6L to 0L, 7L to 0L)
        val shortfall = MoneyEngine.shortfall(200_000, 5, balances)
        val without = MoneyEngine.borrowPlan(shortfall, 5, balances, jars, allowSavings = false)
        assertThat(without.sources).isEmpty()
        assertThat(without.uncovered).isEqualTo(200_000)
        assertThat(MoneyEngine.needsSavings(shortfall, 5, balances, jars)).isTrue()
        val with = MoneyEngine.borrowPlan(shortfall, 5, balances, jars, allowSavings = true)
        assertThat(with.sources).containsExactly(2L to 200_000L)
        assertThat(with.uncovered).isEqualTo(0)
    }

    @Test fun expenseEntriesBalanceTheBorrowedMoney() {
        val plan = BorrowPlan(listOf(7L to 30_000L, 1L to 60_000L), 0)
        val entries = MoneyEngine.expenseEntries(100_000, 5, plan)
        assertThat(entries.sumOf { it.amount }).isEqualTo(-100_000)
        assertThat(entries.filter { it.jarId == 5L }.sumOf { it.amount }).isEqualTo(-10_000)
    }

    // ------------------------------------------------------------------ period & allowance

    @Test fun calendarMonthPeriod() {
        val p = MoneyEngine.period(LocalDate.of(2026, 11, 9), payday = 0)
        assertThat(p).isEqualTo(Period(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
        assertThat(p.daysLeft(LocalDate.of(2026, 11, 9))).isEqualTo(22)
    }

    @Test fun paydayTenthPeriod() {
        assertThat(MoneyEngine.period(LocalDate.of(2026, 11, 9), payday = 10))
            .isEqualTo(Period(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 11, 9)))
        assertThat(MoneyEngine.period(LocalDate.of(2026, 11, 10), payday = 10))
            .isEqualTo(Period(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 12, 9)))
        assertThat(MoneyEngine.period(LocalDate.of(2026, 12, 31), payday = 10).end).isEqualTo(LocalDate.of(2027, 1, 9))
    }

    @Test fun allowanceSpreadsStartOfDayMoneyAndCanGoNegative() {
        val today = LocalDate.of(2026, 11, 21)
        val period = MoneyEngine.period(today, 0) // 10 days left
        val a = MoneyEngine.allowance(today, period, spendingBalanceNow = 1_450_000, spentToday = 50_000)
        assertThat(a.base).isEqualTo(150_000)
        assertThat(a.left).isEqualTo(100_000)
        val over = MoneyEngine.allowance(today, period, spendingBalanceNow = 1_300_000, spentToday = 200_000)
        assertThat(over.left).isEqualTo(-50_000)
    }

    @Test fun billsDueThisPeriodAreSetAsideFirst() {
        val today = LocalDate.of(2026, 11, 21)
        val period = MoneyEngine.period(today, 0) // 10 days left
        val plain = MoneyEngine.allowance(today, period, 4_000_000, 0)
        val withRent = MoneyEngine.allowance(today, period, 4_000_000, 0, reserved = 3_000_000)
        assertThat(plain.base).isEqualTo(400_000)
        assertThat(withRent.base).isEqualTo(100_000)
        // more bills than money: nothing to spend, never a negative base
        assertThat(MoneyEngine.allowance(today, period, 1_000_000, 0, reserved = 3_000_000).base).isEqualTo(0)
    }

    @Test fun moneyNotSpentRollsIntoTomorrow() {
        val period = MoneyEngine.period(LocalDate.of(2026, 11, 21), 0)
        val day1 = MoneyEngine.allowance(LocalDate.of(2026, 11, 21), period, 1_000_000, 0)
        // spend nothing; tomorrow the same pool is spread over one day fewer
        val day2 = MoneyEngine.allowance(LocalDate.of(2026, 11, 22), period, 1_000_000, 0)
        assertThat(day2.base).isGreaterThan(day1.base)
    }

    // ------------------------------------------------------------------ invariant

    /** A tiny in-memory ledger driven only through MoneyEngine. */
    private class Ledger(val jars: List<JarInfo>) {
        data class Txn(val id: Int, val type: String, val amount: Long, val wallet: Int, val toWallet: Int?)
        val txns = mutableListOf<Txn>()
        val entries = mutableListOf<Pair<Int?, EntryDraft>>() // txn id (null = no txn)
        var nextId = 1

        fun balances() = jars.associate { j -> j.id to entries.filter { it.second.jarId == j.id }.sumOf { it.second.amount } }
        fun loanRows() = entries.map { it.second }
            .filter { (it.kind == EntryKind.BORROW || it.kind == EntryKind.REPAY) && it.amount > 0 }
            .groupBy { Triple(it.jarId, it.peerJarId!!, it.kind) }
            .map { (k, v) -> LoanRow(k.first, k.second, k.third, v.sumOf { it.amount }) }
        fun walletTotal() = txns.sumOf {
            when (it.type) {
                "INCOME", "OPENING" -> it.amount
                "EXPENSE" -> -it.amount
                else -> 0 // transfers move money between wallets, the total stays
            }
        }
        fun add(type: String, amount: Long, wallet: Int, to: Int?, rows: List<EntryDraft>) {
            val t = Txn(nextId++, type, amount, wallet, to)
            txns += t
            rows.forEach { entries += t.id to it }
        }
        fun delete(id: Int) {
            txns.removeAll { it.id == id }
            entries.removeAll { it.first == id }
        }
    }

    @Test fun thousandRandomOperationsKeepWalletsEqualToJars() {
        val random = Random(7)
        val ledger = Ledger(jars)
        ledger.add("OPENING", 17_000_000, 0, null, MoneyEngine.split(17_000_000, jars).map { EntryDraft(it.key, it.value, EntryKind.OPENING) })
        repeat(1_000) {
            when (random.nextInt(6)) {
                0, 1 -> { // expense, borrowing if needed
                    val amount = random.nextLong(1_000, 3_000_000)
                    val jar = jars.filter { it.code.spending }.random(random).id
                    val balances = ledger.balances()
                    val plan = MoneyEngine.borrowPlan(MoneyEngine.shortfall(amount, jar, balances), jar, balances, jars, random.nextBoolean())
                    ledger.add("EXPENSE", amount, random.nextInt(3), null, MoneyEngine.expenseEntries(amount, jar, plan))
                }
                2 -> { // income repays loans first
                    val amount = random.nextLong(100_000, 20_000_000)
                    val loans = MoneyEngine.loans(ledger.loanRows())
                    ledger.add("INCOME", amount, random.nextInt(3), null, MoneyEngine.allocateIncome(amount, jars, loans))
                }
                3 -> ledger.add("TRANSFER", random.nextLong(1, 5_000_000), 0, 1, emptyList())
                4 -> { // move between jars
                    val (a, b) = jars.shuffled(random).take(2)
                    MoneyEngine.moveEntries(a.id, b.id, random.nextLong(1, 500_000)).forEach { ledger.entries += null to it }
                }
                5 -> ledger.txns.filter { it.type != "OPENING" }.randomOrNull(random)?.let { ledger.delete(it.id) }
            }
            assertThat(ledger.balances().values.sum()).isEqualTo(ledger.walletTotal())
            // outstanding loans are never negative
            MoneyEngine.loans(ledger.loanRows()).forEach { assertThat(it.amount).isGreaterThan(0) }
        }
    }

    // ------------------------------------------------------------------ keypad & rewards

    @Test fun keypad() {
        fun type(vararg keys: AmountKey) = keys.fold(AmountInput()) { a, k -> a.press(k) }.value
        assertThat(type(AmountKey.Digit(2), AmountKey.Digit(5), AmountKey.Thousand)).isEqualTo(25_000)
        assertThat(type(AmountKey.Digit(2), AmountKey.Million)).isEqualTo(2_000_000)
        assertThat(type(AmountKey.Digit(1), AmountKey.Digit(5), AmountKey.Triple0)).isEqualTo(15_000)
        assertThat(type(AmountKey.Digit(1), AmountKey.Digit(5), AmountKey.Back)).isEqualTo(1)
        assertThat(type(AmountKey.Digit(9), AmountKey.Million, AmountKey.Million)).isEqualTo(9_000_000) // would pass 12 digits
        assertThat(type(AmountKey.Digit(7), AmountKey.Clear)).isEqualTo(0)
        assertThat(type(AmountKey.Digit(0), AmountKey.Digit(0))).isEqualTo(0)
    }

    @Test fun rewardCapsAndFirstLog() {
        assertThat(RewardRules.onLog(0, firstLogToday = true).sumOf { it.delta }).isEqualTo(7)
        assertThat(RewardRules.onLog(18, firstLogToday = false).sumOf { it.delta }).isEqualTo(2)
        assertThat(RewardRules.onLog(19, firstLogToday = false).sumOf { it.delta }).isEqualTo(1)
        assertThat(RewardRules.onLog(20, firstLogToday = false)).isEmpty()
        assertThat(RewardRules.onNoSpend(alreadyClaimedToday = false, spentToday = 0)).isNotNull()
        assertThat(RewardRules.onNoSpend(alreadyClaimedToday = true, spentToday = 0)).isNull()
        assertThat(RewardRules.onNoSpend(alreadyClaimedToday = false, spentToday = 5_000)).isNull()
    }
}
