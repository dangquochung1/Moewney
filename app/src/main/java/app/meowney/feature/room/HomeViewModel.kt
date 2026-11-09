package app.meowney.feature.room

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.HabitResult
import app.meowney.core.data.HabitToday
import app.meowney.core.data.EventItem
import app.meowney.core.data.JarState
import app.meowney.core.data.TodoItem
import app.meowney.core.notify.CalendarReminder
import app.meowney.core.habit.HabitType
import app.meowney.core.data.TxnDraft
import app.meowney.core.db.RecurringEntity
import app.meowney.core.game.MoodEngine
import app.meowney.core.money.JarCode
import app.meowney.core.money.MoneyEngine
import app.meowney.core.data.kind
import app.meowney.core.sound.Sfx
import app.meowney.feature.entry.ExpenseSaver
import app.meowney.feature.entry.PendingExpense
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import app.meowney.core.data.NoteFull
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
    /** Recurring bills due today or overdue. */
    val billsDue: List<RecurringEntity> = emptyList(),
    /** Money set aside for bills due before the period ends. */
    val reserved: Long = 0,
    /** Days since the last backup when it is 7 or more, else 0. */
    val backupDays: Int = 0,
    /** The cat already reminded about the backup today. */
    val backupNudged: Boolean = false,
    /** Today's habits (due and not finished for the week). */
    val habits: List<HabitToday> = emptyList(),
    /** Today's events and to-dos, unfinished to-dos from past days, a birthday today. */
    val events: List<EventItem> = emptyList(),
    val todos: List<TodoItem> = emptyList(),
    val overdue: Int = 0,
    val birthday: String? = null,
    /** Today's diary: its mood (1..5) and whether something is written. */
    val diaryMood: Int? = null,
    val diaryWritten: Boolean = false,
    /** Pinned notes (checklists can be ticked from home). */
    val pinned: List<NoteFull> = emptyList(),
) {
    /** Events not over yet (all-day ones count all day). */
    fun eventsLeft(nowMinute: Int) = events.filter { e ->
        e.occ.allDay || e.occ.multiDay || e.occ.startMinute == null || (e.occ.endMinute ?: (e.occ.startMinute + 60)) > nowMinute
    }

    /** The number on the desk calendar toy. */
    fun calendarCount(nowMinute: Int) = todos.count { !it.done } + eventsLeft(nowMinute).count { !it.birthday }
}

