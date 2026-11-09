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
}
