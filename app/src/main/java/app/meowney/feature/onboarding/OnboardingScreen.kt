package app.meowney.feature.onboarding

import android.Manifest
import android.graphics.Typeface
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.WalletDraft
import app.meowney.core.db.DefaultData
import app.meowney.core.format.formatVnd
import app.meowney.core.money.AmountInput
import app.meowney.core.money.JarCode
import app.meowney.core.money.JarInfo
import app.meowney.core.money.MoneyEngine
import app.meowney.core.sound.Sfx
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelKeypad
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.SpeechBubble
import app.meowney.feature.entry.PourEditor
import app.meowney.feature.entry.PourJar
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class OnboardingState(
    val step: Int = 0,
    val catColor: Int = 5,
    val catName: String = "Mít",
    val wallets: List<WalletDraft> = listOf(WalletDraft("Tiền mặt", "CASH", 0), WalletDraft("Ngân hàng", "BANK", 0)),
    val defaultWallet: Int = 0,
    val percents: Map<JarCode, Int> = DefaultData.jars.associate { it.code to it.percent },
    val names: Map<JarCode, String> = DefaultData.jars.associate { it.code to it.name },
    val payday: Int = 0,
    val shares: Map<JarCode, Long> = emptyMap(),
) {
    val total get() = wallets.sumOf { it.balance }
    val percentSum get() = percents.filterKeys { it != JarCode.UNALLOC }.values.sum()
}

class OnboardingViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state
    val finished = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun update(f: (OnboardingState) -> OnboardingState) = _state.update(f)

    fun next() = _state.update { s ->
        val step = s.step + 1
        // entering the pour step: split the total by the chosen percentages
        if (step == 4) {
            val infos = JarCode.entries.map { JarInfo(it.ordinal.toLong(), it, s.percents[it] ?: 0) }
            val split = MoneyEngine.split(s.total, infos).mapKeys { JarCode.entries[it.key.toInt()] }
            s.copy(step = step, shares = split)
        } else {
            s.copy(step = step)
        }
    }

    fun back() = _state.update { it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun finish() = viewModelScope.launch {
        val s = _state.value
        c.money.onboard(s.wallets.filter { it.name.isNotBlank() }, s.defaultWallet, s.percents, s.shares.filterKeys { it != JarCode.UNALLOC }, LocalDate.now())
        // custom jar names
        c.money.jarsNow().forEach { j ->
            val name = s.names[JarCode.valueOf(j.code)]
            if (name != null && name.isNotBlank() && name != j.name) c.money.updateJar(j.copy(name = name.trim()))
        }
        c.settings.update { it.copy(onboarded = true, catName = s.catName.ifBlank { "Mít" }, catColor = s.catColor, payday = s.payday) }
        c.sfx.play(Sfx.Sound.Success)
        c.events.post(CatEvent.Coins(20, "Chào Sen! Từ nay ${s.catName} giữ 6 lọ tiền cho Sen. Quà ra mắt +20 xu."))
        finished.emit(Unit)
    }
}

