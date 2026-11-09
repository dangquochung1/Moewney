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
        Toy.Habits to "Xong thói quen là trẫm được vờn len! Thử trước nè.",
        Toy.Calendar to "Lịch của Sen mở ở giai đoạn 4.",
        Toy.Notes to "Nhà của trẫm... à không, sổ tay của Sen. Giai đoạn 5 nhé.",
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
}
