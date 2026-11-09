package app.meowney.feature.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.BuyResult
import app.meowney.core.data.GameState
import app.meowney.core.game.Catalog
import app.meowney.core.game.Item
import app.meowney.core.game.SeasonEngine
import app.meowney.core.game.Slot
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.feature.room.Cat
import app.meowney.feature.room.CatLines
import app.meowney.feature.room.CatStage
import app.meowney.feature.room.Prop
import app.meowney.feature.room.RoomAssets
import app.meowney.feature.room.RoomPaint
import app.meowney.feature.room.look
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

enum class GiftTab(val label: String) {
    WHEEL("Vòng quay"), SHOP("Cửa hàng"), WARDROBE("Tủ đồ"), REAL("Thưởng thật"), TROPHY("Tủ cúp"), CHALLENGE("Thử thách"),
}

enum class ShopSection(val label: String) { FOOD("Đồ ăn"), TOY("Đồ chơi"), CORNER("Góc mèo"), CATS("Mèo"), SEASON("Theo mùa") }

class GiftViewModel(val c: AppContainer, initial: GiftTab) : ViewModel() {
    val tab = MutableStateFlow(initial)
    val section = MutableStateFlow(ShopSection.FOOD)
    val today: LocalDate = LocalDate.now()
    val season = SeasonEngine.seasonOn(today)
    val state: StateFlow<GameState> = c.game.state.stateIn(viewModelScope, SharingStarted.Eagerly, GameState())
    val cat: StateFlow<Pair<Int, String>> = c.settings.flow.map { it.catColor to it.catName }.stateIn(viewModelScope, SharingStarted.Eagerly, 5 to "")
    val adoptPrice = MutableStateFlow(1000)

    /** A line from the cat at the top of the screen. */
    val message = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            c.game.ensureStarter(today)
            adoptPrice.value = c.game.adoptPrice()
        }
    }

    fun say(text: String) {
        message.value = text
    }

    fun buy(item: Item, thenUse: Boolean) = viewModelScope.launch {
        when (val r = c.game.buy(item.id, today)) {
            is BuyResult.Ok -> {
                c.sfx.play(app.meowney.core.sound.Sfx.Sound.Coin)
                if (thenUse && item.slot != Slot.FOOD) c.game.equip(item.slot, item.id)
                say(CatLines.bought(item.name))
                checkAchievements()
            }
            is BuyResult.NotEnough -> say(CatLines.notEnough(r.missing))
            BuyResult.AlreadyOwned -> say("Sen có món này rồi mà.")
            BuyResult.NotForSale -> say("Món này hết mùa rồi, sang năm nhé.")
        }
    }

    fun equip(slot: Slot, id: String) = viewModelScope.launch { c.game.equip(slot, id) }

    fun feed(item: Item) = viewModelScope.launch {
        val r = c.game.feed(item.id, today) ?: return@launch
        say(CatLines.treat(r.itemName, r.affection))
        c.events.post(CatEvent.Treat(r.itemName))
        checkAchievements()
    }

    fun adopt(color: Int, name: String) = viewModelScope.launch {
        when (val r = c.game.adopt(color, name, today)) {
            is BuyResult.Ok -> {
                c.sfx.play(app.meowney.core.sound.Sfx.Sound.Success)
                say(CatLines.adopted(name.ifBlank { Catalog.catColors.first { it.first == color }.second }))
                adoptPrice.value = c.game.adoptPrice()
                checkAchievements()
            }
            is BuyResult.NotEnough -> say(CatLines.notEnough(r.missing))
            else -> Unit
        }
    }

    fun show(color: Int) = viewModelScope.launch { c.game.showCat(color) }
    fun rename(color: Int, name: String) = viewModelScope.launch { c.game.renameCat(color, name) }

    /** New trophies after spending: the cat announces the first one. */
    suspend fun checkAchievements() {
        val fresh = c.game.checkAchievements(today)
        fresh.firstOrNull()?.let { (a, tier) -> c.events.post(CatEvent.Coins(app.meowney.core.game.AchievementEngine.TIER_COINS[tier - 1], CatLines.trophy(a.name, tier))) }
    }
}

