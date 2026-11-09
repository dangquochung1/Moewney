package app.meowney.feature.room

import kotlin.random.Random

/**
 * What the cat says. Voice: "Hoàng thượng – Sen" - the cat is royalty ("trẫm"),
 * the user is its servant ("Sen"). Teasing, never mean.
 */
object CatLines {
    private val tap = listOf(
        "Meo~ Sen gọi trẫm à?",
        "Trẫm đang bận liếm lông.",
        "Sen ghi sổ hôm nay chưa đấy?",
        "Vuốt nhẹ thôi, trẫm là hoàng thượng đấy.",
        "Hạt đâu? Sen tiết kiệm cho trẫm mua hạt xịn nhé.",
        "Trẫm ngủ đủ 16 tiếng rồi, giờ trẫm ngắm Sen làm việc.",
    )

    private val fed = listOf(
        "Măm măm! Sen ghi %s %s rồi nha.",
        "Ghi sổ đều thế này, trẫm cho Sen thêm một tiếng meo.",
        "%s %s à? Ghi đủ là trẫm vui, tiêu lố cũng phải ghi đó.",
    )

    private val toy = mapOf(
        Toy.Money to "Hũ xu của Sen. Giai đoạn 1 mới rót tiền vào 6 lọ được.",
        Toy.Habits to "Xong thói quen là trẫm được vờn len!",
        Toy.Calendar to "Lịch của Sen nè.",
        Toy.Notes to "Sổ tay của Sen đó. Trẫm không đọc trộm đâu... chắc vậy.",
        Toy.Gift to "Quà và cửa hàng mở ở giai đoạn 6. Để dành xu đi Sen.",
    )

    fun greeting(hour: Int, name: String = "trẫm"): String = when (hour) {
        in 5..10 -> "Chào buổi sáng, Sen! $name đây. Hôm nay liệu mà tiêu."
        in 11..13 -> "Trưa rồi, Sen ăn gì nhớ ghi sổ nha."
        in 14..17 -> "Chiều rồi. Sen còn bao nhiêu tiền thế?"
        in 18..21 -> "Tối rồi, Sen ghi lại chi tiêu hôm nay đi."
        else -> "Khuya rồi, Sen ngủ đi. $name cũng ngủ đây... zZ"
    }

    fun poured(amount: String) = "Có tiền vào! Trẫm rót $amount vào 6 lọ rồi đó."

    fun worriedLoans() = "Lọ này đang nợ lọ kia đó Sen. Khoản thu sau trẫm trả lại trước."

    fun evening() = listOf(
        "Sen ơi, hôm nay chưa ghi sổ. Tiêu gì thì khai mau!",
        "Trẫm chờ Sen ghi sổ nãy giờ. Hôm nay tiêu những gì?",
        "Hôm nay không tiêu gì thì bấm 'không tiêu' cũng được xu đó.",
    ).random()

    fun onTap(random: Random = Random.Default) = tap.random(random)

    fun fed(label: String, amount: String, random: Random = Random.Default): String {
        val line = fed.random(random)
        return if (line.count { it == '%' } == 2) line.format(label, amount) else line
    }

    /** [toy] null = the big "+ Ghi chi tiêu" button. */
    fun onToy(toy: Toy?): String =
        if (toy == null) "Nút ghi chi tiêu. Giai đoạn 1 trẫm mới cho Sen bấm thật." else toy.let { this.toy.getValue(it) }

    fun sleepToggle(asleep: Boolean) =
        if (asleep) "Trẫm đi ngủ đây... zZ (giữ lâu lần nữa để gọi trẫm dậy)" else "Ai đánh thức trẫm đấy? Hừm."

    fun backup(days: Int) = "Sen ơi, $days ngày chưa sao lưu rồi. Lỡ mất máy là mất hết sổ đó!"

