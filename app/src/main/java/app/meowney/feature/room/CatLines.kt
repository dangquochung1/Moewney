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

    private val hotspot = mapOf(
        "jars" to listOf("Kệ 6 lọ của trẫm. Sắp tới Sen rót tiền vào đây!"),
        "calendar" to listOf("Lịch hôm nay đó. Sổ lịch của Sen mở ở giai đoạn 4."),
        "plants" to listOf("Chậu cây thói quen. Sen chăm chỉ thì cây mới lớn."),
        "desk" to listOf("Bàn viết sổ tay của Sen. Nhật ký để sau nha."),
        "bed" to listOf("Giường của trẫm. Sen cấm nằm."),
        "gift" to listOf("Rương quà! Mỗi ngày một vòng quay, đợi trẫm chút."),
        "window" to listOf("Trời đẹp quá. Sen đi làm kiếm hạt cho trẫm đi."),
        "add" to listOf("Nút ghi chi tiêu nhanh. Giai đoạn 1 trẫm mới cho Sen bấm."),
    )

    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> "Chào buổi sáng, Sen! Hôm nay liệu mà tiêu."
        in 11..13 -> "Trưa rồi, Sen ăn gì nhớ ghi sổ nha."
        in 14..17 -> "Chiều rồi. Sen còn bao nhiêu tiền thế?"
        in 18..21 -> "Tối rồi, Sen ghi lại chi tiêu hôm nay đi."
        else -> "Khuya rồi, Sen ngủ đi. Trẫm cũng ngủ đây... zZ"
    }

    fun onTap(random: Random = Random.Default) = tap.random(random)

    fun onHotspot(id: String, random: Random = Random.Default) =
        hotspot[id]?.random(random) ?: "Meo?"
}
