package app.meowney.core.game

/** A coin reward to write to the coin ledger. */
data class Reward(val delta: Int, val reason: String)

/**
 * All coin numbers in one place. Coins reward honest, regular logging - an overspend that
 * gets logged earns the same as any other entry.
 */
object RewardRules {
    const val LOG = 2
    const val LOG_DAILY_CAP = 20
    const val FIRST_LOG_OF_DAY = 5
    const val NO_SPEND_DAY = 5
    const val INCOME_POURED = 10
    const val ONBOARDING = 20

    const val R_LOG = "log"
    const val R_FIRST = "first_log"
    const val R_NO_SPEND = "no_spend"
    const val R_INCOME = "income"
    const val R_WELCOME = "welcome"

    // habits (GĐ3): coins by the difficulty Sen picked
    const val ALL_HABITS_DONE = 10
    const val FOCUS_SESSION = 5
    const val R_HABIT = "habit"
    const val R_HABITS_ALL = "habits_all"
    const val R_HABIT_STREAK = "habit_streak"
    const val R_FOCUS = "focus"

    /** Dễ / Vừa / Khó. */
    fun habitCoins(difficulty: Int) = when (difficulty) {
        1 -> 2
        3 -> 6
        else -> 4
    }

    /** Coins for reaching a streak of 7 / 30 / 100, else null. */
    fun streakMilestone(streak: Int): Int? = when (streak) {
        7 -> 20
        30 -> 50
        100 -> 150
        else -> null
    }

    /** Coins for logging one transaction, given what logging already earned today. */
    fun onLog(logCoinsToday: Int, firstLogToday: Boolean): List<Reward> = buildList {
        if (firstLogToday) add(Reward(FIRST_LOG_OF_DAY, R_FIRST))
        val room = (LOG_DAILY_CAP - logCoinsToday).coerceAtLeast(0)
        if (room > 0) add(Reward(minOf(LOG, room), R_LOG))
    }

    /** "Hôm nay Sen không tiêu gì": once a day, only if nothing was spent. */
    fun onNoSpend(alreadyClaimedToday: Boolean, spentToday: Long): Reward? =
        if (!alreadyClaimedToday && spentToday == 0L) Reward(NO_SPEND_DAY, R_NO_SPEND) else null

    fun onIncomePoured() = Reward(INCOME_POURED, R_INCOME)

    fun onWelcome() = Reward(ONBOARDING, R_WELCOME)
}
