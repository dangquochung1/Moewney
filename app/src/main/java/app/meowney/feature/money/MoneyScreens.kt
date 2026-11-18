package app.meowney.feature.money

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import app.meowney.core.ui.IconChip
import app.meowney.core.ui.RoundIconButton
import app.meowney.core.ui.SegmentedTabs
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.data.JarState
import app.meowney.core.data.WalletState
import app.meowney.core.db.GoalEntity
import app.meowney.core.db.GoalRow
import app.meowney.core.db.JarEntity
import app.meowney.core.db.JarEntryEntity
import app.meowney.core.db.TxnRow
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.money.AmountInput
import app.meowney.core.money.JarCode
import app.meowney.core.money.Loan
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelKeypad
import app.meowney.core.ui.CardTone
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.RavenIcon
import app.meowney.feature.entry.dayLabel
import app.meowney.feature.entry.jarColor
import app.meowney.feature.room.ToyArt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// ------------------------------------------------------------------ money screen

data class MoneyState(
    val jars: List<JarState> = emptyList(),
    val loans: List<Loan> = emptyList(),
    val txns: List<TxnRow> = emptyList(),
    val goals: List<GoalRow> = emptyList(),
    val wallets: List<WalletState> = emptyList(),
    /** False until the first read, so an empty book is not shown while it loads. */
    val loaded: Boolean = false,
)

class MoneyViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<MoneyState> = combine(c.money.jarStates, c.money.loans, c.money.recentTxns(400), c.money.goals, c.money.walletStates) { j, l, t, g, w ->
        MoneyState(j, l, t, g, w.filter { !it.wallet.archived }, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoneyState())

    fun saveGoal(g: GoalEntity) = viewModelScope.launch { c.money.saveGoal(g) }

    fun saveJars(jars: List<JarEntity>) = viewModelScope.launch {
        jars.forEach { c.money.updateJar(it) }
    }
}

/** [filter]: 0 = everything, 1 = expenses, 2 = income, 3 = transfers. [query] matches the note or the category. */
internal fun filterTxns(txns: List<TxnRow>, filter: Int, query: String): List<TxnRow> {
    val q = app.meowney.core.notes.NoteText.fold(query)
    return txns.filter { t ->
        val kind = when (filter) {
            1 -> t.type == "EXPENSE"
            2 -> t.type == "INCOME"
            3 -> t.type == "TRANSFER"
            else -> true
        }
        kind && (q.isEmpty() || app.meowney.core.notes.NoteText.fold(t.note + " " + (t.categoryName ?: "")).contains(q))
    }
}

/** Everything Sen has, across the wallets in use. */
@Composable
private fun TotalCard(wallets: List<WalletState>) {
    val type = LocalPixelType.current
    PixelPanel(Modifier.fillMaxWidth()) {
        Column {
            PixelText("TỔNG TÀI SẢN", style = type.caption, color = Pal.InkSoft)
            PixelText(formatVnd(wallets.sumOf { it.balance }), style = type.display, color = Pal.Ink)
            if (wallets.isNotEmpty()) {
                PixelText(wallets.joinToString(" · ") { "${it.wallet.name} ${formatVndShort(it.balance)}" }, style = type.caption, color = Pal.InkSoft, maxLines = 2)
            }
        }
    }
}

