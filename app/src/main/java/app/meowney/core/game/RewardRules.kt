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
