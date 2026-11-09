package app.meowney.core.game

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

// ------------------------------------------------------------------ lucky wheel

/** The 8 slots of the wheel; [weight] is how often it stops there. */
enum class Prize(val label: String, val weight: Int) {
    COINS5("5 xu", 20),
    TREAT("Món ăn", 14),
    COINS10("10 xu", 18),
    BOOST("x2 xu ghi sổ", 12),
    COINS20("20 xu", 12),
    TICKET("Vé nghỉ", 10),
    COINS50("50 xu", 4),
    RARE("Đồ hiếm", 4),
}

/** What a spin actually gives once the prize is applied. */
data class SpinOutcome(
    val prize: Prize,
    val coins: Int = 0,
    val itemId: String? = null,
    val ticketHabit: String? = null,
    val boost: Boolean = false,
)

object WheelEngine {
    /** Clockwise from the top, the order the slots sit on the wheel art. */
    val segments: List<Prize> = Prize.entries

    /** One free spin a day, one more once every habit of the day is done. */
    fun spinsToday(allHabitsDone: Boolean) = if (allHabitsDone) 2 else 1

    fun pick(random: Random): Int {
        val total = segments.sumOf { it.weight }
        var r = random.nextInt(total)
        segments.forEachIndexed { i, p ->
            if (r < p.weight) return i
            r -= p.weight
        }
        return 0
    }

    /**
     * Turns a prize into what Sen gets. Prizes that cannot be used fall back to coins: a rest
     * ticket with no habit short of tickets, a rare item when every one is owned, a second
     * boost on the same day.
     */
    fun resolve(
        prize: Prize,
        random: Random,
        ticketHabit: String?,
        rares: List<String>,
        boostedToday: Boolean,
    ): SpinOutcome = when (prize) {
        Prize.COINS5 -> SpinOutcome(prize, coins = 5)
        Prize.COINS10 -> SpinOutcome(prize, coins = 10)
        Prize.COINS20 -> SpinOutcome(prize, coins = 20)
        Prize.COINS50 -> SpinOutcome(prize, coins = 50)
        Prize.TREAT -> SpinOutcome(prize, itemId = Catalog.treats.random(random).id)
        Prize.TICKET -> if (ticketHabit != null) SpinOutcome(prize, ticketHabit = ticketHabit) else SpinOutcome(prize, coins = 15)
        Prize.RARE -> if (rares.isNotEmpty()) SpinOutcome(prize, itemId = rares.random(random)) else SpinOutcome(prize, coins = 50)
        Prize.BOOST -> if (!boostedToday) SpinOutcome(prize, boost = true) else SpinOutcome(prize, coins = 10)
    }
}

// ------------------------------------------------------------------ achievements

enum class AchGroup(val label: String) { MONEY("Tiền"), HABIT("Thói quen"), CALENDAR("Lịch"), NOTES("Sổ tay"), CAT("Mèo") }

/** Numbers the achievements look at; the repository fills them from the database. */
object Metric {
    const val LOG_DAYS = "log_days"
    const val LOG_COUNT = "log_count"
    const val NO_SPEND = "no_spend"
    const val GOALS = "goals"
    const val INCOMES = "incomes"
    const val REPORTS = "reports"
    const val HABIT_STREAK = "habit_streak"
    const val HABIT_DONE = "habit_done"
    const val HABITS_ALL = "habits_all"
    const val FOCUS_MIN = "focus_min"
    const val TODOS = "todos"
    const val EVENTS = "events"
    const val DIARY_DAYS = "diary_days"
    const val DIARY_STREAK = "diary_streak"
    const val NOTES = "notes"
    const val PHOTOS = "photos"
    const val FEEDS = "feeds"
    const val CATS = "cats"
    const val ITEMS = "items"
    const val SPINS = "spins"
    const val CHALLENGES = "challenges"
    const val COINS = "coins"
    const val VOUCHERS = "vouchers"
}

/** One achievement with three tiers (đồng, bạc, vàng); [text] shows the threshold with "%d". */
data class Achievement(val id: String, val group: AchGroup, val name: String, val text: String, val metric: String, val tiers: List<Int>)

