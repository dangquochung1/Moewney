package app.meowney.feature.entry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.data.TxnDraft
import app.meowney.core.db.CategoryEntity
import app.meowney.core.db.JarEntity
import app.meowney.core.db.WalletEntity
import app.meowney.core.money.AmountInput
import app.meowney.core.money.AmountKey
import app.meowney.core.money.BorrowPlan
import app.meowney.core.money.JarCode
import app.meowney.core.sound.Sfx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class EntryMode { EXPENSE, INCOME, TRANSFER }

data class EntryState(
    val loaded: Boolean = false,
    val mode: EntryMode = EntryMode.EXPENSE,
    val amount: AmountInput = AmountInput(),
    val walletId: Long? = null,
    val toWalletId: Long? = null,
    /** Chosen by hand; null = the category's jar. */
    val jarOverride: Long? = null,
    val categoryId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val editing: Long? = null,
    val wallets: List<WalletEntity> = emptyList(),
    val jars: List<JarEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val message: String? = null,
) {
    val visibleCategories get() = categories.filter { it.type == (if (mode == EntryMode.INCOME) "INCOME" else "EXPENSE") && !it.archived }
    val activeWallets get() = wallets.filter { !it.archived }
    fun walletName(id: Long?) = wallets.firstOrNull { it.id == id }?.name ?: "Ví"
    fun jarName(id: Long?) = jars.firstOrNull { it.id == id }?.name
}

/** Where the entry screen goes next. */
sealed interface EntryNav {
    data object Done : EntryNav
    data class Pour(val draft: TxnDraft, val editing: Long?) : EntryNav
}

class EntryViewModel(private val c: AppContainer, startMode: EntryMode, txnId: Long?) : ViewModel() {
    private val saver = ExpenseSaver(c)
    private val _state = MutableStateFlow(EntryState(mode = startMode, editing = txnId))
    val state: StateFlow<EntryState> = _state

    private val _pending = MutableStateFlow<PendingExpense?>(null)
    val pending: StateFlow<PendingExpense?> = _pending

    private val _nav = MutableSharedFlow<EntryNav>(extraBufferCapacity = 1)
    val nav: SharedFlow<EntryNav> = _nav

    init {
        viewModelScope.launch {
            val wallets = c.money.walletsNow()
            val jars = c.money.jarsNow()
            val cats = c.money.categoriesNow()
            val default = c.money.defaultWalletId()
            var s = EntryState(loaded = true, mode = startMode, walletId = default, wallets = wallets, jars = jars, categories = cats)
            val old = txnId?.let { c.money.txn(it) }
            if (old != null) {
                val mode = when (old.type) {
                    "INCOME" -> EntryMode.INCOME
                    "TRANSFER" -> EntryMode.TRANSFER
                    else -> EntryMode.EXPENSE
                }
                val cat = cats.firstOrNull { it.id == old.categoryId }
                s = s.copy(
                    mode = mode,
                    amount = AmountInput(old.amount),
                    walletId = old.walletId,
                    toWalletId = old.toWalletId,
                    categoryId = old.categoryId,
                    jarOverride = old.jarId?.takeIf { it != cat?.defaultJarId },
                    date = LocalDate.ofEpochDay(old.day),
                    note = old.note,
                    editing = old.id,
                )
            }
            if (s.mode == EntryMode.TRANSFER && s.toWalletId == null) {
                s = s.copy(toWalletId = wallets.firstOrNull { !it.archived && it.id != s.walletId }?.id)
            }
            _state.value = s
        }
    }

    fun key(k: AmountKey) {
        c.sfx.play(Sfx.Sound.Click, 0.3f)
        _state.update { it.copy(amount = it.amount.press(k), message = null) }
    }

    fun mode(m: EntryMode) = _state.update {
        val to = if (m == EntryMode.TRANSFER) it.toWalletId ?: it.activeWallets.firstOrNull { w -> w.id != it.walletId }?.id else it.toWalletId
        it.copy(mode = m, categoryId = null, toWalletId = to, message = null)
    }

    fun wallet(id: Long) = _state.update { it.copy(walletId = id) }
    fun toWallet(id: Long) = _state.update { it.copy(toWalletId = id) }
    fun jar(id: Long?) = _state.update { it.copy(jarOverride = id) }
    fun date(d: LocalDate) = _state.update { it.copy(date = d) }
    fun note(n: String) = _state.update { it.copy(note = n.take(80)) }

    /** Expense: save now. Income: go pour it into the jars. */
    fun category(cat: CategoryEntity) {
        val s = _state.value
        if (s.amount.value <= 0) {
            _state.update { it.copy(message = "Gõ số tiền trước nha Sen.", categoryId = cat.id) }
            return
        }
        val wallet = s.walletId ?: return
        _state.update { it.copy(categoryId = cat.id) }
        when (s.mode) {
            EntryMode.EXPENSE -> {
                val jarId = s.jarOverride ?: cat.defaultJarId ?: s.jars.first { it.code == JarCode.NEC.name }.id
                val draft = TxnDraft(s.amount.value, wallet, categoryId = cat.id, jarId = jarId, note = s.note, date = s.date)
                viewModelScope.launch {
                    val p = saver.prepare(draft, s.editing, cat.name)
                    if (p == null) {
                        saver.save(draft, BorrowPlan(emptyList(), 0), s.editing, cat.name)
                        _nav.emit(EntryNav.Done)
                    } else {
                        _pending.value = p
                    }
                }
            }
            EntryMode.INCOME -> viewModelScope.launch {
                _nav.emit(EntryNav.Pour(TxnDraft(s.amount.value, wallet, categoryId = cat.id, note = s.note, date = s.date), s.editing))
            }
            EntryMode.TRANSFER -> Unit
        }
    }

    fun confirmBorrow(plan: BorrowPlan) = viewModelScope.launch {
        val p = _pending.value ?: return@launch
        _pending.value = null
        saver.save(p.draft, plan, p.editing, p.label)
        _nav.emit(EntryNav.Done)
    }

    fun dismissBorrow() {
        _pending.value = null
    }

    fun saveTransfer() = viewModelScope.launch {
        val s = _state.value
        val from = s.walletId ?: return@launch
        val to = s.toWalletId
        when {
            s.amount.value <= 0 -> _state.update { it.copy(message = "Gõ số tiền trước nha Sen.") }
            to == null || to == from -> _state.update { it.copy(message = "Chọn 2 ví khác nhau nha.") }
            else -> {
                c.money.transfer(TxnDraft(s.amount.value, from, toWalletId = to, note = s.note, date = s.date), s.editing)
                c.sfx.play(Sfx.Sound.Click)
                _nav.emit(EntryNav.Done)
            }
        }
    }

    /** Editing an expense without changing its category. */
    fun saveEdit() {
        val s = _state.value
        when (s.mode) {
            EntryMode.TRANSFER -> saveTransfer()
            else -> s.categories.firstOrNull { it.id == s.categoryId }?.let { category(it) }
        }
    }

    fun delete() = viewModelScope.launch {
        val id = _state.value.editing ?: return@launch
        c.money.deleteTxn(id)
        _nav.emit(EntryNav.Done)
    }

    companion object {
        fun epochMillis(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