private const val TRANSFER_ICON = 9 // floppy with arrow: money moving between wallets
private const val HABIT_MOVE_ICON = 11 // star: a habit's reward moving between jars

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoneyScreen(
    vm: MoneyViewModel,
    typeface: Typeface,
    onBack: () -> Unit,
    onJar: (Long) -> Unit,
    onWallets: () -> Unit,
    onTxn: (Long) -> Unit,
    onAdd: () -> Unit,
    onGoal: (Long) -> Unit,
    onDebts: () -> Unit,
    onRecurring: () -> Unit,
    onReport: () -> Unit,
) {
    val s by vm.state.collectAsState()
    var newGoal by remember { mutableStateOf(false) }
    var editingJars by remember { mutableStateOf(false) }
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val names = s.jars.associate { it.jar.id to it.jar.name }
    var filter by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    val days = remember(s.txns, filter, query) { filterTxns(s.txns, filter, query).groupBy { it.day }.toList() }

    PixelScreen("Tiền", onBack = null, actions = {
        RoundIconButton(Icons.Filled.Add, "Ghi sổ", onAdd, primary = true)
    }) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 4), contentPadding = PaddingValues(bottom = m.unit * 6)) {
            if (!s.loaded) return@LazyColumn
            item { TotalCard(s.wallets) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelChip("Định kỳ", onClick = onRecurring)
                    PixelChip("Nợ", onClick = onDebts)
                    PixelChip("Ví", onClick = onWallets)
                    PixelChip("Tổng kết", onClick = onReport)
                }
            }
            item {
                JarShelf(s.jars.filter { it.code != JarCode.UNALLOC }, typeface, onJar)
                PixelTextButton(
                    "Sửa các lọ", onClick = { editingJars = true }, look = ButtonLook.Plain,
                    modifier = Modifier.fillMaxWidth().padding(top = m.unit * 2),
                )
            }
            item {
                val unalloc = s.jars.firstOrNull { it.code == JarCode.UNALLOC }
                if (unalloc != null) {
                    Row(Modifier.fillMaxWidth().clickable { onJar(unalloc.jar.id) }.padding(vertical = m.unit)) {
                        PixelText("Chưa phân bổ", style = type.body, color = Pal.InkSoft, modifier = Modifier.weight(1f))
                        PixelText(formatVnd(unalloc.balance), style = type.bodyBold, color = Pal.InkSoft)
                    }
                }
                for (l in s.loans) {
                    PixelText(
                        "${names[l.borrowerJarId]} đang nợ ${names[l.lenderJarId]} ${formatVndShort(l.amount)}",
                        style = type.caption, color = Pal.GoldDeep,
                    )
                }
            }
            item { GoalsSection(s.goals, s.jars, onGoal = onGoal, onNew = { newGoal = true }) }
            if (s.txns.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                    SegmentedTabs(listOf("Tất cả", "Chi", "Thu", "Chuyển"), filter) { filter = it }
                    PixelTextField(query, { query = it.take(40) }, Modifier.fillMaxWidth(), placeholder = "Tìm theo ghi chú hoặc danh mục")
                }
            }
            if (days.isEmpty()) {
                item {
                    PixelText(
                        if (s.txns.isEmpty()) "Chưa có giao dịch nào. Bấm + để ghi khoản đầu tiên." else "Không có giao dịch nào khớp.",
                        style = type.body, color = Pal.InkSoft,
                    )
                }
            }
            for ((day, rows) in days) {
                item(key = "d$day") {
                    val inc = rows.filter { it.type == "INCOME" }.sumOf { it.amount }
                    val exp = rows.filter { it.type == "EXPENSE" }.sumOf { it.amount }
                    Row(Modifier.fillMaxWidth().padding(top = m.unit * 2), verticalAlignment = Alignment.CenterVertically) {
                        PixelText(dayLabel(LocalDate.ofEpochDay(day)).uppercase(), style = type.bodyBold, color = Pal.GoldDeep, modifier = Modifier.weight(1f))
                        if (inc > 0) PixelText("+${formatVndShort(inc)}  ", style = type.caption, color = Pal.GreenDeep)
                        if (exp > 0) PixelText("-${formatVndShort(exp)}", style = type.caption, color = Pal.Ink)
                    }
                }
                items(rows, key = { it.id }) { t -> TxnLine(t, onClick = {
                    when {
                        t.type.startsWith("DEBT") -> onDebts()
                        t.type == "JAR_MOVE" -> Unit // undone by unticking the habit today
                        else -> onTxn(t.id)
                    }
                }) }
            }
        }
    }

    if (editingJars) {
        JarsEditDialog(
            jars = s.jars,
            onSave = { vm.saveJars(it); editingJars = false },
            onDismiss = { editingJars = false },
        )
    }
    if (newGoal) {
        GoalEditDialog(
            initial = null,
            jars = s.jars.filter { it.code != JarCode.UNALLOC },
            onSave = { vm.saveGoal(it); newGoal = false },
            onDismiss = { newGoal = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JarsEditDialog(jars: List<JarState>, onSave: (List<JarEntity>) -> Unit, onDismiss: () -> Unit) {
    val editable = jars.filter { it.code != JarCode.UNALLOC }
    var names by remember { mutableStateOf(editable.associate { it.jar.id to it.jar.name }) }
    var percents by remember { mutableStateOf(editable.associate { it.jar.id to it.jar.percent }) }
    val sum = percents.values.sum()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelDialog(onDismiss) {
        PixelText("Sửa các lọ", style = type.title)
        PixelText(
            "Tổng: $sum%" + if (sum != 100) " (phải đúng 100%)" else "",
            style = type.caption,
            color = if (sum == 100) Pal.GreenDeep else Pal.Red,
        )
        Column(
            Modifier.heightIn(max = m.unit * 110).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        ) {
            for (j in editable) {
                val pct = percents[j.jar.id] ?: 0
                PixelTextField(names[j.jar.id] ?: "", { names = names + (j.jar.id to it.take(20)) }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelText(names[j.jar.id].orEmpty(), style = type.caption, color = Pal.InkSoft, modifier = Modifier.weight(1f), maxLines = 1)
                    PixelTextButton("-", onClick = { percents = percents + (j.jar.id to (pct - 5).coerceAtLeast(0)) }, look = ButtonLook.Plain)
                    PixelText("$pct%", style = type.bodyBold)
                    PixelTextButton("+", onClick = { percents = percents + (j.jar.id to (pct + 5).coerceAtMost(100)) }, look = ButtonLook.Plain)
                }
            }
        }
        PixelTextButton(
            "Lưu",
            enabled = sum == 100 && names.values.all { it.isNotBlank() },
            onClick = {
                onSave(editable.map { it.jar.copy(name = names.getValue(it.jar.id).trim(), percent = percents.getValue(it.jar.id)) })
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JarShelf(jars: List<JarState>, typeface: Typeface, onJar: (Long) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val top = maxOf(jars.maxOfOrNull { it.balance } ?: 1, 1)
    val scale = (m.ui - 1).coerceAtLeast(2)
    PixelPanel(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, maxItemsInEachRow = 3) {
            for (j in jars) {
                val level = ((j.balance.toFloat() / top) * 16).toInt().coerceIn(0, 16) / 16f
                val negative = j.balance < 0
                val image = remember(j.code, level, negative, typeface) {
                    ToyArt.jarImage(level, typeface, jarColor(j.code), label = if (negative) "!" else "", empty = negative)
                }
                Column(
                    Modifier.weight(1f).clickable { onJar(j.jar.id) }.padding(m.unit),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.size(m.dp(image.width * scale), m.dp(image.height * scale))) {
                        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
                    }
                    PixelText(j.jar.name, style = type.caption, color = Pal.Ink, align = TextAlign.Center, maxLines = 1)
                    PixelText(formatVndShort(j.balance), style = type.bodyBold, color = if (negative) Pal.Red else Pal.Ink)
                }
            }
        }
    }
}

@Composable
private fun TxnLine(t: TxnRow, onClick: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = m.unit),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
    ) {
        IconChip(
            when (t.type) {
                "TRANSFER" -> TRANSFER_ICON
                "JAR_MOVE" -> HABIT_MOVE_ICON
                else -> t.categoryIcon ?: 12
            },
            tint = if (t.type == "INCOME") Pal.GoldTint else if (t.type == "EXPENSE") Pal.GreenTint else Pal.SkyTint,
        )
        Column(Modifier.weight(1f)) {
            val title = when (t.type) {
                "TRANSFER" -> "Chuyển ${t.walletName} > ${t.toWalletName}"
                "ADJUST" -> "Điều chỉnh ${t.walletName}"
                "DEBT_OUT" -> if (t.source == "DEBT") "Cho ${t.debtPerson} vay" else "Trả nợ ${t.debtPerson}"
                "DEBT_IN" -> if (t.source == "DEBT") "Vay của ${t.debtPerson}" else "${t.debtPerson} trả nợ"
                "JAR_MOVE" -> "Thói quen: ${t.note}"
                else -> t.categoryName ?: "Khác"
            }
            PixelText(title, style = type.body, color = Pal.Ink, maxLines = 1)
            val sub = listOfNotNull(
                t.note.takeIf { it.isNotBlank() && t.type != "JAR_MOVE" },
                "chuyển lọ, ví không đổi".takeIf { t.type == "JAR_MOVE" },
                t.walletName.takeIf { t.type == "EXPENSE" || t.type == "INCOME" },
                t.jarName.takeIf { t.type == "EXPENSE" },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) PixelText(sub, style = type.caption, color = Pal.InkSoft, maxLines = 1)
        }
        val (sign, color) = when (t.type) {
            "INCOME" -> "+" to Pal.GreenDeep
            "EXPENSE" -> "-" to Pal.Ink
            "ADJUST" -> (if (t.amount >= 0) "+" else "") to Pal.InkSoft
            else -> "" to Pal.SkyDeep
        }
        PixelText(sign + formatVnd(t.amount), style = type.bodyBold, color = color)
    }
}

// ------------------------------------------------------------------ jar detail

class JarDetailViewModel(private val c: AppContainer, val jarId: Long) : ViewModel() {
    val jars = c.money.jarStates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val entries = c.money.entriesOfJar(jarId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun move(to: Long, amount: Long) = viewModelScope.launch { c.money.moveBetweenJars(jarId, to, amount) }
}

private fun kindLabel(e: JarEntryEntity) = when (e.kind) {
    "OPENING" -> "Số dư ban đầu"
    "ALLOC" -> "Rót từ khoản thu"
    "SPEND" -> "Chi tiêu"
    "MOVE" -> if (e.amount > 0) "Chuyển vào" else "Chuyển đi"
    "BORROW" -> if (e.amount > 0) "Mượn vào" else "Cho mượn"
    "REPAY" -> if (e.amount > 0) "Được trả nợ" else "Trả nợ"
    "ADJUST" -> "Điều chỉnh ví"
    "DEBT" -> if (e.amount > 0) "Tiền vay / được trả" else "Cho vay / trả nợ"
    else -> e.kind
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JarDetailScreen(vm: JarDetailViewModel, onBack: () -> Unit) {
    val jars by vm.jars.collectAsState()
    val entries by vm.entries.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val jar = jars.firstOrNull { it.jar.id == vm.jarId } ?: return
    var moving by remember { mutableStateOf(false) }

    PixelScreen(jar.jar.name, onBack = onBack) {
        PixelPanel(Modifier.fillMaxWidth()) {
            Column {
                PixelText(formatVnd(jar.balance), style = type.display, color = if (jar.balance < 0) Pal.Red else Pal.Ink)
                if (jar.code != JarCode.UNALLOC) PixelText("Nhận ${jar.jar.percent}% mỗi khoản thu", style = type.caption, color = Pal.InkSoft)
            }
        }
        PixelTextButton("Chuyển sang lọ khác", onClick = { moving = true }, look = ButtonLook.Plain, modifier = Modifier.fillMaxWidth().padding(vertical = m.unit * 2))
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = m.unit)) {
                    Column(Modifier.weight(1f)) {
                        PixelText(kindLabel(e), style = type.body, color = Pal.Ink)
                        PixelText(dayLabel(LocalDate.ofEpochDay(e.day)), style = type.caption, color = Pal.InkSoft)
                    }
                    PixelText((if (e.amount > 0) "+" else "") + formatVnd(e.amount), style = type.bodyBold, color = if (e.amount >= 0) Pal.GreenDeep else Pal.Ink)
                }
            }
        }
    }

    if (moving) {
        var amount by remember { mutableStateOf(AmountInput()) }
        var target by remember { mutableStateOf<Long?>(null) }
        PixelDialog(onDismiss = { moving = false }) {
            PixelText("Chuyển từ ${jar.jar.name}", style = type.title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (j in jars.filter { it.jar.id != vm.jarId }) PixelChip(j.jar.name, selected = target == j.jar.id) { target = j.jar.id }
            }
            PixelText(formatVnd(amount.value), style = type.display, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
            PixelKeypad(onKey = { amount = amount.press(it) })
            PixelTextButton("Chuyển", enabled = target != null && amount.value > 0, onClick = {
                vm.move(target!!, amount.value)
                moving = false
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ wallets

class WalletsViewModel(private val c: AppContainer) : ViewModel() {
    val wallets: StateFlow<List<WalletState>> = c.money.walletStates.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDefault(id: Long) = viewModelScope.launch { c.money.setDefaultWallet(id) }
    fun rename(w: WalletState, name: String) = viewModelScope.launch { c.money.updateWallet(w.wallet.copy(name = name)) }
    fun archive(w: WalletState, archived: Boolean) = viewModelScope.launch { c.money.updateWallet(w.wallet.copy(archived = archived)) }
    fun adjust(w: WalletState, real: Long) = viewModelScope.launch { c.money.adjustWallet(w.wallet.id, w.balance, real) }
    fun add(name: String, kind: String) = viewModelScope.launch { c.money.addWallet(name, kind) }
}

private fun kindName(kind: String) = when (kind) {
    "CASH" -> "Tiền mặt"
    "BANK" -> "Ngân hàng"
    else -> "Ví điện tử"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WalletsScreen(vm: WalletsViewModel, onBack: () -> Unit) {
    val wallets by vm.wallets.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var editing by remember { mutableStateOf<WalletState?>(null) }
    var adjusting by remember { mutableStateOf<WalletState?>(null) }
    var adding by remember { mutableStateOf(false) }

    PixelScreen("Ví", onBack = onBack, actions = { PixelTextButton("+", onClick = { adding = true }) }) {
        PixelText("Tổng: ${formatVnd(wallets.filter { !it.wallet.archived }.sumOf { it.balance })}", style = type.title, color = Pal.GoldDeep)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            items(wallets, key = { it.wallet.id }) { w ->
                PixelPanel(
                    Modifier.fillMaxWidth().clickable { editing = w },
                    tone = if (w.wallet.archived) CardTone.Faded else CardTone.Plain,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            PixelText((if (w.wallet.isDefault) "* " else "") + w.wallet.name, style = type.bodyBold, color = Pal.Ink)
                            PixelText(kindName(w.wallet.kind) + if (w.wallet.archived) " · đã ẩn" else "", style = type.caption, color = Pal.InkSoft)
                        }
                        PixelText(formatVnd(w.balance), style = type.bodyBold, color = Pal.Ink)
                    }
                }
            }
        }
    }

    editing?.let { w ->
        var name by remember(w) { mutableStateOf(w.wallet.name) }
        PixelDialog(onDismiss = { editing = null }) {
            PixelText("Ví ${w.wallet.name}", style = type.title)
            PixelTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Đặt mặc định", selected = w.wallet.isDefault) { vm.setDefault(w.wallet.id) }
                PixelChip("Điều chỉnh số dư") { adjusting = w; editing = null }
                PixelChip(if (w.wallet.archived) "Hiện lại" else "Ẩn ví") { vm.archive(w, !w.wallet.archived); editing = null }
            }
            PixelTextButton("Lưu tên", onClick = { if (name.isNotBlank()) vm.rename(w, name.trim()); editing = null }, modifier = Modifier.fillMaxWidth())
        }
    }

    adjusting?.let { w ->
        var amount by remember(w) { mutableStateOf(AmountInput(w.balance.coerceAtLeast(0))) }
        PixelDialog(onDismiss = { adjusting = null }) {
            PixelText("Số dư thật của ${w.wallet.name}", style = type.title)
            PixelText("Phần chênh lệch sẽ vào lọ Chưa phân bổ.", style = type.caption, color = Pal.InkSoft)
            PixelText(formatVnd(amount.value), style = type.display, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
            PixelKeypad(onKey = { amount = amount.press(it) })
            PixelTextButton("Lưu", onClick = { vm.adjust(w, amount.value); adjusting = null }, modifier = Modifier.fillMaxWidth())
        }
    }

    if (adding) {
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("EWALLET") }
        PixelDialog(onDismiss = { adding = false }) {
            PixelText("Thêm ví", style = type.title)
            PixelTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth(), placeholder = "vd: MoMo")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (k in listOf("CASH", "BANK", "EWALLET")) PixelChip(kindName(k), selected = kind == k) { kind = k }
            }
            PixelText("Số dư ban đầu nhập bằng 'Điều chỉnh số dư' sau khi tạo.", style = type.caption, color = Pal.InkSoft)
            PixelTextButton("Thêm", enabled = name.isNotBlank(), onClick = { vm.add(name.trim(), kind); adding = false }, modifier = Modifier.fillMaxWidth())
        }
    }
}

fun epochMillisToDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