object AchievementEngine {
    val TIER_NAMES = listOf("Đồng", "Bạc", "Vàng")
    val TIER_COINS = listOf(20, 50, 150)

    val all = listOf(
        Achievement("log_days", AchGroup.MONEY, "Sổ sách đều tay", "Ghi sổ %d ngày", Metric.LOG_DAYS, listOf(7, 30, 100)),
        Achievement("log_count", AchGroup.MONEY, "Chăm ghi chép", "Ghi %d khoản", Metric.LOG_COUNT, listOf(50, 300, 1000)),
        Achievement("no_spend", AchGroup.MONEY, "Ngày nhịn tiêu", "%d ngày không tiêu đồng nào", Metric.NO_SPEND, listOf(3, 15, 50)),
        Achievement("goals", AchGroup.MONEY, "Về đích", "Đạt %d mục tiêu tiết kiệm", Metric.GOALS, listOf(1, 3, 10)),
        Achievement("incomes", AchGroup.MONEY, "Rót đầy lọ", "Chia %d khoản thu vào lọ", Metric.INCOMES, listOf(1, 6, 24)),
        Achievement("reports", AchGroup.MONEY, "Tổng kết tháng", "Nhận %d phiếu điểm tháng", Metric.REPORTS, listOf(1, 6, 12)),
        Achievement("habit_streak", AchGroup.HABIT, "Bền bỉ", "Chuỗi thói quen %d lần", Metric.HABIT_STREAK, listOf(7, 30, 100)),
        Achievement("habit_done", AchGroup.HABIT, "Từng chút một", "Hoàn thành thói quen %d lần", Metric.HABIT_DONE, listOf(30, 200, 1000)),
        Achievement("habits_all", AchGroup.HABIT, "Ngày trọn vẹn", "%d ngày xong hết thói quen", Metric.HABITS_ALL, listOf(5, 30, 100)),
        Achievement("focus", AchGroup.HABIT, "Tập trung", "Tập trung %d phút", Metric.FOCUS_MIN, listOf(300, 1500, 6000)),
        Achievement("todos", AchGroup.CALENDAR, "Gọn việc", "Xong %d việc", Metric.TODOS, listOf(10, 50, 200)),
        Achievement("events", AchGroup.CALENDAR, "Lịch kín", "Tạo %d sự kiện", Metric.EVENTS, listOf(5, 30, 100)),
        Achievement("diary_days", AchGroup.NOTES, "Nhật ký", "Viết nhật ký %d ngày", Metric.DIARY_DAYS, listOf(7, 30, 100)),
        Achievement("diary_streak", AchGroup.NOTES, "Viết liền mạch", "Viết %d ngày liền", Metric.DIARY_STREAK, listOf(7, 30, 100)),
        Achievement("notes", AchGroup.NOTES, "Sổ dày lên", "Có %d ghi chú", Metric.NOTES, listOf(5, 20, 50)),
        Achievement("photos", AchGroup.NOTES, "Nhiếp ảnh gia", "Dán %d ảnh vào sổ", Metric.PHOTOS, listOf(1, 10, 50)),
        Achievement("feeds", AchGroup.CAT, "Sen chiều trẫm", "Cho mèo ăn %d lần", Metric.FEEDS, listOf(5, 30, 100)),
        Achievement("cats", AchGroup.CAT, "Đàn mèo", "Nuôi %d con mèo", Metric.CATS, listOf(2, 4, 6)),
        Achievement("items", AchGroup.CAT, "Góc mèo xinh", "Sắm %d món đồ", Metric.ITEMS, listOf(3, 10, 20)),
        Achievement("spins", AchGroup.CAT, "Tay quay", "Quay vòng quay %d lần", Metric.SPINS, listOf(7, 30, 100)),
        Achievement("challenges", AchGroup.CAT, "Chinh phục", "Xong %d thử thách tháng", Metric.CHALLENGES, listOf(1, 3, 6)),
        Achievement("coins", AchGroup.CAT, "Hũ xu đầy", "Kiếm tổng cộng %d xu", Metric.COINS, listOf(500, 3000, 10000)),
        Achievement("vouchers", AchGroup.CAT, "Tự thưởng", "Dùng %d phần thưởng thật", Metric.VOUCHERS, listOf(1, 5, 20)),
    )

