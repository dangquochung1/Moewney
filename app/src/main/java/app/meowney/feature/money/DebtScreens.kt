package app.meowney.feature.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.data.JarState
import app.meowney.core.data.WalletState
import app.meowney.core.db.DebtRow
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.money.AmountInput
import app.meowney.core.money.JarCode
import app.meowney.core.sound.Sfx
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelKeypad
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelProgress
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.feature.entry.dayLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DebtsUi(val debts: List<DebtRow> = emptyList(), val wallets: List<WalletState> = emptyList(), val jars: List<JarState> = emptyList())

class DebtsViewModel(private val c: AppContainer) : ViewModel() {
    val ui: StateFlow<DebtsUi> = combine(c.money.debts, c.money.walletStates, c.money.jarStates) { d, w, j -> DebtsUi(d, w, j) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtsUi())

    fun create(person: String, lent: Boolean, amount: Long, walletId: Long, jarId: Long, dueDay: Long?, note: String) = viewModelScope.launch {
        c.money.createDebt(person, lent, amount, walletId, jarId, dueDay, note)
        c.sfx.play(Sfx.Sound.Click)
    }

    fun pay(debt: DebtRow, amount: Long, walletId: Long) = viewModelScope.launch {
        c.money.payDebt(debt.id, amount, walletId)
        c.sfx.play(Sfx.Sound.Coin)
    }

