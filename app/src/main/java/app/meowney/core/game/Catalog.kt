package app.meowney.core.game

/** Where an item goes. FOOD is used up; every other kind is kept and shown in its slot. */
enum class Slot(val label: String) {
    FOOD("Đồ ăn"),
    TOY("Đồ chơi"),
    BOWL("Bát ăn"),
    BED("Ổ ngủ"),
    TOWER("Tháp mèo"),
    FRAME("Khung tròn"),
    WALL("Tường"),
    FLOOR("Sàn"),
    RUG("Thảm"),
    DECOR("Góc trang trí"),
    HAT("Mũ"),
}

enum class Season(val label: String) { TET("Tết"), HALLOWEEN("Halloween"), NOEL("Noel"), AUTUMN("Mùa thu") }

/**
 * One thing in the shop. [sprite] is an atlas key; [tint] an ARGB colour for slots drawn
 * in code (frame, wall, floor, rug). [hearts] is what a treat adds to the cat's affection.
 * [season] items are sold only in that season (kept forever once bought).
 */
data class Item(
    val id: String,
    val name: String,
    val slot: Slot,
    val price: Int,
    val sprite: String? = null,
    val tint: Long? = null,
    val hearts: Int = 0,
    val season: Season? = null,
    /** Given for free (the starting set). */
    val default: Boolean = false,
)

object Catalog {
    val items: List<Item> = listOf(
        // treats
        Item("food_kibble", "Hạt giòn", Slot.FOOD, 10, sprite = "prop/pet_food_blue", hearts = 1),
        Item("food_fish", "Cá tươi", Slot.FOOD, 20, sprite = "crop/fish", hearts = 2),
        Item("food_pate", "Pate", Slot.FOOD, 30, sprite = "prop/foodbowl", hearts = 3),
        // toys (the ball the cat plays with)
        Item("toy_yarn", "Cuộn len đỏ", Slot.TOY, 0, sprite = "prop/yarnball", default = true),
        Item("toy_yarn_blue", "Cuộn len xanh", Slot.TOY, 60, sprite = "prop/pet_yarn_blue"),
        Item("toy_yarn_green", "Cuộn len lục", Slot.TOY, 60, sprite = "prop/pet_yarn_green"),
        Item("toy_ball_red", "Bóng đỏ", Slot.TOY, 80, sprite = "prop/pet_ball_red"),
        Item("toy_ball_green", "Bóng lục", Slot.TOY, 80, sprite = "prop/pet_ball_green"),
        Item("toy_fish", "Cá nhồi bông", Slot.TOY, 100, sprite = "prop/pet_fish"),
        Item("toy_bone", "Xương gặm", Slot.TOY, 120, sprite = "prop/pet_bone"),
        // bowl
        Item("bowl_red", "Bát đỏ", Slot.BOWL, 0, sprite = "prop/pet_food_red", default = true),
        Item("bowl_blue", "Bát xanh", Slot.BOWL, 200, sprite = "prop/pet_food_blue"),
        Item("bowl_green", "Bát lục", Slot.BOWL, 200, sprite = "prop/pet_food_green"),
        // bed
        Item("bed_cushion", "Ổ nệm", Slot.BED, 0, sprite = "prop/catbed", default = true),
        Item("bed_box", "Hộp các-tông", Slot.BED, 250, sprite = "prop/cardboardbox"),
        Item("bed_round", "Ổ tròn", Slot.BED, 300, sprite = "prop/pet_bed"),
        Item("bed_fish", "Ổ cá", Slot.BED, 400, sprite = "prop/pet_bed_fish"),
        // tower (the habits toy)
        Item("tower_white", "Tháp trắng", Slot.TOWER, 0, sprite = "prop/tower_white", default = true),
        Item("tower_grey", "Tháp xám", Slot.TOWER, 400, sprite = "prop/tower_grey"),
        Item("tower_green", "Tháp lục", Slot.TOWER, 500, sprite = "prop/tower_green"),
        // round frame
        Item("frame_wood", "Khung gỗ", Slot.FRAME, 0, tint = 0xFF8A5536, default = true),
        Item("frame_pink", "Khung hồng", Slot.FRAME, 300, tint = 0xFFE88AA8),
        Item("frame_blue", "Khung xanh", Slot.FRAME, 300, tint = 0xFF4F86C6),
        Item("frame_silver", "Khung bạc", Slot.FRAME, 400, tint = 0xFFB8C0CC),
        Item("frame_gold", "Khung vàng", Slot.FRAME, 500, tint = 0xFFF2C14E),
        // walls and floor: a tone over the stone and the planks
        Item("wall_stone", "Tường đá", Slot.WALL, 0, default = true),
        Item("wall_warm", "Tường đá ấm", Slot.WALL, 300, tint = 0xFFC98A4B),
        Item("wall_moss", "Tường rêu", Slot.WALL, 300, tint = 0xFF7FA35A),
        Item("wall_rose", "Tường hồng", Slot.WALL, 350, tint = 0xFFE09AAE),
        Item("wall_night", "Tường đêm", Slot.WALL, 400, tint = 0xFF5E4A7A),
        Item("floor_oak", "Sàn gỗ sồi", Slot.FLOOR, 0, default = true),
        Item("floor_honey", "Sàn mật ong", Slot.FLOOR, 250, tint = 0xFFE8B77A),
        Item("floor_dark", "Sàn gỗ tối", Slot.FLOOR, 250, tint = 0xFF6B4A36),
        Item("floor_ash", "Sàn tro", Slot.FLOOR, 300, tint = 0xFFB0A89C),
        // rug, drawn in code
        Item("rug_none", "Không thảm", Slot.RUG, 0, default = true),
        Item("rug_blue", "Thảm xanh", Slot.RUG, 350, tint = 0xFF4A5AA8),
        Item("rug_red", "Thảm đỏ", Slot.RUG, 350, tint = 0xFFB2453E),
        Item("rug_green", "Thảm lục", Slot.RUG, 350, tint = 0xFF4E8A4E),
        // corner decoration
        Item("decor_none", "Để trống", Slot.DECOR, 0, default = true),
        Item("decor_vase", "Bình hoa", Slot.DECOR, 250, sprite = "decor/vase"),
        Item("decor_pot", "Chậu cây", Slot.DECOR, 250, sprite = "decor/pot"),
        Item("decor_flower", "Chậu hoa trắng", Slot.DECOR, 300, sprite = "decor/flower"),
        Item("decor_jug", "Bình gốm", Slot.DECOR, 200, sprite = "decor/jug"),
        Item("decor_herbs", "Bó lá treo", Slot.DECOR, 300, sprite = "decor/herbs"),
        // seasonal
        Item("hat_none", "Không đội", Slot.HAT, 0, default = true),
        Item("hat_santa", "Mũ Noel", Slot.HAT, 250, sprite = "hat/santa/sit", season = Season.NOEL),
        Item("hat_santa2", "Mũ Noel lông", Slot.HAT, 300, sprite = "hat/santa2/sit", season = Season.NOEL),
        Item("hat_antlers", "Sừng tuần lộc", Slot.HAT, 300, sprite = "hat/antlers/sit", season = Season.NOEL),
        Item("decor_pumpkin", "Bí ngô", Slot.DECOR, 200, sprite = "crop/pumpkin_05", season = Season.HALLOWEEN),
        Item("decor_lantern", "Đèn lồng đỏ", Slot.DECOR, 250, sprite = "decor/lantern", season = Season.TET),
        Item("decor_sunflower", "Hoa hướng dương", Slot.DECOR, 200, sprite = "crop/sunflower_05", season = Season.AUTUMN),
    )