    /** Highest tier (0..3) reached by [value]. */
    fun tierOf(a: Achievement, value: Long): Int = a.tiers.count { value >= it }

    /** Tiers reached now that are not in [have] (pairs of id and tier 1..3). */
    fun newTiers(metrics: Map<String, Long>, have: Set<Pair<String, Int>>): List<Pair<Achievement, Int>> =
        all.flatMap { a ->
            val reached = tierOf(a, metrics[a.metric] ?: 0)
            (1..reached).filter { (a.id to it) !in have }.map { a to it }
        }
}

// ------------------------------------------------------------------ monthly challenge

/** One challenge; [ref] points at a habit for HABIT_DAYS. */
@Serializable
data class ChallengeSpec(val kind: String, val target: Int, val label: String, val ref: Long = 0)

/** What the suggestions are based on: what Sen actually uses. */
data class ChallengeInputs(
    val daysInMonth: Int,
    val habits: List<Pair<Long, String>> = emptyList(),
    val usesDiary: Boolean = false,
    val usesTodos: Boolean = false,
    val usesFocus: Boolean = false,
)

object ChallengeEngine {
    const val LOG_DAYS = "LOG_DAYS"
    const val WITHIN_DAYS = "WITHIN_DAYS"
    const val NO_SPEND_DAYS = "NO_SPEND_DAYS"
    const val NO_LOANS = "NO_LOANS"
    const val SAVE_PLAN = "SAVE_PLAN"
    const val HABIT_DAYS = "HABIT_DAYS"
    const val DIARY_DAYS = "DIARY_DAYS"
    const val TODOS = "TODOS"
    const val FOCUS_MIN = "FOCUS_MIN"

    const val CHEST_COINS = 200

    /** Three different challenges that fit what Sen uses, in an order fixed by the month. */
    fun suggest(inputs: ChallengeInputs, month: YearMonth): List<ChallengeSpec> {
        val n = inputs.daysInMonth
        val money = listOf(
            ChallengeSpec(LOG_DAYS, n - 5, "Ghi sổ ${n - 5} ngày"),
            ChallengeSpec(WITHIN_DAYS, 20, "Giữ trong hạn mức 20 ngày"),
            ChallengeSpec(NO_SPEND_DAYS, 3, "3 ngày không tiêu đồng nào"),
            ChallengeSpec(NO_LOANS, 1, "Cả tháng không mượn lọ"),
            ChallengeSpec(SAVE_PLAN, 100, "Để dành đủ như dự tính"),
        )
        val other = buildList {
            inputs.habits.forEach { (id, name) -> add(ChallengeSpec(HABIT_DAYS, n - 10, "Làm \"$name\" ${n - 10} ngày", ref = id)) }
            if (inputs.usesDiary) add(ChallengeSpec(DIARY_DAYS, 15, "Viết nhật ký 15 ngày"))
            if (inputs.usesTodos) add(ChallengeSpec(TODOS, 15, "Xong 15 việc"))
            if (inputs.usesFocus) add(ChallengeSpec(FOCUS_MIN, 600, "Tập trung 10 giờ"))
        }
        val rnd = Random(month.year * 100 + month.monthValue)
        val pickedMoney = money.shuffled(rnd)
        val pickedOther = other.shuffled(rnd)
        // one or two about money, the rest from what else Sen does
        val out = mutableListOf<ChallengeSpec>()
        out += pickedMoney.first()
        out += pickedOther.take(2)
        out += pickedMoney.drop(1).take(3 - out.size)
        return out.take(3)
    }

    /** Pass/fail challenges are only settled when the month is over. */
    fun settledAtMonthEnd(kind: String) = kind == NO_LOANS

    fun done(spec: ChallengeSpec, value: Int, monthOver: Boolean): Boolean =
        if (settledAtMonthEnd(spec.kind)) monthOver && value >= spec.target else value >= spec.target

