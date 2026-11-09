package app.meowney.feature.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import app.meowney.core.db.CategoryEntity
import app.meowney.core.db.RecurringEntity
import app.meowney.core.db.WalletEntity
import app.meowney.core.format.formatVnd
import app.meowney.core.money.AmountInput
import app.meowney.core.money.RecurrenceEngine
import app.meowney.core.money.Repeat
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class RecurringUi(
    val bills: List<RecurringEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val wallets: List<WalletEntity> = emptyList(),
)

class RecurringViewModel(private val c: AppContainer) : ViewModel() {
    val ui: StateFlow<RecurringUi> = combine(c.money.recurring, c.money.categories, c.money.wallets) { r, cats, w ->
        RecurringUi(r, cats.filter { it.type == "EXPENSE" }, w.filter { !it.archived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecurringUi())

    fun save(r: RecurringEntity) = viewModelScope.launch { c.money.saveRecurring(r) }
    fun delete(id: Long) = viewModelScope.launch { c.money.deleteRecurring(id) }
}

fun repeatLabel(r: RecurringEntity): String = when (Repeat.valueOf(r.rule)) {
    Repeat.MONTHLY -> "ngày ${r.ruleDay} hằng tháng"
    Repeat.WEEKLY -> listOf("", "thứ 2", "thứ 3", "thứ 4", "thứ 5", "thứ 6", "thứ 7", "chủ nhật")[r.ruleDay] + " hằng tuần"
    Repeat.YEARLY -> "${r.ruleDay}/${r.ruleMonth} hằng năm"
}

@Composable
fun RecurringScreen(vm: RecurringViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var editing by remember { mutableStateOf<RecurringEntity?>(null) }
    var adding by remember { mutableStateOf(false) }

    PixelScreen("Khoản định kỳ", onBack = onBack, actions = { PixelTextButton("+", onClick = { adding = true }) }) {
        PixelText(
            "Mỗi tháng: ${formatVnd(ui.bills.filter { it.active && it.rule == Repeat.MONTHLY.name }.sumOf { it.amount })}",
            style = type.title, color = Pal.Gold,
        )
        PixelText("Trẫm nhắc vào sáng ngày đến hạn; số 'hôm nay còn' đã chừa sẵn tiền cho các khoản này.", style = type.caption, color = Pal.Paper)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            if (ui.bills.isEmpty()) item { PixelText("Chưa có khoản nào. Tiền nhà, điện, mạng, Netflix... bấm + để thêm.", style = type.body, color = Pal.Paper) }
            items(ui.bills, key = { it.id }) { r ->
                val cat = ui.categories.firstOrNull { it.id == r.categoryId }
                PixelPanel(Modifier.fillMaxWidth().clickable { editing = r }, sprite = "ui/wood/nine_path_panel_3") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        RavenIcon(cat?.icon ?: 12, scale = (m.ui - 1).coerceAtLeast(2))
                        Column(Modifier.weight(1f)) {
                            PixelText(r.name, style = type.bodyBold, maxLines = 1)
                            PixelText("${repeatLabel(r)} · hạn ${dayLabel(LocalDate.ofEpochDay(r.nextDueDay))}", style = type.caption, color = Pal.InkSoft)
                        }
                        PixelText(formatVnd(r.amount), style = type.bodyBold)
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        RecurringDialog(
            initial = editing,
            categories = ui.categories,
            wallets = ui.wallets,
            onSave = { vm.save(it); adding = false; editing = null },
            onDelete = editing?.let { e -> { vm.delete(e.id); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecurringDialog(
    initial: RecurringEntity?,
    categories: List<CategoryEntity>,
    wallets: List<WalletEntity>,
    onSave: (RecurringEntity) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var amount by remember { mutableStateOf(AmountInput(initial?.amount ?: 0)) }
    var category by remember { mutableStateOf(initial?.categoryId ?: categories.firstOrNull { it.name.startsWith("Nhà") }?.id ?: categories.firstOrNull()?.id ?: 0L) }
    var wallet by remember { mutableStateOf(initial?.walletId ?: wallets.firstOrNull { it.isDefault }?.id ?: wallets.firstOrNull()?.id ?: 0L) }
    var repeat by remember { mutableStateOf(initial?.rule?.let { Repeat.valueOf(it) } ?: Repeat.MONTHLY) }
    var day by remember { mutableStateOf(initial?.ruleDay ?: today.dayOfMonth) }
    var month by remember { mutableStateOf(initial?.ruleMonth?.takeIf { it > 0 } ?: today.monthValue) }
    var typing by remember { mutableStateOf(initial == null) }

    PixelDialog(onDismiss) {
        PixelText(if (initial == null) "Khoản định kỳ mới" else "Sửa khoản định kỳ", style = type.title)
        PixelTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth(), placeholder = "vd: Tiền nhà")
        PixelTextButton(if (amount.value == 0L) "Bao nhiêu?" else formatVnd(amount.value), onClick = { typing = !typing }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        if (typing) PixelKeypad(onKey = { amount = amount.press(it) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (cat in categories) PixelChip(cat.name, selected = category == cat.id) { category = cat.id }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (w in wallets) PixelChip(w.name, selected = wallet == w.id) { wallet = w.id }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
            PixelChip("Hằng tháng", selected = repeat == Repeat.MONTHLY) { repeat = Repeat.MONTHLY; day = day.coerceIn(1, 31) }
            PixelChip("Hằng tuần", selected = repeat == Repeat.WEEKLY) { repeat = Repeat.WEEKLY; day = today.dayOfWeek.value }
            PixelChip("Hằng năm", selected = repeat == Repeat.YEARLY) { repeat = Repeat.YEARLY }
        }
        val maxDay = if (repeat == Repeat.WEEKLY) 7 else 31
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText(if (repeat == Repeat.WEEKLY) "Thứ" else "Ngày", style = type.body, modifier = Modifier.weight(1f))
            PixelTextButton("-", onClick = { day = if (day <= 1) maxDay else day - 1 }, look = ButtonLook.Light)
            PixelText(
                if (repeat == Repeat.WEEKLY) listOf("", "T2", "T3", "T4", "T5", "T6", "T7", "CN")[day.coerceIn(1, 7)] else "$day",
                style = type.bodyBold, align = TextAlign.Center,
            )
            PixelTextButton("+", onClick = { day = if (day >= maxDay) 1 else day + 1 }, look = ButtonLook.Light)
        }
        if (repeat == Repeat.YEARLY) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelText("Tháng", style = type.body, modifier = Modifier.weight(1f))
                PixelTextButton("-", onClick = { month = if (month <= 1) 12 else month - 1 }, look = ButtonLook.Light)
                PixelText("$month", style = type.bodyBold)
                PixelTextButton("+", onClick = { month = if (month >= 12) 1 else month + 1 }, look = ButtonLook.Light)
            }
        }
        val firstDue = RecurrenceEngine.onOrAfter(repeat, day, month, today)
        PixelText("Hạn gần nhất: ${dayLabel(firstDue)}", style = type.caption, color = Pal.InkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelTextButton("Lưu", enabled = name.isNotBlank() && amount.value > 0 && category != 0L && wallet != 0L, onClick = {
                val jar = categories.firstOrNull { it.id == category }?.defaultJarId
                val changedRule = initial == null || initial.rule != repeat.name || initial.ruleDay != day || initial.ruleMonth != month
                onSave(
                    (initial ?: RecurringEntity(name = "", amount = 0, categoryId = 0, walletId = 0, rule = repeat.name, ruleDay = day, nextDueDay = 0)).copy(
                        name = name.trim(), amount = amount.value, categoryId = category, walletId = wallet, jarId = jar,
                        rule = repeat.name, ruleDay = day, ruleMonth = if (repeat == Repeat.YEARLY) month else 0,
                        nextDueDay = if (changedRule) firstDue.toEpochDay() else initial!!.nextDueDay,
                    ),
                )
            })
            if (onDelete != null) PixelTextButton("Xoá", onClick = onDelete, look = ButtonLook.Light)
        }
    }
}