    fun habitDone(name: String, r: app.meowney.core.data.HabitResult): String {
        val head = if (r.allDone) "Xong hết thói quen hôm nay! Tháp xây xong, trẫm lên ngồi rồi nè." else "Xong $name! Trẫm vờn len đây."
        val money = if (r.moved > 0) " Chuyển ${app.meowney.core.format.formatVndShort(r.moved)} rồi." else ""
        val coins = if (r.coins > 0) " +${r.coins} xu" else ""
        return (listOf(head + money + coins) + r.notes).joinToString(" ")
    }

    fun habitSlip(name: String) = "Lỡ $name à? Không sao, Sen thật thà là trẫm thương. Mai làm lại nha."

    /** The morning words about the days that were just closed. */
    fun habitSummary(s: app.meowney.core.data.CloseSummary): String = buildList {
        if (s.kept.isNotEmpty()) add("Hôm qua Sen giữ được ${s.kept.joinToString(", ")}.")
        if (s.rested.isNotEmpty()) add("Trẫm cho Sen nghỉ ${s.rested.joinToString(", ")} (dùng vé).")
        if (s.missed.isNotEmpty()) add("Lỡ ${s.missed.joinToString(", ")} rồi, hôm nay làm lại nha.")
        addAll(s.notes)
        if (s.coins > 0) add("+${s.coins} xu")
    }.joinToString(" ")

    fun birthday(title: String) = "Hôm nay là $title! Trẫm đội mũ rồi, Sen nhớ chúc mừng nha."

    /** Reaction to the diary mood (1 rất tệ .. 5 rất vui); [low] = three bad days in a row. */
    fun mood(level: Int, low: Boolean = false): String = when {
        low -> "Mấy hôm nay Sen buồn nhiều. Nghỉ ngơi chút, hay gọi cho ai đó Sen thương nhé. Trẫm vẫn ở đây."
        level >= 5 -> "Sen vui là trẫm vui! Kể thêm đi, trẫm nghe nè."
        level == 4 -> "Ngày tốt đấy chứ. Trẫm ghi công cho Sen."
        level == 3 -> "Bình thường cũng là một ngày ổn. Trẫm ngồi đây nghe Sen kể."
        level == 2 -> "Thôi nào, trẫm nằm cạnh Sen đây. Viết ra cho nhẹ lòng."
        else -> "Hôm nay khó khăn hả Sen? Trẫm không đi đâu hết, cứ kể trẫm nghe."
    }

    fun diaryCoins(coins: Int, streak: Int, bonus: Int): String =
        "Viết nhật ký rồi! +$coins xu" + when {
            bonus > 0 -> ". Chuỗi $streak ngày, thưởng thêm $bonus xu!"
            streak > 1 -> " · $streak ngày liền"
            else -> ""
        }

    // ------------------------------------------------------------------ game (GĐ6)

    fun bought(name: String) = listOf(
        "$name về nhà rồi! Sen khéo chọn đấy.",
        "Trẫm duyệt món $name. Đặt ở đâu đây Sen?",
        "Có $name rồi, góc của trẫm sang hẳn lên.",
    ).random()

    fun notEnough(missing: Long) = "Còn thiếu $missing xu. Sen ghi sổ, làm thói quen thêm chút nữa nhé."

    fun treat(name: String, affection: Int) = "Ngon quá! $name đúng ý trẫm. Thân thiết lên $affection tim rồi đó."

    fun ate(name: String) = listOf("Măm măm... $name ngon tuyệt!", "Sen chiều trẫm quá. $name hết sạch rồi.", "Trẫm no rồi, cảm ơn Sen nha.").random()

    fun adopted(name: String) = "Chào mừng $name về nhà! Vào Tủ đồ để cho bé ra phòng nhé."

    fun trophy(name: String, tier: Int) = "Sen mở được cúp \"$name\" bậc ${listOf("Đồng", "Bạc", "Vàng")[tier - 1]}!"

    fun lixi(coins: Int) = "Lì xì đầu năm! Trẫm mừng tuổi Sen $coins xu. An khang thịnh vượng nha."

    fun redeemed(name: String) = "Đổi \"$name\" rồi! Lúc nào thưởng thì bấm Dùng, trẫm ghi sổ giúp."
}
