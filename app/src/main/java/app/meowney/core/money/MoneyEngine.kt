package app.meowney.core.money

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The 6 jars of the JARS method plus the virtual "not yet allocated" jar. */
enum class JarCode(val isSavings: Boolean, val spending: Boolean) {
    NEC(false, true),      // Thiết yếu
    FFA(true, false),      // Tự do tài chính
    LTSS(true, false),     // Tiết kiệm dài hạn
    EDU(false, true),      // Giáo dục
    PLAY(false, true),     // Hưởng thụ
    GIVE(false, true),     // Cho đi
    UNALLOC(false, false), // Chưa phân bổ
}

/** Why a jar balance moved. Every row of the jar ledger has one. */
enum class EntryKind { OPENING, ALLOC, SPEND, MOVE, BORROW, REPAY, ADJUST }

data class JarInfo(val id: Long, val code: JarCode, val percent: Int)

/** One row to write into the jar ledger. Amount is signed. */
data class EntryDraft(val jarId: Long, val amount: Long, val kind: EntryKind, val peerJarId: Long? = null)

/** [borrowerJarId] still owes [lenderJarId] this much. */
data class Loan(val lenderJarId: Long, val borrowerJarId: Long, val amount: Long)

/** Positive sums of BORROW / REPAY ledger rows, grouped by (jar, peer, kind). */
data class LoanRow(val jarId: Long, val peerJarId: Long, val kind: EntryKind, val total: Long)

/** Where the missing money for an expense comes from. */
data class BorrowPlan(val sources: List<Pair<Long, Long>>, val uncovered: Long) {
    val total get() = sources.sumOf { it.second }
}

/** The pay period "hôm nay còn" spreads money over; [end] is inclusive. */
data class Period(val start: LocalDate, val end: LocalDate) {
    fun daysLeft(today: LocalDate): Long = (ChronoUnit.DAYS.between(today, end) + 1).coerceAtLeast(1)
}

data class Allowance(val base: Long, val spentToday: Long) {
    val left get() = base - spentToday
}

/**
 * The money rules, free of Android and the database so they can be tested hard.
 *
 * Model: wallets say where money is, jars say what it is for. Wallet balances are the sum
 * of transactions, jar balances the sum of ledger rows; every operation keeps
 * Σ wallets == Σ jars.
 */
object MoneyEngine {

    /**
     * Splits [amount] by the jars' percentages, rounding each share down to the đồng;
     * the remainder goes to the jar with the biggest percentage (Thiết yếu), so the
     * shares always add up to exactly [amount].
     */
    fun split(amount: Long, jars: List<JarInfo>): Map<Long, Long> {
        val parts = jars.filter { it.code != JarCode.UNALLOC && it.percent > 0 }
        if (parts.isEmpty()) return jars.firstOrNull { it.code == JarCode.UNALLOC }?.let { mapOf(it.id to amount) } ?: emptyMap()
        val totalPct = parts.sumOf { it.percent }
        val shares = parts.associate { it.id to amount * it.percent / totalPct }.toMutableMap()
        val rest = amount - shares.values.sum()
        val biggest = parts.maxBy { it.percent }.id
        shares[biggest] = shares.getValue(biggest) + rest
        return shares
    }

    /** Outstanding loans between jars from the ledger sums. */
    fun loans(rows: List<LoanRow>): List<Loan> {
        // BORROW row on the borrower: +x with peer = lender. REPAY row on the lender: +x with peer = borrower.
        val borrowed = rows.filter { it.kind == EntryKind.BORROW }
            .groupBy { it.peerJarId to it.jarId }.mapValues { (_, v) -> v.sumOf { it.total } }
        val repaid = rows.filter { it.kind == EntryKind.REPAY }
            .groupBy { it.jarId to it.peerJarId }.mapValues { (_, v) -> v.sumOf { it.total } }
        return borrowed.mapNotNull { (key, amount) ->
            val owed = amount - (repaid[key] ?: 0)
            if (owed > 0) Loan(lenderJarId = key.first, borrowerJarId = key.second, amount = owed) else null
        }.sortedWith(compareBy({ it.borrowerJarId }, { it.lenderJarId }))
    }