/** Share of today's habits done, null when there are none (the tower then stands complete). */
val HomeState.habitLevel: Float?
    get() = if (habits.isEmpty()) null else habits.count { it.done }.toFloat() / habits.size

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
            // never backed up: count from the first entry
            val backupSince = settings.lastBackupDay ?: c.db.reports().firstDay() ?: day.toEpochDay()
            val backupDays = (day.toEpochDay() - backupSince).toInt().takeIf { it >= 7 } ?: 0
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
            val moneyState = combine(dayNumbers, templates, money.coins, money.loans, money.recurring) { n, t, coins, loans, bills ->
                val active = bills.filter { it.active }
                val reserved = active.filter { it.jarId in spendIds && it.nextDueDay <= period.end.toEpochDay() }.sumOf { it.amount }
                build(day, settings.catName, settings.catColor, period, jars, spendIds, n, t, coins, loans.isNotEmpty(), reserved)
                    .copy(
                        billsDue = active.filter { it.nextDueDay <= day.toEpochDay() },
                        backupDays = backupDays,
                        backupNudged = settings.lastBackupNudgeDay == day.toEpochDay(),
                    )
            }
            combine(moneyState, c.habits.today(day), c.calendar.days(day, day), c.calendar.inboxAndOverdue(day)) { state, habits, days, io ->
                val cal = days[day]
                state.copy(
                    habits = habits,
                    events = cal?.events.orEmpty(),
                    todos = cal?.todos.orEmpty(),
                    overdue = io.second.size,
                    birthday = cal?.events?.firstOrNull { it.birthday && it.occ.start == day }?.event?.title,
                )
            }.let { withCal ->
                val diary = c.notes.diaries.map { l -> l.firstOrNull { it.note.day == day.toEpochDay() } }
                combine(withCal, diary, c.notes.pinned) { state, page, pinned ->
                    state.copy(diaryMood = page?.note?.mood, diaryWritten = page?.hasContent == true, pinned = pinned.take(3))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        // close the habit days that ended; the cat sums them up
        viewModelScope.launch {
            var first = true
            today.collect { day ->
                val summary = c.habits.closeThrough(day.minusDays(1))
                if (!summary.isEmpty) {
                    if (first) delay(3_000) // let the wake-up intro finish first
                    c.events.post(CatEvent.Coins(summary.coins, CatLines.habitSummary(summary)))
                }
                first = false
            }
        }
        // remember today's limit for the monthly report card
        viewModelScope.launch {
            state.collect { s ->
                if (s.ready) c.reports.recordDay(LocalDate.now(), s.leftToday + s.spentToday, s.spentToday, s.spentToday > 0)
            }
        }
    }

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
        reserved: Long,
    ): HomeState {
        val spendingBalance = jars.filter { it.jar.id in spendIds }.sumOf { it.balance }
        val allowance = MoneyEngine.allowance(day, period, spendingBalance, n.spent, reserved)
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
            reserved = reserved,
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
        pendingBill?.let { money.advanceRecurring(it) }
        pendingBill = null
    }

    private var pendingBill: Long? = null

    /** "Đã trả" on a recurring bill: log it (borrowing if needed), then move it to its next date. */
    fun payBill(bill: RecurringEntity, amount: Long) = viewModelScope.launch {
        val cat = money.categoriesNow().firstOrNull { it.id == bill.categoryId }
        val draft = TxnDraft(amount, bill.walletId, categoryId = bill.categoryId, jarId = bill.jarId ?: cat?.defaultJarId, note = bill.name, date = LocalDate.now(), source = "RECURRING")
        val pending = saver.prepare(draft, editing = null, label = bill.name)
        if (pending == null) {
            saver.save(draft, app.meowney.core.money.BorrowPlan(emptyList(), 0), null, bill.name)
            money.advanceRecurring(bill.id)
        } else {
            pendingBill = bill.id
            _pending.value = pending
        }
    }

    fun skipBill(bill: RecurringEntity) = viewModelScope.launch { money.advanceRecurring(bill.id) }

    fun dismissBorrow() {
        _pending.value = null
        pendingBill = null
    }

    fun togglePin(t: QuickTemplate) = viewModelScope.launch {
        if (t.pinned && t.id != null) money.unpinTemplate(t.id) else money.pinTemplate(t.label, t.amount, t.categoryId)
    }

    // ------------------------------------------------------------------ habits

    /** Có/không toggles, Đếm số lượng adds one. */
    fun habitTap(h: HabitToday) = viewModelScope.launch {
        val r = when (h.habit.kind) {
            HabitType.CHECK -> c.habits.toggle(h.habit.id)
            HabitType.COUNT -> c.habits.count(h.habit.id)
            else -> return@launch
        }
        celebrate(h, r)
    }

    fun habitCount(h: HabitToday, value: Int) = viewModelScope.launch { celebrate(h, c.habits.count(h.habit.id, value = value)) }

    fun habitFailed(h: HabitToday, failed: Boolean) = viewModelScope.launch {
        c.habits.setFailed(h.habit.id, failed)
        if (failed) c.events.post(CatEvent.Say(CatLines.habitSlip(h.habit.name)))
    }

    private fun celebrate(h: HabitToday, r: HabitResult) {
        if (r.coins <= 0 && r.notes.isEmpty()) return
        c.sfx.play(if (r.allDone) Sfx.Sound.Success else Sfx.Sound.Coin)
        c.events.post(CatEvent.Coins(r.coins, CatLines.habitDone(h.habit.name, r)))
    }

    fun toggleCheck(blockId: Long) = viewModelScope.launch { c.notes.toggleCheck(blockId) }

    fun todoToggle(t: TodoItem) = viewModelScope.launch {
        val coins = c.calendar.toggleTodo(t.todo.id)
        if (coins > 0) {
            c.sfx.play(Sfx.Sound.Coin)
            c.events.post(CatEvent.Coins(coins, "Xong \"${t.todo.title}\" rồi! Trẫm vờn len ăn mừng. +$coins xu"))
        }
        CalendarReminder.schedule(c.context)
    }

    fun markBackupNudge() = viewModelScope.launch { c.settings.markBackupNudge(LocalDate.now().toEpochDay()) }

    fun claimNoSpend() = viewModelScope.launch {
        val coins = money.claimNoSpend(LocalDate.now(), state.value.spentToday)
        if (coins > 0) {
            c.sfx.play(Sfx.Sound.Success)
            c.events.post(CatEvent.Coins(coins, "Không tiêu đồng nào? Giỏi lắm Sen! +$coins xu"))
        }
    }
}