    fun delete(debt: DebtRow) = viewModelScope.launch { c.money.deleteDebt(debt.id) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebtsScreen(vm: DebtsViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var lentTab by remember { mutableStateOf(true) }
    var paying by remember { mutableStateOf<DebtRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    val shown = ui.debts.filter { (it.direction == "LENT") == lentTab }
    val open = shown.filter { it.settledDay == null }

    PixelScreen("Sổ nợ", onBack = onBack, actions = { PixelTextButton("+", onClick = { adding = true }) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelChip("Người nợ Sen", selected = lentTab) { lentTab = true }
            PixelChip("Sen nợ", selected = !lentTab) { lentTab = false }
        }
        PixelText(
            "Còn ${formatVnd(open.sumOf { it.left })} · ${open.size} người",
            style = type.title, color = Pal.Gold, modifier = Modifier.padding(vertical = m.unit * 2),
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            if (shown.isEmpty()) {
                item {
                    PixelText(
                        if (lentTab) "Chưa ai nợ Sen. Cho ai mượn tiền thì bấm + để ghi lại." else "Sen không nợ ai. Tuyệt!",
                        style = type.body, color = Pal.Paper,
                    )
                }
            }
            items(shown, key = { it.id }) { d -> DebtCard(d, Modifier.clickable { paying = d }) }
        }
    }

    paying?.let { d ->
        var amount by remember(d) { mutableStateOf(AmountInput(d.left.coerceAtLeast(0))) }
        var wallet by remember(d) { mutableStateOf(d.walletId) }
        PixelDialog(onDismiss = { paying = null }) {
            PixelText(if (d.direction == "LENT") "${d.person} trả Sen" else "Sen trả ${d.person}", style = type.title)
            PixelText("Còn ${formatVnd(d.left)} / ${formatVnd(d.principal)}", style = type.caption, color = Pal.InkSoft)
            if (d.settledDay == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    for (w in ui.wallets.filter { !it.wallet.archived }) PixelChip(w.wallet.name, selected = wallet == w.wallet.id) { wallet = w.wallet.id }
                }
                PixelText(formatVnd(amount.value), style = type.display, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
                PixelKeypad(onKey = { amount = amount.press(it) })
                PixelTextButton("Ghi lần trả", enabled = amount.value in 1..d.left, onClick = {
                    vm.pay(d, amount.value, wallet)
                    paying = null
                }, modifier = Modifier.fillMaxWidth())
            } else {
                PixelText("Đã trả xong.", style = type.body, color = Pal.Green)
            }
            PixelTextButton("Xoá khoản này", onClick = { vm.delete(d); paying = null }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        }
    }

    if (adding) {
        NewDebtDialog(
            wallets = ui.wallets.filter { !it.wallet.archived },
            jars = ui.jars,
            lentDefault = lentTab,
            onSave = { person, lent, amount, wallet, jar, due, note ->
                vm.create(person, lent, amount, wallet, jar, due, note)
                lentTab = lent
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun DebtCard(d: DebtRow, modifier: Modifier) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val overdue = d.settledDay == null && d.dueDay != null && d.dueDay < LocalDate.now().toEpochDay()
    PixelPanel(modifier.fillMaxWidth(), sprite = if (d.settledDay != null) "ui/wood/nine_path_panel_disabled" else "ui/wood/nine_path_panel_3") {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(d.person, style = type.bodyBold, modifier = Modifier.weight(1f), maxLines = 1)
                PixelText(if (d.settledDay != null) "xong" else "còn ${formatVndShort(d.left)}", style = type.bodyBold, color = if (d.settledDay != null) Pal.Green else Pal.Ink)
            }
            PixelProgress(fraction = d.paid.toFloat() / d.principal.coerceAtLeast(1), color = Pal.Sky)
            val bits = listOfNotNull(
                "${formatVndShort(d.paid)} / ${formatVndShort(d.principal)}",
                d.dueDay?.let { (if (overdue) "QUÁ HẠN " else "hạn ") + dayLabel(LocalDate.ofEpochDay(it)) },
                d.note.takeIf { it.isNotBlank() },
            )
            PixelText(bits.joinToString(" · "), style = type.caption, color = if (overdue) Pal.Red else Pal.InkSoft)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewDebtDialog(
    wallets: List<WalletState>,
    jars: List<JarState>,
    lentDefault: Boolean,
    onSave: (String, Boolean, Long, Long, Long, Long?, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var lent by remember { mutableStateOf(lentDefault) }
    var person by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf(AmountInput()) }
    var wallet by remember { mutableStateOf(wallets.firstOrNull { it.wallet.isDefault }?.wallet?.id ?: wallets.firstOrNull()?.wallet?.id ?: 0L) }
    // lending usually comes out of everyday money; borrowed money waits in "Chưa phân bổ"
    var jar by remember(lent) { mutableStateOf(jars.firstOrNull { it.code == if (lent) JarCode.NEC else JarCode.UNALLOC }?.jar?.id ?: 0L) }
    var dueWeeks by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }

    PixelDialog(onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelChip("Sen cho vay", selected = lent) { lent = true }
            PixelChip("Sen đi vay", selected = !lent) { lent = false }
        }
        PixelTextField(person, { person = it.take(20) }, Modifier.fillMaxWidth(), placeholder = if (lent) "Cho ai vay?" else "Vay của ai?")
        PixelText(formatVnd(amount.value), style = type.display, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
        PixelKeypad(onKey = { amount = amount.press(it) })
        PixelText("Ví", style = type.caption, color = Pal.InkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (w in wallets) PixelChip(w.wallet.name, selected = wallet == w.wallet.id) { wallet = w.wallet.id }
        }
        PixelText(if (lent) "Lấy từ lọ" else "Bỏ vào lọ", style = type.caption, color = Pal.InkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (j in jars) PixelChip(j.jar.name, selected = jar == j.jar.id) { jar = j.jar.id }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText("Hạn trả", style = type.body, modifier = Modifier.weight(1f))
            PixelTextButton("-", onClick = { dueWeeks = (dueWeeks - 1).coerceAtLeast(0) }, look = ButtonLook.Light)
            PixelText(if (dueWeeks == 0) "không" else "$dueWeeks tuần", style = type.bodyBold)
            PixelTextButton("+", onClick = { dueWeeks = (dueWeeks + 1).coerceAtMost(104) }, look = ButtonLook.Light)
        }
        PixelTextField(note, { note = it.take(40) }, Modifier.fillMaxWidth(), placeholder = "Ghi chú")
        PixelTextButton("Lưu", enabled = person.isNotBlank() && amount.value > 0 && wallet != 0L && jar != 0L, onClick = {
            val due = if (dueWeeks > 0) LocalDate.now().plusWeeks(dueWeeks.toLong()).toEpochDay() else null
            onSave(person.trim(), lent, amount.value, wallet, jar, due, note.trim())
        }, modifier = Modifier.fillMaxWidth())
    }
}
