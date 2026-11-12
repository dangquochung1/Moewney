package app.meowney.feature.entry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import app.meowney.core.db.CategoryEntity
import app.meowney.core.format.formatVnd
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
import java.time.LocalDate

private enum class Picker { Wallet, ToWallet, Jar, Date, Note }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(vm: EntryViewModel, onBack: () -> Unit, onDone: () -> Unit, onPour: (EntryNav.Pour) -> Unit) {
    val s by vm.state.collectAsState()
    val pending by vm.pending.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var picker by remember { mutableStateOf<Picker?>(null) }

    LaunchedEffect(vm) {
        vm.nav.collect {
            when (it) {
                EntryNav.Done -> onDone()
                is EntryNav.Pour -> onPour(it)
            }
        }
    }

    val title = when {
        s.editing != null -> "Sửa giao dịch"
        else -> "Ghi sổ"
    }
    PixelScreen(title, onBack = onBack, actions = {
        if (s.editing != null) PixelTextButton("Xoá", onClick = vm::delete, look = ButtonLook.Light)
    }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                for (mode in EntryMode.entries) {
                    if (s.editing != null && mode != s.mode) continue
                    PixelChip(
                        when (mode) {
                            EntryMode.EXPENSE -> "Chi"
                            EntryMode.INCOME -> "Thu"
                            EntryMode.TRANSFER -> "Chuyển"
                        },
                        selected = s.mode == mode,
                    ) { vm.mode(mode) }
                }
            }
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                PixelText(
                    (if (s.mode == EntryMode.INCOME) "+" else if (s.mode == EntryMode.EXPENSE) "-" else "") + formatVnd(s.amount.value),
                    style = type.display,
                    color = when (s.mode) {
                        EntryMode.INCOME -> Pal.Green
                        EntryMode.EXPENSE -> Pal.Ink
                        EntryMode.TRANSFER -> Pal.Sky
                    },
                    align = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelChip(if (s.mode == EntryMode.TRANSFER) "Từ: ${s.walletName(s.walletId)}" else s.walletName(s.walletId)) { picker = Picker.Wallet }
                if (s.mode == EntryMode.TRANSFER) PixelChip("Đến: ${s.walletName(s.toWalletId)}") { picker = Picker.ToWallet }
                if (s.mode == EntryMode.EXPENSE) PixelChip(s.jarName(s.jarOverride) ?: "Lọ theo danh mục") { picker = Picker.Jar }
                PixelChip(dayLabel(s.date)) { picker = Picker.Date }
                PixelChip(if (s.note.isBlank()) "+ ghi chú" else s.note) { picker = Picker.Note }
            }
            s.message?.let { PixelText(it, style = type.body, color = Pal.Gold) }
            if (s.mode != EntryMode.TRANSFER) {
                CategoryGrid(s.visibleCategories, s.categoryId.takeIf { s.editing != null }, onPick = vm::category)
            }
        }
        PixelKeypad(onKey = vm::key, modifier = Modifier.padding(vertical = m.unit * 2))
        if (s.mode == EntryMode.TRANSFER || s.editing != null) {
            PixelTextButton(
                if (s.mode == EntryMode.TRANSFER) "Chuyển" else "Lưu",
                onClick = vm::saveEdit,
                modifier = Modifier.fillMaxWidth().padding(bottom = m.unit * 2),
            )
        }
    }

    pending?.let { p -> BorrowDialog(p, s.jars.associate { it.id to it.name }, onConfirm = vm::confirmBorrow, onDismiss = vm::dismissBorrow) }

    when (picker) {
        Picker.Wallet, Picker.ToWallet -> PixelDialog(onDismiss = { picker = null }) {
            PixelText("Chọn ví", style = type.title)
            for (w in s.activeWallets) {
                PixelTextButton(w.name, onClick = {
                    if (picker == Picker.Wallet) vm.wallet(w.id) else vm.toWallet(w.id)
                    picker = null
                }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
            }
        }
        Picker.Jar -> PixelDialog(onDismiss = { picker = null }) {
            PixelText("Trả từ lọ nào?", style = type.title)
            PixelTextButton("Theo danh mục", onClick = { vm.jar(null); picker = null }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
            for (j in s.jars) {
                PixelTextButton(j.name, onClick = { vm.jar(j.id); picker = null }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
            }
        }
        Picker.Date -> PixelDialog(onDismiss = { picker = null }) {
            PixelText("Ngày nào?", style = type.title)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("<", onClick = { vm.date(s.date.minusDays(1)) }, look = ButtonLook.Light)
                PixelText(dayLabel(s.date), style = type.bodyBold, modifier = Modifier.width(m.unit * 50), align = TextAlign.Center)
                PixelTextButton(">", onClick = { if (s.date < LocalDate.now()) vm.date(s.date.plusDays(1)) }, look = ButtonLook.Light)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelChip("Hôm nay") { vm.date(LocalDate.now()); picker = null }
                PixelChip("Hôm qua") { vm.date(LocalDate.now().minusDays(1)); picker = null }
            }
            PixelTextButton("Xong", onClick = { picker = null })
        }
        Picker.Note -> PixelDialog(onDismiss = { picker = null }) {
            PixelText("Ghi chú", style = type.title)
            PixelTextField(s.note, vm::note, Modifier.fillMaxWidth(), placeholder = "vd: cà phê với Lan")
            PixelTextButton("Xong", onClick = { picker = null })
        }
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryGrid(categories: List<CategoryEntity>, selected: Long?, onPick: (CategoryEntity) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        maxItemsInEachRow = 4,
    ) {
        for (cat in categories) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onPick(cat) }
                    .padding(vertical = m.unit),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                RavenIcon(cat.icon, scale = m.ui)
                PixelText(
                    cat.name,
                    style = type.caption,
                    color = if (cat.id == selected) Pal.Gold else Pal.Ink,
                    align = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        // keep the last row's cells the same width
        repeat((4 - categories.size % 4) % 4) { Spacer(Modifier.weight(1f)) }
    }
}

fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Hôm nay"
        today.minusDays(1) -> "Hôm qua"
        else -> "%02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year)
    }
}
