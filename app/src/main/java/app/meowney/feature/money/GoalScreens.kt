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
import androidx.compose.runtime.LaunchedEffect
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
import app.meowney.core.CatEvent
import app.meowney.core.data.JarState
import app.meowney.core.db.GoalEntity
import app.meowney.core.db.GoalRow
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

// ------------------------------------------------------------------ shared pieces

/** Progress card used in the Money screen and the goal screen. */
@Composable
fun GoalCard(goal: GoalRow, jarName: String, modifier: Modifier = Modifier) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val pct = if (goal.target > 0) (goal.saved * 100 / goal.target).toInt() else 0
    PixelPanel(modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelText(goal.name, style = type.bodyBold, modifier = Modifier.weight(1f), maxLines = 1)
                PixelText("$pct%", style = type.bodyBold, color = if (pct >= 100) Pal.Green else Pal.Ink)
            }
            PixelProgress(fraction = goal.saved.toFloat() / goal.target.coerceAtLeast(1), color = Pal.Green)
            val left = (goal.target - goal.saved).coerceAtLeast(0)
            val bits = listOfNotNull(
                "${formatVndShort(goal.saved)} / ${formatVndShort(goal.target)}",
                if (left > 0) "còn ${formatVndShort(left)}" else "đủ rồi!",
                goal.deadlineDay?.let { "hạn ${dayLabel(LocalDate.ofEpochDay(it))}" },
                if (goal.autoPercent > 0) "tự nạp ${goal.autoPercent}% $jarName" else null,
            )
            PixelText(bits.joinToString(" · "), style = type.caption, color = Pal.InkSoft)
        }
    }
}