@Composable
fun GiftScreen(
    vm: GiftViewModel,
    corner: RoomAssets,
    onBack: () -> Unit,
    content: @Composable (GiftTab) -> Unit,
) {
    val tab by vm.tab.collectAsState()
    val game by vm.state.collectAsState()
    val message by vm.message.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    LaunchedEffect(message) {
        if (message != null) {
            delay(3_500)
            vm.message.value = null
        }
    }
    PixelScreen("Quà", onBack = onBack, actions = { CoinTag(game.coins) }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = m.unit * 2), horizontalArrangement = Arrangement.spacedBy(m.unit)) {
            for (t in GiftTab.entries) PixelChip(t.label, selected = tab == t) { vm.tab.value = t }
        }
        message?.let {
            PixelPanel(Modifier.fillMaxWidth().padding(bottom = m.unit * 2), sprite = "ui/wood/nine_path_panel_interior", extraPadding = 1) {
                PixelText(it, style = type.body, color = Pal.Cream)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                GiftTab.SHOP -> ShopTab(vm)
                GiftTab.WARDROBE -> WardrobeTab(vm, corner)
                else -> content(tab)
            }
        }
    }
}

@Composable
fun CoinTag(coins: Long) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    PixelPanel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit)) {
            val coin = atlas["coin/coin"]
            Canvas(Modifier.size(m.dp(coin.width * (m.ui - 1)), m.dp(coin.height * (m.ui - 1)))) { drawSprite(coin, 0, size.width / 2, size.height, scale = m.ui - 1) }
            PixelText("$coins", style = LocalPixelType.current.bodyBold, color = Pal.Ink)
        }
    }
}

// ------------------------------------------------------------------ item art

/**
 * A picture of an item in a [box] square: its sprite at the largest whole scale that fits,
 * or a drawn swatch for the slots that are colours (frame, wall, floor, rug).
 */
