package app.meowney.feature.entry

import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.SaveResult
import app.meowney.core.data.TxnDraft
import app.meowney.core.money.BorrowPlan
import app.meowney.core.money.MoneyEngine
import app.meowney.core.sound.Sfx

/** An expense that needs money from other jars before it can be saved. */
data class PendingExpense(
    val draft: TxnDraft,
    val editing: Long?,
    val label: String,
    val shortfall: Long,
    /** Borrowing from spending jars only (+ "Chưa phân bổ"). */
    val plan: BorrowPlan,
    /** Also using the savings jars; null when they would not help. */
    val savingsPlan: BorrowPlan?,
)

/** Saves expenses: works out borrowing, writes the ledger, tells the cat. Shared by home and entry. */
class ExpenseSaver(private val c: AppContainer) {

    /** Null when the jar can pay by itself (save right away), else what the borrow dialog shows. */
    suspend fun prepare(draft: TxnDraft, editing: Long?, label: String): PendingExpense? {
        val jarId = draft.jarId ?: return null
        val balances = c.money.balancesNow(excludingTxn = editing)
        val shortfall = MoneyEngine.shortfall(draft.amount, jarId, balances)
        if (shortfall == 0L) return null
        val jars = c.money.jarInfos()
        val plan = MoneyEngine.borrowPlan(shortfall, jarId, balances, jars, allowSavings = false)
        val savings = if (MoneyEngine.needsSavings(shortfall, jarId, balances, jars)) {
            MoneyEngine.borrowPlan(shortfall, jarId, balances, jars, allowSavings = true)
        } else {
            null
        }
        return PendingExpense(draft, editing, label, shortfall, plan, savings)
    }

    suspend fun save(draft: TxnDraft, plan: BorrowPlan, editing: Long?, label: String): SaveResult {
        val result = c.money.addExpense(draft, plan, editing)
        if (plan.sources.isNotEmpty()) c.sfx.play(Sfx.Sound.Alert, 0.5f)
        c.sfx.play(Sfx.Sound.Coin)
        if (editing == null) c.events.post(CatEvent.Fed(label, draft.amount, result.coins))
        return result
    }
}