    private val byId = items.associateBy { it.id }

    operator fun get(id: String): Item? = byId[id]

    fun defaultFor(slot: Slot): Item? = items.firstOrNull { it.slot == slot && it.default }

    /** Slots the cat's corner shows (FOOD is not a slot). */
    val decorSlots = listOf(Slot.FRAME, Slot.WALL, Slot.FLOOR, Slot.RUG, Slot.BED, Slot.BOWL, Slot.TOY, Slot.DECOR, Slot.TOWER, Slot.HAT)

    /** The 6 cat colours, with a short name for the shop. */
    val catColors = listOf(1 to "Mèo cam", 2 to "Mèo đen", 3 to "Mèo kem", 4 to "Mèo hồng", 5 to "Mèo trắng", 6 to "Mèo xám")

    /** A new cat costs more the more cats there are: 1.000, 1.500, 2.000, 2.500... */
    fun catPrice(owned: Int): Int = (1000 + (owned - 1).coerceAtLeast(0) * 500).coerceAtMost(2500)

    /** Items of a season that the shop sells only then. */
    fun seasonal(season: Season) = items.filter { it.season == season }

    /** Things the wheel's rare slot can give: kept decorations up to 300 coins, not seasonal. */
    val wheelRares get() = items.filter { !it.default && it.season == null && it.slot != Slot.FOOD && it.price <= 300 }

    val treats get() = items.filter { it.slot == Slot.FOOD }
}
