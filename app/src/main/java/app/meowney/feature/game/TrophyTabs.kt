package app.meowney.feature.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.meowney.core.data.ChallengeView
import app.meowney.core.data.QuestView
import app.meowney.core.game.AchGroup
import app.meowney.core.game.Achievement
import app.meowney.core.game.AchievementEngine
import app.meowney.core.game.Catalog
import app.meowney.core.game.ChallengeEngine
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelProgress
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import kotlin.math.max

private val BRONZE = Color(0xFFF0A868)

/** A cup for a tier: 0 = not yet (faded), 1 bronze, 2 silver, 3 gold. */
@Composable
fun Cup(tier: Int, box: Dp) {
    val atlas = LocalSprites.current
    Canvas(Modifier.size(box)) {
        val s = atlas.getOrNull(if (tier == 3) "treasure/goldcup" else "treasure/silvercup") ?: return@Canvas
        val k = max(1, (size.minDimension / max(s.width, s.height)).toInt())
        val w = s.width * k
        val h = s.height * k
        drawImage(
            s.page, s.srcOffset(0), s.srcSize,
            dstOffset = IntOffset(((size.width - w) / 2).toInt(), ((size.height - h) / 2).toInt()), dstSize = IntSize(w, h),
            alpha = if (tier == 0) 0.25f else 1f,
            colorFilter = if (tier == 1) ColorFilter.tint(BRONZE, BlendMode.Modulate) else null,
            filterQuality = FilterQuality.None,
        )
    }
}

@Composable
fun TrophyTab(vm: GiftViewModel) {
    val have by vm.trophies.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    LaunchedEffect(Unit) { vm.loadMetrics() }
    val best = have.groupBy { it.id }.mapValues { (_, l) -> l.maxOf { it.tier } }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        item {
            val cups = best.values.sum()
            PixelText("Tủ cúp: ${best.size}/${AchievementEngine.all.size} thành tựu · $cups/${AchievementEngine.all.size * 3} bậc", style = type.bodyBold, color = Pal.Gold)
            PixelText("Bậc Đồng / Bạc / Vàng thưởng 20 / 50 / 150 xu.", style = type.caption, color = Pal.Paper)
        }
        for (g in AchGroup.entries) {
            item(key = g.name) { PixelText(g.label.uppercase(), style = type.caption, color = Pal.Paper) }
            items(AchievementEngine.all.filter { it.group == g }, key = { it.id }) { a ->
                TrophyRow(a, best[a.id] ?: 0, metrics[a.metric])
            }
        }
    }
}

@Composable
private fun TrophyRow(a: Achievement, tier: Int, value: Long?) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3", extraPadding = 1) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            Cup(tier, m.unit * 16)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                Row {
                    PixelText(a.name, style = type.bodyBold, color = if (tier > 0) Pal.Ink else Pal.InkSoft, modifier = Modifier.weight(1f), maxLines = 1)
                    if (tier > 0) PixelText(AchievementEngine.TIER_NAMES[tier - 1], style = type.caption, color = if (tier == 1) BRONZE else if (tier == 2) Pal.InkSoft else Pal.Orange)
                }
                val next = a.tiers.getOrNull(tier)
                if (next != null) {
                    PixelText(a.text.format(next) + (value?.let { " · $it/$next" } ?: ""), style = type.caption, color = Pal.InkSoft, maxLines = 2)
                    PixelProgress(((value ?: 0).toFloat() / next).coerceIn(0f, 1f), heightPx = 4, color = Pal.Gold)
                } else {
                    PixelText(a.text.format(a.tiers.last()) + " · đủ cả 3 bậc!", style = type.caption, color = Pal.Green)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ monthly challenge + season quest

@Composable
fun ChallengeTab(vm: GiftViewModel) {
    val view by vm.challenge.collectAsState()
    val quest by vm.quest.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    LaunchedEffect(Unit) { vm.loadChallenge() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
        view?.let { v -> item { ChallengeCard(v, vm) } }
        quest?.let { q -> item { QuestCard(q, vm) } }
        item {
            PixelText(
                "Mỗi tháng một thử thách, xong thì mở rương lớn: ${ChallengeEngine.CHEST_COINS} xu + 2 món ăn cho mèo.",
                style = type.caption, color = Pal.Paper,
            )
        }
    }
}

@Composable
private fun ChallengeCard(v: ChallengeView, vm: GiftViewModel) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val atlas = LocalSprites.current
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        PixelText("THỬ THÁCH THÁNG ${v.month.monthValue}", style = type.caption, color = Pal.Gold)
        val picked = v.picked
        if (picked == null) {
            PixelText("Trẫm có 3 gợi ý, Sen chọn 1 nhé (chọn rồi không đổi được):", style = type.body, color = Pal.Cream)
            v.options.forEachIndexed { i, o ->
                PixelPanel(Modifier.fillMaxWidth().clickable { vm.pickChallenge(i) }, sprite = "ui/wood/nine_path_panel_3") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PixelText(o.label, style = type.bodyBold, color = Pal.Ink, modifier = Modifier.weight(1f))
                        PixelTextButton("Chọn", onClick = { vm.pickChallenge(i) })
                    }
                }
            }
        } else {
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                    Box(Modifier.size(m.unit * 24), contentAlignment = Alignment.Center) {
                        val chest = atlas.getOrNull(if (v.claimed) "chest/chest_open" else "treasure/bigtreasurechest")
                        if (chest != null) app.meowney.feature.money.SpriteView(if (v.claimed) "chest/chest_open" else "treasure/bigtreasurechest", scale = m.ui, frame = 0)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                        PixelText(picked.label, style = type.bodyBold, color = Pal.Ink)
                        PixelProgress(v.progress, color = if (v.done) Pal.Green else Pal.Gold)
                        PixelText(
                            when {
                                v.claimed -> "Đã mở rương. Tháng sau trẫm ra đề mới."
                                v.done -> "Xong rồi! Mở rương thôi."
                                ChallengeEngine.settledAtMonthEnd(picked.kind) -> if (v.value >= picked.target) "Đang giữ được, cuối tháng chấm." else "Tháng này đã có mượn lọ."
                                else -> "${v.value}/${picked.target}"
                            },
                            style = type.caption, color = Pal.InkSoft,
                        )
                    }
                }
            }
            if (v.done && !v.claimed) PixelTextButton("Mở rương", onClick = { vm.claimChallenge() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun QuestCard(q: QuestView, vm: GiftViewModel) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val reward = Catalog[q.quest.rewardItem]
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        PixelText("NHIỆM VỤ ${q.quest.season.label.uppercase()}", style = type.caption, color = Pal.Gold)
        PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                reward?.let { ItemIcon(it, m.unit * 22) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    PixelText(q.quest.label, style = type.bodyBold, color = Pal.Ink)
                    PixelProgress((q.value.toFloat() / q.quest.target).coerceIn(0f, 1f), color = if (q.done) Pal.Green else Pal.Gold)
                    PixelText(
                        if (q.claimed) "Đã nhận ${reward?.name}." else "${minOf(q.value, q.quest.target)}/${q.quest.target} · thưởng: ${reward?.name}",
                        style = type.caption, color = Pal.InkSoft,
                    )
                }
            }
        }
        if (q.done && !q.claimed) PixelTextButton("Nhận ${reward?.name}", onClick = { vm.claimQuest() }, look = ButtonLook.Orange, modifier = Modifier.fillMaxWidth())
    }
}