/** Create or edit a goal. [jars] are the real jars a goal can live in. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoalEditDialog(
    initial: GoalEntity?,
    jars: List<JarState>,
    onSave: (GoalEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var target by remember { mutableStateOf(AmountInput(initial?.target ?: 0)) }
    var jarId by remember { mutableStateOf(initial?.jarId ?: jars.firstOrNull { it.code == JarCode.LTSS }?.jar?.id ?: jars.firstOrNull()?.jar?.id ?: 0L) }
    var auto by remember { mutableStateOf(initial?.autoPercent ?: 50) }
    var months by remember {
        mutableStateOf(initial?.deadlineDay?.let { ((it - LocalDate.now().toEpochDay()) / 30).toInt().coerceAtLeast(0) } ?: 0)
    }
    var typingTarget by remember { mutableStateOf(false) }

    PixelDialog(onDismiss) {
        PixelText(if (initial == null) "Mục tiêu mới" else "Sửa mục tiêu", style = type.title)
        PixelTextField(name, { name = it.take(24) }, Modifier.fillMaxWidth(), placeholder = "vd: Laptop")
        PixelTextButton(
            if (target.value == 0L) "Cần bao nhiêu?" else "Cần ${formatVnd(target.value)}",
            onClick = { typingTarget = !typingTarget }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth(),
        )
        if (typingTarget) PixelKeypad(onKey = { target = target.press(it) })
        PixelText("Nằm trong lọ", style = type.caption, color = Pal.InkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (j in jars) PixelChip(j.jar.name, selected = jarId == j.jar.id) { jarId = j.jar.id }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText("Tự nạp", style = type.body, modifier = Modifier.weight(1f))
            PixelTextButton("-", onClick = { auto = (auto - 10).coerceAtLeast(0) }, look = ButtonLook.Light)
            PixelText("$auto%", style = type.bodyBold)
            PixelTextButton("+", onClick = { auto = (auto + 10).coerceAtMost(100) }, look = ButtonLook.Light)
        }
        PixelText("= $auto% phần tiền lọ nhận được mỗi khoản thu", style = type.caption, color = Pal.InkSoft)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText("Hạn", style = type.body, modifier = Modifier.weight(1f))
            PixelTextButton("-", onClick = { months = (months - 1).coerceAtLeast(0) }, look = ButtonLook.Light)
            PixelText(if (months == 0) "không" else "$months tháng", style = type.bodyBold)
            PixelTextButton("+", onClick = { months = (months + 1).coerceAtMost(120) }, look = ButtonLook.Light)
        }
        PixelTextButton(
            "Lưu",
            enabled = name.isNotBlank() && target.value > 0 && jarId != 0L,
            onClick = {
                val today = LocalDate.now()
                onSave(
                    (initial ?: GoalEntity(name = "", jarId = jarId, target = 0, createdDay = today.toEpochDay())).copy(
                        name = name.trim(),
                        jarId = jarId,
                        target = target.value,
                        autoPercent = auto,
                        deadlineDay = if (months > 0) today.plusMonths(months.toLong()).toEpochDay() else null,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------ goal screen

data class GoalUi(
    val goal: GoalRow? = null,
    val jars: List<JarState> = emptyList(),
    val free: Long = 0,
    val walletName: String = "",
)

class GoalViewModel(private val c: AppContainer, private val goalId: Long) : ViewModel() {
    val ui: StateFlow<GoalUi> = combine(c.money.goals, c.money.jarStates, c.money.goalTotalsByJar, c.money.walletStates) { goals, jars, totals, wallets ->
        val g = goals.firstOrNull { it.id == goalId }
        val jar = jars.firstOrNull { it.jar.id == g?.jarId }
        val wallet = wallets.firstOrNull { it.wallet.isDefault } ?: wallets.firstOrNull()
        GoalUi(g, jars, (jar?.balance ?: 0) - (totals[g?.jarId] ?: 0), wallet?.wallet?.name ?: "ví")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GoalUi())
    val entries = c.money.goalEntries(goalId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val closed = MutableStateFlow(false)

    fun fund(amount: Long) = viewModelScope.launch {
        val r = c.money.fundGoal(goalId, amount)
        c.sfx.play(if (amount > 0) Sfx.Sound.Coin else Sfx.Sound.Click)
        r.notes.firstOrNull()?.let {
            message.value = it
            c.events.post(CatEvent.Coins(r.coins, it))
        }
    }

    fun save(g: GoalEntity) = viewModelScope.launch { c.money.saveGoal(g) }

    fun archive() = viewModelScope.launch {
        c.money.archiveGoal(goalId)
        closed.value = true
    }

    fun buy() = viewModelScope.launch {
        val wallet = c.money.defaultWalletId() ?: return@launch
        val cat = c.money.categoriesNow().firstOrNull { it.type == "EXPENSE" && it.name == "Mua sắm" }?.id
        val r = c.money.buyGoal(goalId, wallet, cat)
        c.sfx.play(Sfx.Sound.Success)
        c.events.post(CatEvent.Coins(0, r.notes.first()))
        closed.value = true
    }
}

@Composable
fun GoalScreen(vm: GoalViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsState()
    val entries by vm.entries.collectAsState()
    val message by vm.message.collectAsState()
    val closed by vm.closed.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var moving by remember { mutableStateOf<Int?>(null) } // +1 fund, -1 withdraw
    var editing by remember { mutableStateOf(false) }
    var buying by remember { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) onBack() }
    val goal = ui.goal ?: return
    val jarName = ui.jars.firstOrNull { it.jar.id == goal.jarId }?.jar?.name ?: ""

    PixelScreen(goal.name, onBack = onBack, actions = { PixelTextButton("Sửa", onClick = { editing = true }, look = ButtonLook.Light) }) {
        GoalCard(goal, jarName)
        message?.let { PixelText(it, style = type.bodyBold, color = Pal.Gold, modifier = Modifier.padding(top = m.unit * 2)) }
        PixelText("Phần tự do trong $jarName: ${formatVnd(ui.free)}", style = type.caption, color = Pal.InkSoft, modifier = Modifier.padding(vertical = m.unit * 2))
        if (goal.saved >= goal.target) {
            PixelTextButton("Đã mua — trừ tiền", onClick = { buying = true }, modifier = Modifier.fillMaxWidth())
            PixelText("Ghi một khoản chi và trừ số đã dành khỏi ví cùng lọ.", style = type.caption, color = Pal.InkSoft, modifier = Modifier.padding(top = m.unit))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), modifier = Modifier.padding(top = m.unit * 2)) {
            PixelTextButton("Nạp thêm", onClick = { moving = 1 }, enabled = ui.free > 0)
            PixelTextButton("Rút", onClick = { moving = -1 }, enabled = goal.saved > 0, look = ButtonLook.Light)
        }
        LazyColumn(Modifier.weight(1f).padding(top = m.unit * 2)) {
            items(entries, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = m.unit)) {
                    Column(Modifier.weight(1f)) {
                        PixelText(
                            when (e.kind) {
                                "AUTO" -> "Tự nạp từ khoản thu"
                                "MANUAL" -> "Nạp thêm"
                                else -> "Rút ra"
                            },
                            style = type.body, color = Pal.Ink,
                        )
                        PixelText(dayLabel(LocalDate.ofEpochDay(e.day)), style = type.caption, color = Pal.InkSoft)
                    }
                    PixelText((if (e.amount > 0) "+" else "") + formatVnd(e.amount), style = type.bodyBold, color = if (e.amount >= 0) Pal.Green else Pal.Ink)
                }
            }
        }
        PixelTextButton("Bỏ mục tiêu — nhả tiền trong lọ", onClick = vm::archive, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth().padding(top = m.unit * 2))
        PixelText(
            "Tiền vẫn nằm trong lọ. Bỏ chỉ nhả phần đang giữ chỗ, không ghi chi tiêu và không trừ ví.",
            style = type.caption, color = Pal.InkSoft, modifier = Modifier.padding(bottom = m.unit * 2),
        )
    }

    if (buying) {
        PixelDialog(onDismiss = { buying = false }) {
            PixelText("Đã mua ${goal.name}?", style = type.title)
            PixelText(
                "Trừ ${formatVnd(goal.saved)} khỏi ${ui.walletName} và lọ $jarName. Mục tiêu đóng lại.",
                style = type.body,
            )
            PixelTextButton("Trừ tiền", onClick = { buying = false; vm.buy() }, modifier = Modifier.fillMaxWidth())
            PixelTextButton("Để sau", onClick = { buying = false }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        }
    }
    moving?.let { dir ->
        var amount by remember(dir) { mutableStateOf(AmountInput()) }
        val max = if (dir > 0) ui.free else goal.saved
        PixelDialog(onDismiss = { moving = null }) {
            PixelText(if (dir > 0) "Nạp vào ${goal.name}" else "Rút khỏi ${goal.name}", style = type.title)
            PixelText("Tối đa ${formatVnd(max)}", style = type.caption, color = Pal.InkSoft)
            PixelText(formatVnd(amount.value), style = type.display, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
            PixelKeypad(onKey = { amount = amount.press(it) })
            PixelTextButton("Xong", enabled = amount.value in 1..max, onClick = {
                vm.fund(dir * amount.value)
                moving = null
            }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (editing) {
        GoalEditDialog(
            initial = GoalEntity(goal.id, goal.name, goal.jarId, goal.target, goal.deadlineDay, goal.autoPercent, goal.createdDay, goal.achievedDay, goal.archived),
            jars = ui.jars.filter { it.code != JarCode.UNALLOC },
            onSave = { vm.save(it); editing = false },
            onDismiss = { editing = false },
        )
    }
}

/** The goals block under the jar shelf in the Money screen. */
@Composable
fun GoalsSection(goals: List<GoalRow>, jars: List<JarState>, onGoal: (Long) -> Unit, onNew: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText("MỤC TIÊU", style = type.bodyBold, color = Pal.Gold, modifier = Modifier.weight(1f))
            PixelTextButton("+ Mục tiêu", onClick = onNew, look = ButtonLook.Light)
        }
        if (goals.isEmpty()) PixelText("Đặt một mục tiêu để dành, vd 'Laptop 20tr'.", style = type.caption, color = Pal.InkSoft)
        for (g in goals) {
            GoalCard(g, jars.firstOrNull { it.jar.id == g.jarId }?.jar?.name ?: "", Modifier.clickable { onGoal(g.id) })
        }
    }
}
