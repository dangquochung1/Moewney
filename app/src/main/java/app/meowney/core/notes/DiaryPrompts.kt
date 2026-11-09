package app.meowney.core.notes

import kotlin.random.Random

/** What the cat knows about the day when it picks a question for the diary page. */
data class PromptContext(
    val overspent: Boolean = false,
    val spent: Long = 0,
    val allHabitsDone: Boolean = false,
    val birthday: String? = null,
    val yesterdayMood: Int? = null,
    val weekend: Boolean = false,
    val firstEvent: String? = null,
    val todosDone: Int = 0,
)

/**
 * The cat's question at the top of a diary page. Questions that fit the day come first, then
 * the general ones in an order fixed by the day; "Câu khác" ([skip]) walks the list.
 */
object DiaryPrompts {
    val general = listOf(
        "Hôm nay có gì làm Sen cười không?",
        "Kể trẫm nghe điều nhỏ nhất làm Sen vui hôm nay.",
        "Hôm nay Sen biết ơn điều gì?",
        "Có ai làm Sen thấy được quan tâm hôm nay không?",
        "Sen ăn gì ngon hôm nay? Trẫm hỏi vì trẫm đói.",
        "Hôm nay Sen học được điều gì mới?",
        "Điều gì làm Sen mệt nhất hôm nay?",
        "Nếu được làm lại hôm nay, Sen đổi điều gì?",
        "Sen đã tự hào về mình chỗ nào hôm nay?",
        "Có chuyện gì Sen còn nghĩ mãi không?",
        "Hôm nay Sen nghe bài hát nào hay?",
        "Kể một khoảnh khắc yên bình hôm nay đi.",
        "Ngày mai Sen muốn làm một việc gì cho vui?",
        "Hôm nay Sen gặp ai? Họ thế nào?",
        "Sen lo lắng điều gì không? Viết ra cho nhẹ lòng.",
        "Hôm nay thời tiết thế nào, Sen thấy sao?",
        "Có câu nào ai nói làm Sen nhớ không?",
        "Sen đã nghỉ ngơi đủ chưa? Thật lòng nhé.",
        "Một điều Sen muốn nói với chính mình hôm nay?",
        "Hôm nay có gì khác hôm qua không?",
        "Sen đã làm điều tốt nào cho ai chưa?",
        "Điều gì Sen đang mong chờ?",
        "Viết ba chữ tả ngày hôm nay xem nào.",
        "Hôm nay Sen có cười to lần nào không?",
        "Có gì Sen muốn trẫm giữ bí mật không? Trẫm kín miệng lắm.",
        "Sen đọc hay xem được gì thú vị hôm nay?",
        "Có lúc nào hôm nay Sen thấy mình giỏi không?",
        "Hôm nay Sen đã chăm sóc bản thân thế nào?",
        "Nếu hôm nay là một món ăn thì nó là món gì?",
        "Sen muốn nhớ điều gì của hôm nay sau một năm nữa?",
    )

    fun contextual(ctx: PromptContext): List<String> = buildList {
        ctx.birthday?.let { add("Hôm nay là $it! Sen đã chúc mừng chưa, có gì vui kể trẫm nghe?") }
        if (ctx.yesterdayMood != null && ctx.yesterdayMood <= 2) {
            add("Hôm qua Sen hơi buồn. Hôm nay đỡ hơn chưa?")
        }
        if (ctx.overspent) add("Hôm nay Sen tiêu hơi mạnh tay đấy. Mua gì vui không?")
        else if (ctx.spent == 0L) add("Hôm nay Sen chưa tiêu đồng nào. Ngày tiết kiệm có vui không?")
        if (ctx.allHabitsDone) add("Xong hết thói quen rồi! Sen thấy trong người thế nào?")
        ctx.firstEvent?.let { add("Hôm nay có \"$it\". Mọi chuyện ra sao rồi?") }
        if (ctx.todosDone >= 3) add("Sen làm xong ${ctx.todosDone} việc hôm nay. Việc nào khó nhất?")
        if (ctx.weekend) add("Cuối tuần Sen làm gì cho mình vui?")
    }

    fun list(ctx: PromptContext, day: Long): List<String> =
        contextual(ctx) + general.shuffled(Random(day))

    fun pick(ctx: PromptContext, day: Long, skip: Int = 0): String {
        val all = list(ctx, day)
        return all[Math.floorMod(skip, all.size)]
    }
}