    fun progress(spec: ChallengeSpec, value: Int): Float = (value.toFloat() / spec.target).coerceIn(0f, 1f)
}

// ------------------------------------------------------------------ seasons

/** A small quest of the season; done when [kind] reaches [target] inside the season. */
data class SeasonQuest(val id: String, val season: Season, val label: String, val kind: String, val target: Int, val rewardItem: String)

object SeasonEngine {
    /** Mùng 1 Tết, from the lunar calendar. */
    val TET: Map<Int, LocalDate> = mapOf(
        2026 to LocalDate.of(2026, 2, 17), 2027 to LocalDate.of(2027, 2, 6), 2028 to LocalDate.of(2028, 1, 26),
        2029 to LocalDate.of(2029, 2, 13), 2030 to LocalDate.of(2030, 2, 3), 2031 to LocalDate.of(2031, 1, 23),
        2032 to LocalDate.of(2032, 2, 11), 2033 to LocalDate.of(2033, 1, 31), 2034 to LocalDate.of(2034, 2, 19),
        2035 to LocalDate.of(2035, 2, 8),
    )

    /** The days of a season around [d] (null when [d] is not in that season). */
    fun window(season: Season, d: LocalDate): ClosedRange<LocalDate>? {
        val y = d.year
        val r: List<ClosedRange<LocalDate>> = when (season) {
            Season.TET -> listOfNotNull(TET[y], TET[y + 1]).map { it.minusDays(7)..it.plusDays(6) }
            Season.HALLOWEEN -> listOf(LocalDate.of(y, 10, 24)..LocalDate.of(y, 11, 1))
            Season.NOEL -> listOf(LocalDate.of(y, 12, 15)..LocalDate.of(y + 1, 1, 1), LocalDate.of(y - 1, 12, 15)..LocalDate.of(y, 1, 1))
            Season.AUTUMN -> listOf(LocalDate.of(y, 9, 15)..LocalDate.of(y, 10, 23))
        }
        return r.firstOrNull { d in it }
    }

    fun seasonOn(d: LocalDate): Season? = Season.entries.firstOrNull { window(it, d) != null }

    /** Lì xì: one red envelope a day from mùng 1 to mùng 7. */
    fun lixiDay(d: LocalDate): Boolean = TET[d.year]?.let { !d.isBefore(it) && !d.isAfter(it.plusDays(6)) } == true

    val LIXI = listOf(20, 36, 50, 68, 88)

    fun lixi(random: Random): Int = LIXI[random.nextInt(LIXI.size)]

    /** In Noel the cat wears a Santa hat for free (unless Sen chose another hat). */
    fun freeHat(season: Season?): String? = if (season == Season.NOEL) "santa" else null

    fun music(season: Season?, day: LocalDate): String = when (season) {
        Season.NOEL -> "noel.mp3"
        Season.HALLOWEEN -> "halloween.ogg"
        Season.TET -> "tet.ogg"
        Season.AUTUMN -> "autumn.ogg"
        null -> if (day.dayOfYear % 2 == 0) "chill.ogg" else "village.ogg"
    }

    val quests = listOf(
        SeasonQuest("q_noel", Season.NOEL, "Ghi sổ 10 ngày mùa Noel", "LOG_DAYS", 10, "hat_antlers"),
        SeasonQuest("q_halloween", Season.HALLOWEEN, "Xong hết thói quen 3 ngày", "HABITS_ALL", 3, "decor_pumpkin"),
        SeasonQuest("q_tet", Season.TET, "Viết nhật ký 3 ngày Tết", "DIARY_DAYS", 3, "decor_lantern"),
        SeasonQuest("q_autumn", Season.AUTUMN, "Ghi sổ 15 ngày mùa thu", "LOG_DAYS", 15, "decor_sunflower"),
    )

    fun questFor(season: Season?) = quests.firstOrNull { it.season == season }

    /** A key unique to this year's season, so quests come back next year. */
    fun questKey(q: SeasonQuest, d: LocalDate): String = "${q.id}_${window(q.season, d)?.start?.year ?: d.year}"
}
