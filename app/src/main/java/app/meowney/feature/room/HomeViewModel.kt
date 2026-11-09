package app.meowney.feature.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.JarState
import app.meowney.core.data.TxnDraft
import app.meowney.core.game.MoodEngine
import app.meowney.core.money.JarCode
import app.meowney.core.money.MoneyEngine
import app.meowney.core.sound.Sfx
import app.meowney.feature.entry.ExpenseSaver
import app.meowney.feature.entry.PendingExpense
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A one-tap expense: pinned ([id] set) or suggested from history. */
data class QuickTemplate(val id: Long?, val label: String, val amount: Long, val categoryId: Long, val pinned: Boolean)

data class HomeState(
    val ready: Boolean = false,
    val catName: String = "Mít",
    val catColor: Int = 5,
    val coins: Long = 0,
    val leftToday: Long = 0,
    val spentToday: Long = 0,
    val jarLevel: Float = 0f,
    val mood: Mood = Mood.Calm,
    val hasLoans: Boolean = false,
    val canClaimNoSpend: Boolean = false,
    val templates: List<QuickTemplate> = emptyList(),
    val jarNames: Map<Long, String> = emptyMap(),
)

private data class DayNumbers(val spent: Long, val count: Int, val noSpendClaims: Int, val allocated: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val money = c.money
    private val saver = ExpenseSaver(c)

    /** Today's date, re-checked every minute so the screen rolls over at midnight. */
    private val today = flow {
        while (true) {
            emit(LocalDate.now())
            delay(60_000)
        }
    }.distinctUntilChanged()

    private val _pending = MutableStateFlow<PendingExpense?>(null)
    val pending: StateFlow<PendingExpense?> = _pending

    val state: StateFlow<HomeState> = combine(today, c.settings.flow, money.jarStates) { d, s, jars -> Triple(d, s, jars) }
        .flatMapLatest { (day, settings, jars) ->
            val spendIds = jars.filter { it.code == JarCode.NEC || it.code == JarCode.PLAY }.map { it.jar.id }
            val period = MoneyEngine.period(day, settings.payday)
            val dayNumbers = combine(
                money.spentOn(day, spendIds),
                money.countOn(day),
                money.noSpendClaims(day),
                money.allocatedSince(period.start, spendIds),
            ) { spent, count, claims, allocated -> DayNumbers(spent, count, claims, allocated) }
            val templates = combine(money.templates, money.frequentExpenses(day.minusDays(60), 8), money.categories) { pinned, frequent, cats ->
                val names = cats.associate { it.id to it.name }
                val list = pinned.map { QuickTemplate(it.id, it.label, it.amount, it.categoryId, true) }.toMutableList()
                for (f in frequent) {
                    if (list.size >= 6) break
                    if (list.none { it.categoryId == f.categoryId && it.amount == f.amount }) {
                        list += QuickTemplate(null, names[f.categoryId] ?: "Chi", f.amount, f.categoryId, false)
                    }
                }
                list
            }
            combine(dayNumbers, templates, money.coins, money.loans) { n, t, coins, loans ->
                build(day, settings.catName, settings.catColor, period, jars, spendIds, n, t, coins, loans.isNotEmpty())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private fun build(
        day: LocalDate,
        catName: String,
        catColor: Int,
        period: app.meowney.core.money.Period,
        jars: List<JarState>,
        spendIds: List<Long>,
        n: DayNumbers,
        templates: List<QuickTemplate>,
        coins: Long,
        hasLoans: Boolean,
    ): HomeState {
        val spendingBalance = jars.filter { it.jar.id in spendIds }.sumOf { it.balance }
        val allowance = MoneyEngine.allowance(day, period, spendingBalance, n.spent)
        val full = maxOf(n.allocated, spendingBalance, 1)
        return HomeState(
            ready = true,
            catName = catName,
            catColor = catColor,
            coins = coins,
            leftToday = allowance.left,
            spentToday = n.spent,
            jarLevel = (spendingBalance.toFloat() / full).coerceIn(0f, 1f),
            mood = MoodEngine.mood(allowance.left, hasLoans, n.count > 0),
            hasLoans = hasLoans,
            canClaimNoSpend = n.spent == 0L && n.noSpendClaims == 0,
            templates = templates,
            jarNames = jars.associate { it.jar.id to it.jar.name },
        )
    }

    // ------------------------------------------------------------------ actions

    fun quickLog(t: QuickTemplate) = viewModelScope.launch {
        val cats = money.categoriesNow()
        val cat = cats.firstOrNull { it.id == t.categoryId } ?: return@launch
        val wallet = money.defaultWalletId() ?: return@launch
        val draft = TxnDraft(t.amount, wallet, categoryId = cat.id, jarId = cat.defaultJarId, date = LocalDate.now(), source = "TEMPLATE")
        val pending = saver.prepare(draft, editing = null, label = t.label)
        if (pending == null) saver.save(draft, app.meowney.core.money.BorrowPlan(emptyList(), 0), null, t.label) else _pending.value = pending
    }

    fun confirmBorrow(plan: app.meowney.core.money.BorrowPlan) = viewModelScope.launch {
        val p = _pending.value ?: return@launch
        _pending.value = null
        saver.save(p.draft, plan, p.editing, p.label)
    }

    fun dismissBorrow() {
        _pending.value = null
    }

    fun togglePin(t: QuickTemplate) = viewModelScope.launch {
        if (t.pinned && t.id != null) money.unpinTemplate(t.id) else money.pinTemplate(t.label, t.amount, t.categoryId)
    }

    fun claimNoSpend() = viewModelScope.launch {
        val coins = money.claimNoSpend(LocalDate.now(), state.value.spentToday)
        if (coins > 0) {
            c.sfx.play(Sfx.Sound.Success)
            c.events.post(CatEvent.Coins(coins, "Không tiêu đồng nào? Giỏi lắm Sen! +$coins xu"))
        }
    }
}