    /**
     * Ledger rows for an income: split by [shares] (or the jars' %), then each borrowing
     * jar pays its lenders back out of its own share before keeping the rest.
     */
    fun allocateIncome(
        amount: Long,
        jars: List<JarInfo>,
        loans: List<Loan>,
        shares: Map<Long, Long> = split(amount, jars),
    ): List<EntryDraft> {
        require(shares.values.sum() == amount) { "shares must add up to the income" }
        val out = shares.filterValues { it != 0L }.map { (jar, v) -> EntryDraft(jar, v, EntryKind.ALLOC) }.toMutableList()
        val available = shares.toMutableMap()
        for (loan in loans) {
            val have = available[loan.borrowerJarId] ?: 0
            val pay = minOf(have, loan.amount)
            if (pay <= 0) continue
            available[loan.borrowerJarId] = have - pay
            out += EntryDraft(loan.borrowerJarId, -pay, EntryKind.REPAY, loan.lenderJarId)
            out += EntryDraft(loan.lenderJarId, pay, EntryKind.REPAY, loan.borrowerJarId)
        }
        return out
    }

    /** How much of [needJarId]'s expense it cannot pay itself. */
    fun shortfall(amount: Long, needJarId: Long, balances: Map<Long, Long>): Long =
        (amount - (balances[needJarId] ?: 0).coerceAtLeast(0)).coerceAtLeast(0)

    /**
     * Who lends: "Chưa phân bổ" first, then the other spending jars, richest first, and the
     * savings jars only when [allowSavings]. Whatever no jar can cover stays [BorrowPlan.uncovered]
     * (the jar goes negative).
     */
    fun borrowPlan(
        shortfall: Long,
        needJarId: Long,
        balances: Map<Long, Long>,
        jars: List<JarInfo>,
        allowSavings: Boolean,
    ): BorrowPlan {
        if (shortfall <= 0) return BorrowPlan(emptyList(), 0)
        val candidates = jars.filter { it.id != needJarId && (balances[it.id] ?: 0) > 0 }
        val unalloc = candidates.filter { it.code == JarCode.UNALLOC }
        val spending = candidates.filter { it.code.spending }.sortedByDescending { balances[it.id] ?: 0 }
        val savings = if (allowSavings) candidates.filter { it.code.isSavings }.sortedByDescending { balances[it.id] ?: 0 } else emptyList()
        var need = shortfall
        val sources = mutableListOf<Pair<Long, Long>>()
        for (jar in unalloc + spending + savings) {
            if (need == 0L) break
            val take = minOf(need, balances[jar.id] ?: 0)
            sources += jar.id to take
            need -= take
        }
        return BorrowPlan(sources, need)
    }

    /** True if covering [shortfall] without savings jars is impossible but with them it is (more) possible. */
    fun needsSavings(shortfall: Long, needJarId: Long, balances: Map<Long, Long>, jars: List<JarInfo>): Boolean {
        val without = borrowPlan(shortfall, needJarId, balances, jars, allowSavings = false)
        if (without.uncovered == 0L) return false
        val with = borrowPlan(shortfall, needJarId, balances, jars, allowSavings = true)
        return with.uncovered < without.uncovered
    }

    /** Ledger rows for an expense of [amount] from [jarId], borrowing per [plan] first. */
    fun expenseEntries(amount: Long, jarId: Long, plan: BorrowPlan): List<EntryDraft> {
        val out = mutableListOf<EntryDraft>()
        for ((source, v) in plan.sources) {
            if (v <= 0) continue
            out += EntryDraft(source, -v, EntryKind.BORROW, jarId)
            out += EntryDraft(jarId, v, EntryKind.BORROW, source)
        }
        out += EntryDraft(jarId, -amount, EntryKind.SPEND)
        return out
    }

    /** Moving money between two jars by hand. */
    fun moveEntries(from: Long, to: Long, amount: Long) = listOf(
        EntryDraft(from, -amount, EntryKind.MOVE, to),
        EntryDraft(to, amount, EntryKind.MOVE, from),
    )

    /**
     * The pay period containing [today]. [payday] 0 = calendar month; 1..28 = the period ends
     * the day before that day of the month.
     */
    fun period(today: LocalDate, payday: Int): Period {
        if (payday !in 1..28) {
            return Period(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
        }
        val thisMonthPay = today.withDayOfMonth(payday)
        return if (today >= thisMonthPay) {
            Period(thisMonthPay, thisMonthPay.plusMonths(1).minusDays(1))
        } else {
            Period(thisMonthPay.minusMonths(1), thisMonthPay.minusDays(1))
        }
    }

    /**
     * "Hôm nay còn được tiêu": what the spending jars held at the start of today (their
     * balance now plus what was spent today), spread over the days left in the period.
     * Money not spent today is still in the jars, so it rolls into tomorrow by itself.
     */
    fun allowance(today: LocalDate, period: Period, spendingBalanceNow: Long, spentToday: Long, reserved: Long = 0): Allowance {
        val pool = spendingBalanceNow + spentToday - reserved
        val base = (pool / period.daysLeft(today)).coerceAtLeast(0)
        return Allowance(base, spentToday)
    }
}
