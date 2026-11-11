package app.meowney.feature.game

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.meowney.core.db.RealRewardEntity
import app.meowney.core.db.VoucherEntity
import app.meowney.core.format.formatVnd
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.shortDate
import java.time.LocalDate

/** Real-life treats Sen prices in coins; redeemed ones wait as vouchers ("Dùng" logs the expense). */
@Composable
fun RewardsTab(vm: GiftViewModel, onUse: (VoucherEntity) -> Unit) {
    val rewards by vm.rewards.collectAsState()
    val vouchers by vm.vouchers.collectAsState()
    val game by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var editing by remember { mutableStateOf<RealRewardEntity?>(null) }
    var confirm by remember { mutableStateOf<RealRewardEntity?>(null) }
    val open = vouchers.filter { it.usedDay == null }
    val used = vouchers.filter { it.usedDay != null }.take(10)

    LazyColumn(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        item {
            PixelText(
                "Sen tự đặt giá cho những món tự thưởng ngoài đời. Đổi xu lấy phiếu, khi dùng thì ghi luôn khoản chi.",
                style = type.caption, color = Pal.InkSoft,
            )
        }
        if (open.isNotEmpty()) {
            item { PixelText("PHIẾU CỦA SEN", style = type.caption, color = Pal.Gold) }
            items(open, key = { "v${it.id}" }) { v ->
                PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        Column(Modifier.weight(1f)) {
                            PixelText(v.name, style = type.bodyBold, color = Pal.Ink)
                            PixelText("đổi ${shortDate(LocalDate.ofEpochDay(v.boughtDay))}" + if (v.amount > 0) " · ${formatVnd(v.amount)}" else "", style = type.caption, color = Pal.InkSoft)
                        }
                        PixelTextButton("Dùng", onClick = { vm.useVoucher(v); onUse(v) })
                    }
                }
            }
        }
        item { PixelText("PHẦN THƯỞNG", style = type.caption, color = Pal.Gold) }
        if (rewards.isEmpty()) {
            item { PixelText("Chưa có phần thưởng nào. Thử \"Trà sữa\" 300 xu, \"Xem phim\" 1.000 xu...", style = type.body, color = Pal.InkSoft) }
        }
        items(rewards, key = { "r${it.id}" }) { r ->
            PixelPanel(Modifier.fillMaxWidth().clickable { editing = r }, sprite = "ui/wood/nine_path_panel_3") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    Column(Modifier.weight(1f)) {
                        PixelText(r.name, style = type.bodyBold, color = Pal.Ink)
                        PixelText("${r.coins} xu" + if (r.amount > 0) " · ${formatVnd(r.amount)}" else "", style = type.caption, color = if (game.coins >= r.coins) Pal.InkSoft else Pal.Red)
                    }
                    PixelTextButton("Đổi", enabled = game.coins >= r.coins, onClick = { confirm = r })
                }
            }
        }
        item {
            PixelTextButton("+ Thêm phần thưởng", onClick = { editing = RealRewardEntity(name = "", coins = 300) }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        }
        if (used.isNotEmpty()) {
            item { PixelText("ĐÃ DÙNG", style = type.caption, color = Pal.InkSoft) }
            items(used, key = { "u${it.id}" }) { v ->
                PixelText("${v.name} · ${shortDate(LocalDate.ofEpochDay(v.usedDay!!))}", style = type.caption, color = Pal.InkSoft)
            }
        }
    }

    editing?.let { r ->
        RewardEditDialog(vm, r, onDismiss = { editing = null })
    }
    confirm?.let { r ->
        PixelDialog(onDismiss = { confirm = null }) {
            PixelText("Đổi ${r.coins} xu lấy \"${r.name}\"?", style = type.bodyBold, color = Pal.Ink)
            PixelText("Phiếu nằm trong mục Phiếu của Sen, dùng lúc nào cũng được.", style = type.caption, color = Pal.InkSoft)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Thôi", onClick = { confirm = null }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
                PixelTextButton("Đổi", onClick = { vm.redeem(r); confirm = null }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RewardEditDialog(vm: GiftViewModel, r: RealRewardEntity, onDismiss: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val categories by vm.expenseCategories.collectAsState()
    var name by remember { mutableStateOf(r.name) }
    var coins by remember { mutableStateOf(r.coins.toString()) }
    var amount by remember { mutableStateOf(if (r.amount > 0) (r.amount / 1000).toString() else "") }
    var category by remember { mutableStateOf(r.categoryId) }
    PixelDialog(onDismiss) {
        PixelText(if (r.id == 0L) "Phần thưởng mới" else "Sửa phần thưởng", style = type.title, color = Pal.Ink)
        PixelTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), placeholder = "vd: Trà sữa, Xem phim, Mua sách")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText("Giá", style = type.body, color = Pal.Ink)
            PixelTextField(coins, { coins = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), numeric = true)
            PixelText("xu", style = type.body, color = Pal.Ink)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelText("Tiền thật", style = type.body, color = Pal.Ink)
            PixelTextField(amount, { amount = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), placeholder = "0", numeric = true)
            PixelText("nghìn đ", style = type.body, color = Pal.Ink)
        }
        PixelText("Danh mục khi ghi chi", style = type.caption, color = Pal.InkSoft)
        Column(Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (cat in categories) PixelChip(cat.name, selected = category == cat.id) { category = if (category == cat.id) null else cat.id }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            if (r.id != 0L) PixelTextButton("Xoá", onClick = { vm.archiveReward(r); onDismiss() }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
            PixelTextButton("Lưu", enabled = name.isNotBlank() && (coins.toIntOrNull() ?: 0) > 0, modifier = Modifier.weight(1f), onClick = {
                vm.saveReward(r.copy(name = name.trim(), coins = coins.toInt(), amount = (amount.toLongOrNull() ?: 0) * 1000, categoryId = category))
                onDismiss()
            })
        }
    }
}