@Composable
fun ItemIcon(item: Item, box: Dp, catColor: Int = 5, modifier: Modifier = Modifier) {
    val atlas = LocalSprites.current
    Canvas(modifier.size(box)) {
        val side = size.minDimension
        when {
            item.slot == Slot.HAT -> {
                val cat = atlas["cat/$catColor/sit"]
                val k = max(1, (side / (cat.height + 6)).toInt())
                drawSprite(cat, 0, size.width / 2, size.height - k, scale = k)
                item.sprite?.let { atlas.getOrNull(it) }?.let { drawSprite(it, 0, size.width / 2, size.height - k, scale = k) }
            }
            item.sprite != null -> {
                val s = atlas.getOrNull(item.sprite) ?: return@Canvas
                val k = max(1, min(side / s.width, side / s.height).toInt().coerceAtMost(8))
                drawSprite(s, 0, size.width / 2, size.height / 2 + s.height * k / 2f, scale = k)
            }
            item.slot == Slot.FRAME -> {
                val t = item.tint ?: 0xFF8A5536
                val r = side * 0.38f
                drawCircle(Pal.WoodDark, r + side * 0.08f, center)
                drawCircle(RoomPaint.shade(t, 1.25f), r + side * 0.05f, center)
                drawCircle(RoomPaint.shade(t, 0.75f), r, center)
                drawCircle(Pal.Night, r - side * 0.05f, center)
            }
            item.slot == Slot.WALL || item.slot == Slot.FLOOR -> {
                val t = item.tint ?: if (item.slot == Slot.WALL) 0xFF7A8494 else 0xFFB59A72
                val u = side / 16f
                drawRect(Pal.WoodDark, Offset(u, u), Size(side - 2 * u, side - 2 * u))
                drawRect(RoomPaint.shade(t, 1f), Offset(2 * u, 2 * u), Size(side - 4 * u, side - 4 * u))
                val line = RoomPaint.shade(t, 0.65f)
                if (item.slot == Slot.WALL) {
                    for (row in 0 until 4) {
                        val y = 2 * u + row * 3 * u
                        drawRect(line, Offset(2 * u, y + 3 * u - u), Size(side - 4 * u, u))
                        val off = if (row % 2 == 0) 5 * u else 9 * u
                        drawRect(line, Offset(off, y), Size(u, 2 * u))
                    }
                } else {
                    for (col in 1 until 4) drawRect(line, Offset(2 * u + col * 3 * u, 2 * u), Size(u, side - 4 * u))
                }
            }
            item.slot == Slot.RUG && item.tint != null -> {
                val k = side / 40f
                translate(size.width / 2 - 32 * k, size.height / 2 - 56 * k) {
                    scale(k, pivot = Offset.Zero) { with(RoomPaint) { rug(item.tint) } }
                }
            }
            else -> {
                // "không / để trống": a small cross
                val u = side / 10f
                for (i in 3..6) {
                    drawRect(Pal.InkSoft, Offset(i * u, i * u), Size(u, u))
                    drawRect(Pal.InkSoft, Offset((9 - i) * u, i * u), Size(u, u))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ shop

private fun ShopSection.items(season: app.meowney.core.game.Season?): List<Item> = when (this) {
    ShopSection.FOOD -> Catalog.items.filter { it.slot == Slot.FOOD }
    ShopSection.TOY -> Catalog.items.filter { it.slot == Slot.TOY && !it.default }
    ShopSection.CORNER -> Catalog.items.filter { it.slot !in setOf(Slot.FOOD, Slot.TOY, Slot.HAT) && !it.default && it.season == null }
    ShopSection.SEASON -> if (season != null) Catalog.seasonal(season) else emptyList()
    ShopSection.CATS -> emptyList()
}

@Composable
private fun ShopTab(vm: GiftViewModel) {
    val section by vm.section.collectAsState()
    val game by vm.state.collectAsState()
    val cat by vm.cat.collectAsState()
    val adoptPrice by vm.adoptPrice.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var asking by remember { mutableStateOf<Item?>(null) }
    var adopting by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = m.unit * 2), horizontalArrangement = Arrangement.spacedBy(m.unit)) {
            for (s in ShopSection.entries) {
                if (s == ShopSection.SEASON && vm.season == null) continue
                PixelChip(if (s == ShopSection.SEASON) vm.season!!.label else s.label, selected = section == s) { vm.section.value = s }
            }
        }
        if (section == ShopSection.SEASON) {
            PixelText("Đồ mùa ${vm.season?.label} chỉ bán trong mùa, mua rồi giữ mãi.", style = type.caption, color = Pal.Paper, modifier = Modifier.padding(bottom = m.unit * 2))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(m.unit * 2),
            horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (section == ShopSection.CATS) {
                items(Catalog.catColors, key = { "cat${it.first}" }) { (color, label) ->
                    val pet = game.pets.firstOrNull { it.color == color }
                    ShopCell(
                        title = pet?.name ?: label,
                        price = if (pet != null) null else adoptPrice,
                        note = if (pet != null) (if (cat.first == color) "Đang ở phòng" else "Đã nuôi") else null,
                        affordable = game.coins >= adoptPrice,
                        onClick = { if (pet == null) adopting = color else vm.show(color) },
                    ) { CatIcon(color, m.unit * 26) }
                }
            } else {
                items(section.items(vm.season), key = { it.id }) { item ->
                    val owned = game.owns(item.id)
                    ShopCell(
                        title = item.name,
                        price = if (item.slot == Slot.FOOD || !owned) item.price else null,
                        note = when {
                            item.slot == Slot.FOOD && game.qty(item.id) > 0 -> "Có ${game.qty(item.id)}"
                            owned && game.equipped(item.slot) == item.id -> "Đang dùng"
                            owned -> "Đã có"
                            else -> null
                        },
                        affordable = game.coins >= item.price,
                        onClick = { if (item.slot != Slot.FOOD && owned) vm.equip(item.slot, item.id) else asking = item },
                    ) { ItemIcon(item, m.unit * 26, cat.first) }
                }
            }
        }
    }
    asking?.let { item ->
        BuyDialog(item, game.coins, cat.first, onDismiss = { asking = null }, onBuy = { use ->
            asking = null
            vm.buy(item, use)
        })
    }
    adopting?.let { color ->
        AdoptDialog(color, adoptPrice, game.coins, onDismiss = { adopting = null }, onAdopt = { name ->
            adopting = null
            vm.adopt(color, name)
        })
    }
}

@Composable
fun CatIcon(color: Int, box: Dp, anim: String = "sit") {
    val atlas = LocalSprites.current
    Canvas(Modifier.size(box)) {
        val s = atlas.getOrNull("cat/$color/$anim") ?: return@Canvas
        val k = max(1, min(size.width / s.width, size.height / s.height).toInt())
        drawSprite(s, 0, size.width / 2, size.height, scale = k)
    }
}

@Composable
private fun ShopCell(title: String, price: Int?, note: String?, affordable: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelPanel(Modifier.fillMaxWidth().clickable(onClick = onClick), sprite = "ui/wood/nine_path_panel_3", extraPadding = 0) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.unit)) {
            icon()
            PixelText(title, style = type.caption, color = Pal.Ink, align = TextAlign.Center, maxLines = 2)
            when {
                note != null && price == null -> PixelText(note, style = type.caption, color = Pal.Green, align = TextAlign.Center)
                price != null -> PixelText(
                    "$price xu" + (note?.let { " · $it" } ?: ""), style = type.bodyBold,
                    color = if (affordable) Pal.Ink else Pal.Red, align = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun BuyDialog(item: Item, coins: Long, catColor: Int, onDismiss: () -> Unit, onBuy: (use: Boolean) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            ItemIcon(item, m.unit * 34, catColor)
            Column(Modifier.weight(1f)) {
                PixelText(item.name, style = type.title, color = Pal.Ink)
                PixelText(item.slot.label + if (item.hearts > 0) " · +${item.hearts} tim" else "", style = type.caption, color = Pal.InkSoft)
                PixelText("${item.price} xu", style = type.bodyBold, color = Pal.Ink)
            }
        }
        if (coins < item.price) {
            PixelText("Còn thiếu ${item.price - coins} xu. Ghi sổ, làm thói quen để kiếm thêm nhé.", style = type.caption, color = Pal.Red)
            PixelTextButton("Để sau", onClick = onDismiss, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        } else if (item.slot == Slot.FOOD) {
            PixelTextButton("Mua", onClick = { onBuy(false) }, modifier = Modifier.fillMaxWidth())
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Mua", onClick = { onBuy(false) }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
                PixelTextButton("Mua + dùng", onClick = { onBuy(true) }, modifier = Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun AdoptDialog(color: Int, price: Int, coins: Long, onDismiss: () -> Unit, onAdopt: (String) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var name by remember { mutableStateOf("") }
    val label = Catalog.catColors.first { it.first == color }.second
    PixelDialog(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            CatIcon(color, m.unit * 34, "meow_sit")
            Column(Modifier.weight(1f)) {
                PixelText("Nhận nuôi $label", style = type.title, color = Pal.Ink)
                PixelText("$price xu", style = type.bodyBold, color = Pal.Ink)
            }
        }
        PixelTextField(name, { name = it.take(16) }, Modifier.fillMaxWidth(), placeholder = "Đặt tên (vd: Su, Bơ, Mochi)")
        if (coins < price) {
            PixelText("Còn thiếu ${price - coins} xu.", style = type.caption, color = Pal.Red)
            PixelTextButton("Để sau", onClick = onDismiss, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        } else {
            PixelTextButton("Nhận nuôi", onClick = { onAdopt(name.trim()) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------ wardrobe

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WardrobeTab(vm: GiftViewModel, corner: RoomAssets) {
    val game by vm.state.collectAsState()
    val cat by vm.cat.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val look = game.look(vm.season)
    val preview = remember(cat.first) { Cat(cat.first, corner.circle, corner.spots) }
    LaunchedEffect(preview, look) {
        preview.propSprites = mapOf(Prop.Bed to look.bed, Prop.Bowl to look.bowl, Prop.Yarn to look.toy)
        preview.hat = look.hat
    }
    var renaming by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(m.unit)) {
                CatStage(corner = corner, cat = preview, scale = m.ui + 1, onCatTap = { preview.poke() }, onCatLongPress = {}, look = look)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    val pet = game.pets.firstOrNull { it.color == cat.first }
                    PixelText(cat.second, style = type.title, color = Pal.Cream)
                    pet?.let { PixelText("${it.affection} tim", style = type.bodyBold, color = Pal.Red) }
                    PixelTextButton("Đổi tên", onClick = { renaming = true }, look = ButtonLook.Light)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelText("MÈO CỦA SEN", style = type.caption, color = Pal.Paper)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    for (p in game.pets) {
                        Column(
                            Modifier.border(m.unit, if (p.color == cat.first) Pal.Orange else Pal.WoodDark).clickable { vm.show(p.color) }.padding(m.unit),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CatIcon(p.color, m.unit * 20)
                            PixelText(p.name, style = type.caption, color = Pal.Cream, maxLines = 1)
                        }
                    }
                    if (game.pets.size < 6) {
                        PixelTextButton("+ Nuôi thêm", onClick = { vm.section.value = ShopSection.CATS; vm.tab.value = GiftTab.SHOP }, look = ButtonLook.Light)
                    }
                }
            }
        }
        val treats = Catalog.treats.filter { game.qty(it.id) > 0 }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelText("ĐỒ ĂN", style = type.caption, color = Pal.Paper)
                if (treats.isEmpty()) PixelText("Chưa có món nào. Mua ở Cửa hàng hoặc quay trúng nhé.", style = type.caption, color = Pal.Paper)
                for (t in treats) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        ItemIcon(t, m.unit * 14)
                        PixelText("${t.name} x${game.qty(t.id)}", style = type.body, color = Pal.Cream, modifier = Modifier.weight(1f))
                        PixelTextButton("Cho ăn", onClick = { vm.feed(t); preview.feed() })
                    }
                }
            }
        }
        for (slot in Catalog.decorSlots) {
            val options = Catalog.items.filter { it.slot == slot && game.owns(it.id) }
            item(key = slot.name) {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    PixelText(slot.label.uppercase(), style = type.caption, color = Pal.Paper)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        for (o in options) {
                            val on = game.equipped(slot) == o.id
                            Column(
                                Modifier.width(m.unit * 28).border(m.unit, if (on) Pal.Orange else Pal.WoodDark).clickable { vm.equip(slot, o.id) }.padding(m.unit),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ItemIcon(o, m.unit * 18, cat.first)
                                PixelText(o.name, style = type.caption, color = if (on) Pal.Gold else Pal.Cream, align = TextAlign.Center, maxLines = 2)
                            }
                        }
                        if (options.size <= 1) {
                            PixelText("Mua thêm ở Cửa hàng", style = type.caption, color = Pal.Paper, modifier = Modifier.clickable {
                                vm.section.value = if (slot == Slot.TOY) ShopSection.TOY else ShopSection.CORNER
                                vm.tab.value = GiftTab.SHOP
                            })
                        }
                    }
                }
            }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(cat.second) }
        PixelDialog(onDismiss = { renaming = false }) {
            PixelText("Tên mèo", style = type.title, color = Pal.Ink)
            PixelTextField(name, { name = it.take(16) }, Modifier.fillMaxWidth())
            PixelTextButton("Lưu", onClick = { vm.rename(cat.first, name); renaming = false }, modifier = Modifier.fillMaxWidth())
        }
    }
}