private val CAT_LINES = listOf(
    "Meo! Sen chọn trẫm đi. Rồi đặt cho trẫm một cái tên thật kêu.",
    "Sen đang có bao nhiêu tiền, để ở đâu? Khai thật với trẫm.",
    "Quy tắc 6 chiếc lọ: mỗi đồng vào đều chia lọ. Sen chỉnh % nếu muốn.",
    "Sen nhận lương ngày nào? Trẫm dùng nó để tính mỗi ngày được tiêu bao nhiêu.",
    "Giờ rót tiền đang có vào 6 lọ. Xong là bắt đầu!",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, typeface: Typeface, onFinished: () -> Unit) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var editWallet by remember { mutableIntStateOf(-1) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.finish() }
    LaunchedEffect(vm) { vm.finished.collect { onFinished() } }

    PixelScreen("Làm quen (${s.step + 1}/5)", onBack = if (s.step > 0) vm::back else null) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            SpeechBubble(CAT_LINES[s.step])
            when (s.step) {
                0 -> {
                    CatPicker(s.catColor) { color -> vm.update { it.copy(catColor = color) } }
                    PixelText("Tên mèo", style = type.bodyBold, color = Pal.Ink)
                    PixelTextField(s.catName, { n -> vm.update { it.copy(catName = n.take(16)) } }, Modifier.fillMaxWidth())
                }
                1 -> {
                    s.wallets.forEachIndexed { i, w ->
                        PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel") {
                            Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                                PixelTextField(w.name, { n -> vm.update { st -> st.copy(wallets = st.wallets.toMutableList().also { it[i] = w.copy(name = n.take(20)) }) } }, Modifier.fillMaxWidth())
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                                    for ((kind, label) in listOf("CASH" to "Tiền mặt", "BANK" to "Ngân hàng", "EWALLET" to "Ví điện tử")) {
                                        PixelChip(label, selected = w.kind == kind) {
                                            vm.update { st -> st.copy(wallets = st.wallets.toMutableList().also { it[i] = w.copy(kind = kind) }) }
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                                    PixelTextButton(formatVnd(w.balance), onClick = { editWallet = i }, look = ButtonLook.Light)
                                    PixelChip("Mặc định", selected = s.defaultWallet == i) { vm.update { it.copy(defaultWallet = i) } }
                                }
                            }
                        }
                    }
                    PixelTextButton("+ Thêm ví", onClick = {
                        vm.update { it.copy(wallets = it.wallets + WalletDraft("Ví ${it.wallets.size + 1}", "EWALLET", 0)) }
                    }, look = ButtonLook.Light)
                    PixelText("Tổng: ${formatVnd(s.total)}", style = type.title, color = Pal.Gold)
                }
                2 -> {
                    for (code in JarCode.entries.filter { it != JarCode.UNALLOC }) {
                        val pct = s.percents[code] ?: 0
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                            PixelTextField(s.names[code] ?: "", { n -> vm.update { it.copy(names = it.names + (code to n.take(20))) } }, Modifier.weight(1f))
                            PixelTextButton("-", onClick = { vm.update { it.copy(percents = it.percents + (code to (pct - 5).coerceAtLeast(0))) } }, look = ButtonLook.Light)
                            PixelText("$pct%", style = type.bodyBold, color = Pal.Ink, modifier = Modifier.size(m.unit * 30, m.unit * 10), align = TextAlign.Center, maxLines = 1)
                            PixelTextButton("+", onClick = { vm.update { it.copy(percents = it.percents + (code to (pct + 5).coerceAtMost(100))) } }, look = ButtonLook.Light)
                        }
                    }
                    PixelText(
                        "Tổng: ${s.percentSum}%" + if (s.percentSum != 100) " (phải đúng 100%)" else "",
                        style = type.title,
                        color = if (s.percentSum == 100) Pal.Green else Pal.Red,
                    )
                }
                3 -> {
                    PixelChip("Cuối tháng", selected = s.payday == 0) { vm.update { it.copy(payday = 0) } }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        PixelText("Ngày cố định:", style = type.body, color = Pal.Ink)
                        PixelTextButton("-", onClick = { vm.update { it.copy(payday = if (it.payday <= 2) 0 else it.payday - 1) } }, look = ButtonLook.Light)
                        PixelText(if (s.payday == 0) "--" else "ngày ${s.payday}", style = type.bodyBold, color = Pal.Gold)
                        PixelTextButton("+", onClick = { vm.update { it.copy(payday = if (it.payday == 0) 2 else (it.payday + 1).coerceAtMost(28)) } }, look = ButtonLook.Light)
                    }
                    PixelText(
                        if (s.payday == 0) "Mỗi kỳ là 1 tháng dương lịch. Lương không cố định thì cứ để Cuối tháng."
                        else "Mỗi kỳ chạy từ ngày ${s.payday} tới hết ngày ${s.payday - 1} tháng sau.",
                        style = type.caption, color = Pal.InkSoft,
                    )
                }
                4 -> {
                    PixelText("Rót ${formatVnd(s.total)} vào lọ", style = type.title, color = Pal.Gold)
                    PourEditor(
                        amount = s.total,
                        jars = JarCode.entries.map { PourJar(it, s.names[it] ?: it.name) },
                        shares = s.shares,
                        onChange = { sh -> vm.update { it.copy(shares = sh) } },
                        typeface = typeface,
                    )
                }
            }
        }
        val canNext = when (s.step) {
            2 -> s.percentSum == 100
            1 -> s.wallets.any { it.name.isNotBlank() }
            else -> true
        }
        PixelTextButton(
            if (s.step == 4) "Bắt đầu!" else "Tiếp",
            onClick = {
                if (s.step < 4) {
                    vm.next()
                } else if (Build.VERSION.SDK_INT >= 33) {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    vm.finish()
                }
            },
            enabled = canNext,
            modifier = Modifier.fillMaxWidth().padding(vertical = m.unit * 2),
        )
    }

    if (editWallet >= 0) {
        var amount by remember(editWallet) { mutableStateOf(AmountInput(s.wallets[editWallet].balance)) }
        PixelDialog(onDismiss = { editWallet = -1 }) {
            PixelText("Số dư ${s.wallets[editWallet].name}", style = type.title)
            PixelText(formatVnd(amount.value), style = type.display, modifier = Modifier.fillMaxWidth(), align = TextAlign.End)
            PixelKeypad(onKey = { amount = amount.press(it) })
            PixelTextButton("Xong", onClick = {
                val i = editWallet
                vm.update { st -> st.copy(wallets = st.wallets.toMutableList().also { it[i] = it[i].copy(balance = amount.value) }) }
                editWallet = -1
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatPicker(selected: Int, onPick: (Int) -> Unit) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { clock.longValue = it }
            delay(100)
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, maxItemsInEachRow = 3) {
        for (color in 1..6) {
            PixelPanel(
                Modifier.weight(1f).padding(m.unit).clickable { onPick(color) },
                sprite = if (color == selected) "ui/wood/nine_path_panel_3" else "ui/wood/nine_path_bg",
                extraPadding = 1,
            ) {
                Canvas(Modifier.fillMaxWidth().size(m.dp(30 * m.ui))) {
                    val s = atlas["cat/$color/sit"]
                    drawSprite(s, s.frameAt(clock.longValue), size.width / 2, size.height, scale = m.ui)
                }
            }
        }
    }
}
