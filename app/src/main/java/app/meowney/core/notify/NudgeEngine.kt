package app.meowney.core.notify

/** The mood a message from the cat arrives in; each has its own title and face. */
enum class CatTone { REMIND, ANGRY, WORRIED, SAD, HAPPY, MORNING, HABIT }

/** One message from the cat, before it becomes a notification. */
data class Nudge(val tone: CatTone, val text: String)

/** What the evening check knows about today. Names are what the user typed. */
data class EveningInputs(
    /** To-dos due today that are still open. */
    val undone: List<String> = emptyList(),
    /** To-dos from earlier days that are still open. */
    val overdue: List<String> = emptyList(),
    val todosDone: Int = 0,
    /** Habits due today that are still open. */
    val habitsOpen: List<String> = emptyList(),
    val habitsDone: Int = 0,
    /** Something was written in the money book today (or "không tiêu" was claimed). */
    val logged: Boolean = false,
    /** The cat already got angry today. */
    val angryToday: Boolean = false,
)

/** What the morning check knows about today. */
data class MorningInputs(
    /** Events and to-dos of the day, ready to read ("Nộp bài (14:00)"). */
    val agenda: List<String> = emptyList(),
    /** Bills and debts due today or overdue ("Tiền điện 350k"). */
    val due: List<String> = emptyList(),
    /** Jars below zero ("Hưởng thụ"). */
    val negativeJars: List<String> = emptyList(),
    /** Days since the app was last opened. */
    val daysAway: Int = 0,
)

/**
 * Decides what the cat says and in which mood. No Android here, so the rules can be tested:
 * angry at most once a day, sad only after two days away.
 */
object NudgeEngine {
    const val ANGRY_AT = 3
    const val SAD_AFTER_DAYS = 2

    fun title(tone: CatTone, cat: String): String = when (tone) {
        CatTone.REMIND -> "$cat nhắc nhở"
        CatTone.ANGRY -> "$cat đang tức giận"
        CatTone.WORRIED -> "$cat đang lo"
        CatTone.SAD -> "$cat đang buồn"
        CatTone.HAPPY -> "$cat đang vui"
        CatTone.MORNING -> "$cat chào buổi sáng"
        CatTone.HABIT -> "$cat nhắc thói quen"
    }

    /** "A, B, C" or "A, B, C và 2 việc nữa". */
    fun names(items: List<String>, noun: String, max: Int = 3): String {
        val head = items.take(max).joinToString(", ") { "\"$it\"" }
        val rest = items.size - max
        return if (rest > 0) "$head và $rest $noun nữa" else head
    }

    /** The evening message, or null when there is nothing to say. [nag] is the "ghi sổ đi" line. */
    fun evening(i: EveningInputs, nag: String): Nudge? {
        val open = i.overdue + i.undone
        val habits = if (i.habitsOpen.isEmpty()) null else "Còn ${i.habitsOpen.size} thói quen chưa xong: ${names(i.habitsOpen, "thói quen")}."
        return when {
            (i.overdue.isNotEmpty() || open.size >= ANGRY_AT) && !i.angryToday -> {
                val late = if (i.overdue.isEmpty()) null else "${if (i.overdue.size == 1) "\"${i.overdue.first()}\"" else "${i.overdue.size} việc"} đã quá hạn."
                Nudge(CatTone.ANGRY, listOfNotNull("Còn ${open.size} việc chưa làm: ${names(open, "việc")}.", late, habits).joinToString(" "))
            }
            open.isNotEmpty() || habits != null || !i.logged -> {
                val todos = if (open.isEmpty()) null else "Còn ${open.size} việc chưa làm: ${names(open, "việc")}."
                Nudge(CatTone.REMIND, listOfNotNull(todos, habits, nag.takeIf { !i.logged }).joinToString(" "))
            }
            i.todosDone + i.habitsDone > 0 -> {
                val parts = listOfNotNull(
                    "${i.todosDone} việc".takeIf { i.todosDone > 0 },
                    "${i.habitsDone} thói quen".takeIf { i.habitsDone > 0 },
                )
                Nudge(CatTone.HAPPY, "Hôm nay Sen xong hết ${parts.joinToString(" và ")}. Giỏi lắm!")
            }
            else -> null
        }
    }

    /** The morning messages: the day's agenda, money worries, and missing the user. */
    fun morning(i: MorningInputs): List<Nudge> = buildList {
        if (i.agenda.isNotEmpty()) {
            add(Nudge(CatTone.MORNING, "Hôm nay có ${i.agenda.size} việc: ${i.agenda.take(4).joinToString(", ")}${if (i.agenda.size > 4) "…" else ""}."))
        }
        val worries = listOfNotNull(
            if (i.due.isEmpty()) null else "${i.due.joinToString(", ")} đến hạn hôm nay.",
            if (i.negativeJars.isEmpty()) null else "Lọ ${i.negativeJars.joinToString(", ")} đang âm.",
        )
        if (worries.isNotEmpty()) add(Nudge(CatTone.WORRIED, worries.joinToString(" ")))
        if (i.daysAway >= SAD_AFTER_DAYS) {
            add(Nudge(CatTone.SAD, "${if (i.daysAway == 2) "Hai" else "${i.daysAway}"} hôm rồi Sen chưa ghé trẫm. Vào ghi vài dòng nha."))
        }
    }
}
