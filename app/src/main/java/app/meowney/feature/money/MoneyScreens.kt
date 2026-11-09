package app.meowney.feature.money

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
)

class MoneyViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<MoneyState> = combine(c.money.jarStates, c.money.loans, c.money.recentTxns(400)) { j, l, t ->
        MoneyState(j, l, t)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoneyState())
}

private const val TRANSFER_ICON = 9 // floppy with arrow: money moving between wallets

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
) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val names = s.jars.associate { it.jar.id to it.jar.name }
    val days = remember(s.txns) { s.txns.groupBy { it.day }.toList() }

    PixelScreen("Tiền", onBack = onBack, actions = {
        PixelTextButton("Ví", onClick = onWallets, look = ButtonLook.Light)
        PixelTextButton("+", onClick = onAdd)
    }) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            item { JarShelf(s.jars.filter { it.code != JarCode.UNALLOC }, typeface, onJar) }
            item {
                val unalloc = s.jars.firstOrNull { it.code == JarCode.UNALLOC }
                if (unalloc != null) {
                    Row(Modifier.fillMaxWidth().clickable { onJar(unalloc.jar.id) }.padding(vertical = m.unit)) {
                        PixelText("Chưa phân bổ", style = type.body, color = Pal.Paper, modifier = Modifier.weight(1f))
                        PixelText(formatVnd(unalloc.balance), style = type.bodyBold, color = Pal.Paper)
                    }
                }
                for (l in s.loans) {
                    PixelText(
                        "${names[l.borrowerJarId]} đang nợ ${names[l.lenderJarId]} ${formatVndShort(l.amount)}",
                        style = type.caption, color = Pal.Gold,
                    )
                }
            }
            if (days.isEmpty()) {
                item { PixelText("Chưa có giao dịch nào. Bấm + để ghi khoản đầu tiên.", style = type.body, color = Pal.Paper) }
            }
            for ((day, rows) in days) {
                item(key = "d$day") {
                    val inc = rows.filter { it.type == "INCOME" }.sumOf { it.amount }
                    val exp = rows.filter { it.type == "EXPENSE" }.sumOf { it.amount }
                    Row(Modifier.fillMaxWidth().padding(top = m.unit * 2), verticalAlignment = Alignment.CenterVertically) {
                        PixelText(dayLabel(LocalDate.ofEpochDay(day)).uppercase(), style = type.bodyBold, color = Pal.Gold, modifier = Modifier.weight(1f))
                        if (inc > 0) PixelText("+${formatVndShort(inc)}  ", style = type.caption, color = Pal.Green)
                        if (exp > 0) PixelText("-${formatVndShort(exp)}", style = type.caption, color = Pal.Cream)
                    }
                }
                items(rows, key = { it.id }) { t -> TxnLine(t, onClick = { onTxn(t.id) }) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JarShelf(jars: List<JarState>, typeface: Typeface, onJar: (Long) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val top = maxOf(jars.maxOfOrNull { it.balance } ?: 1, 1)
    val scale = (m.ui - 1).coerceAtLeast(2)
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_bg") {
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
                    PixelText(j.jar.name, style = type.caption, color = Pal.Cream, align = TextAlign.Center, maxLines = 1)
                    PixelText(formatVndShort(j.balance), style = type.bodyBold, color = if (negative) Pal.Red else jarColor(j.code))
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
        RavenIcon(if (t.type == "TRANSFER") TRANSFER_ICON else t.categoryIcon ?: 12, scale = (m.ui - 1).coerceAtLeast(2))
        Column(Modifier.weight(1f)) {
            val title = when (t.type) {
                "TRANSFER" -> "Chuyển ${t.walletName} > ${t.toWalletName}"
                "ADJUST" -> "Điều chỉnh ${t.walletName}"
                else -> t.categoryName ?: "Khác"
            }
            PixelText(title, style = type.body, color = Pal.Cream, maxLines = 1)
            val sub = listOfNotNull(
                t.note.takeIf { it.isNotBlank() },
                t.walletName.takeIf { t.type == "EXPENSE" || t.type == "INCOME" },
                t.jarName.takeIf { t.type == "EXPENSE" },
            ).joinToString(" · ")
            if (sub.isNotEmpty()) PixelText(sub, style = type.caption, color = Pal.Paper, maxLines = 1)
        }
        val (sign, color) = when (t.type) {
            "INCOME" -> "+" to Pal.Green
            "EXPENSE" -> "-" to Pal.Cream
            "ADJUST" -> (if (t.amount >= 0) "+" else "") to Pal.Paper
            else -> "" to Pal.Sky
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
        PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
            Column {
                PixelText(formatVnd(jar.balance), style = type.display, color = if (jar.balance < 0) Pal.Red else Pal.Ink)
                if (jar.code != JarCode.UNALLOC) PixelText("Nhận ${jar.jar.percent}% mỗi khoản thu", style = type.caption, color = Pal.InkSoft)
            }
        }
        PixelTextButton("Chuyển sang lọ khác", onClick = { moving = true }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth().padding(vertical = m.unit * 2))
        LazyColumn(Modifier.weight(1f)) {
            items(entries, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = m.unit)) {
                    Column(Modifier.weight(1f)) {
                        PixelText(kindLabel(e), style = type.body, color = Pal.Cream)
                        PixelText(dayLabel(LocalDate.ofEpochDay(e.day)), style = type.caption, color = Pal.Paper)
                    }
                    PixelText((if (e.amount > 0) "+" else "") + formatVnd(e.amount), style = type.bodyBold, color = if (e.amount >= 0) Pal.Green else Pal.Cream)
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
        PixelText("Tổng: ${formatVnd(wallets.filter { !it.wallet.archived }.sumOf { it.balance })}", style = type.title, color = Pal.Gold)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            items(wallets, key = { it.wallet.id }) { w ->
                PixelPanel(
                    Modifier.fillMaxWidth().clickable { editing = w },
                    sprite = if (w.wallet.archived) "ui/wood/nine_path_panel_disabled" else "ui/wood/nine_path_panel",
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
