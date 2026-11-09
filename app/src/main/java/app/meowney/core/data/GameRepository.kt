package app.meowney.core.data

import androidx.room.withTransaction
import app.meowney.core.db.AchievementEntity
import app.meowney.core.db.ChallengeEntity
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.EquipEntity
import app.meowney.core.db.GameLogEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.OwnedItemEntity
import app.meowney.core.db.PetEntity
import app.meowney.core.db.RealRewardEntity
import app.meowney.core.db.VoucherEntity
import app.meowney.core.game.Achievement
import app.meowney.core.game.AchievementEngine
import app.meowney.core.game.Catalog
import app.meowney.core.game.ChallengeEngine
import app.meowney.core.game.ChallengeInputs
import app.meowney.core.game.ChallengeSpec
import app.meowney.core.game.Metric
import app.meowney.core.game.Season
import app.meowney.core.game.SeasonEngine
import app.meowney.core.game.SeasonQuest
import app.meowney.core.game.Slot
import app.meowney.core.game.SpinOutcome
import app.meowney.core.game.WheelEngine
import app.meowney.core.game.RewardRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

/** The cat on show lives in the settings (name and colour are used all over the app). */
interface CatProfile {
    suspend fun current(): Pair<Int, String>
    suspend fun set(color: Int, name: String)
}

/** Everything the shop and the wardrobe need in one value. */
data class GameState(
    val coins: Long = 0,
    val owned: Map<String, Int> = emptyMap(),
    val equip: Map<Slot, String> = emptyMap(),
    val pets: List<PetEntity> = emptyList(),
) {
    /** What is in [slot]: the stored choice or the free default. */
    fun equipped(slot: Slot): String? = equip[slot] ?: Catalog.defaultFor(slot)?.id
    fun owns(id: String) = Catalog[id]?.default == true || (owned[id] ?: 0) > 0
    fun qty(id: String) = owned[id] ?: 0
}

sealed interface BuyResult {
    data class Ok(val itemId: String?) : BuyResult
    data class NotEnough(val missing: Long) : BuyResult
    data object AlreadyOwned : BuyResult
    data object NotForSale : BuyResult
}

data class FeedResult(val itemName: String, val hearts: Int, val affection: Int)

/** The month's challenge with where it stands. */
data class ChallengeView(val month: YearMonth, val options: List<ChallengeSpec>, val picked: ChallengeSpec?, val value: Int, val done: Boolean, val claimed: Boolean) {
    val progress get() = picked?.let { ChallengeEngine.progress(it, value) } ?: 0f
}

data class QuestView(val quest: SeasonQuest, val value: Int, val claimed: Boolean) {
    val done get() = value >= quest.target
}

class GameRepository(
    private val db: MeowneyDb,
    private val profile: CatProfile,
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.game()
    private val money = db.money()
    private val habits = db.habits()
    private val reports = Reports(db)
    private val json = Json { ignoreUnknownKeys = true }
    private val specs = ListSerializer(ChallengeSpec.serializer())

    val state: Flow<GameState> = combine(money.coinTotal(), dao.owned(), dao.equip(), dao.pets()) { coins, owned, equip, pets ->
        GameState(
            coins = coins,
            owned = owned.associate { it.itemId to it.qty },
            equip = equip.mapNotNull { e -> runCatching { Slot.valueOf(e.slot) }.getOrNull()?.let { it to e.itemId } }.toMap(),
            pets = pets,
        )
    }

    /** When the last treat was eaten (the cat stays extra happy for a while). */
    val lastFeedAt: Flow<Long?> = dao.lastAt(K_FEED)

    /** The first cat of the collection is the one Sen already has. */
    suspend fun ensureStarter(today: LocalDate = LocalDate.now()) {
        if (dao.petsNow().isNotEmpty()) return
        val (color, name) = profile.current()
        dao.insertPet(PetEntity(color = color, name = name, adoptedDay = today.toEpochDay()))
    }

    private suspend fun spend(coins: Int, reason: String, day: Long) {
        money.insertCoins(listOf(CoinEntryEntity(delta = -coins, reason = reason, day = day)))
    }

    private suspend fun earn(coins: Int, reason: String, day: Long) {
        if (coins > 0) money.insertCoins(listOf(CoinEntryEntity(delta = coins, reason = reason, day = day)))
    }

    /** Adds an item to the owned ones (treats stack, the rest are kept once). */
    private suspend fun grant(id: String, day: Long) {
        val item = Catalog[id] ?: return
        val have = dao.ownedItem(id)
        if (item.slot == Slot.FOOD) dao.putOwned(OwnedItemEntity(id, (have?.qty ?: 0) + 1, have?.acquiredDay ?: day))
        else if (have == null) dao.putOwned(OwnedItemEntity(id, 1, day))
    }

    // ------------------------------------------------------------------ shop

    suspend fun buy(id: String, today: LocalDate = LocalDate.now()): BuyResult = db.withTransaction {
        val item = Catalog[id] ?: return@withTransaction BuyResult.NotForSale
        if (item.default) return@withTransaction BuyResult.AlreadyOwned
        if (item.season != null && dao.ownedItem(id) == null && SeasonEngine.seasonOn(today) != item.season) return@withTransaction BuyResult.NotForSale
        if (item.slot != Slot.FOOD && dao.ownedItem(id) != null) return@withTransaction BuyResult.AlreadyOwned
        val balance = dao.balance()
        if (balance < item.price) return@withTransaction BuyResult.NotEnough(item.price - balance)
        spend(item.price, R_SHOP, today.toEpochDay())
        grant(id, today.toEpochDay())
        dao.log(GameLogEntity(day = today.toEpochDay(), kind = K_BUY, data = id, at = clock()))
        BuyResult.Ok(id)
    }

    suspend fun equip(slot: Slot, id: String): Boolean {
        val item = Catalog[id] ?: return false
        if (item.slot != slot) return false
        if (!item.default && dao.ownedItem(id) == null) return false
        if (item.default) dao.clearEquip(slot.name) else dao.putEquip(EquipEntity(slot.name, id))
        return true
    }

    /** Gives a treat to the cat on show. */
    suspend fun feed(id: String, today: LocalDate = LocalDate.now()): FeedResult? = db.withTransaction {
        val item = Catalog[id]?.takeIf { it.slot == Slot.FOOD } ?: return@withTransaction null
        val have = dao.ownedItem(id) ?: return@withTransaction null
        if (have.qty <= 1) dao.deleteOwned(id) else dao.putOwned(have.copy(qty = have.qty - 1))
        val (color, _) = profile.current()
        val pet = dao.petsNow().firstOrNull { it.color == color }
        val affection = (pet?.affection ?: 0) + item.hearts
        pet?.let { dao.updatePet(it.copy(affection = affection)) }
        dao.log(GameLogEntity(day = today.toEpochDay(), kind = K_FEED, data = id, at = clock()))
        FeedResult(item.name, item.hearts, affection)
    }

    // ------------------------------------------------------------------ cats

    suspend fun adoptPrice(): Int = Catalog.catPrice(dao.petsNow().size)

    suspend fun adopt(color: Int, name: String, today: LocalDate = LocalDate.now()): BuyResult = db.withTransaction {
        if (color !in 1..6) return@withTransaction BuyResult.NotForSale
        val pets = dao.petsNow()
        if (pets.any { it.color == color }) return@withTransaction BuyResult.AlreadyOwned
        val price = Catalog.catPrice(pets.size)
        val balance = dao.balance()
        if (balance < price) return@withTransaction BuyResult.NotEnough(price - balance)
        spend(price, R_ADOPT, today.toEpochDay())
        dao.insertPet(PetEntity(color = color, name = name.ifBlank { Catalog.catColors.first { it.first == color }.second }, adoptedDay = today.toEpochDay()))
        BuyResult.Ok(null)
    }

    suspend fun showCat(color: Int) {
        val pet = dao.petsNow().firstOrNull { it.color == color } ?: return
        profile.set(pet.color, pet.name)
    }

    suspend fun renameCat(color: Int, name: String) {
        val clean = name.trim().take(16)
        if (clean.isBlank()) return
        val pet = dao.petsNow().firstOrNull { it.color == color } ?: return
        dao.updatePet(pet.copy(name = clean))
        if (profile.current().first == color) profile.set(color, clean)
    }

    // ------------------------------------------------------------------ wheel

    fun spinsUsed(day: LocalDate): Flow<Int> = dao.countOnFlow(K_SPIN, day.toEpochDay())

    suspend fun boostedOn(day: LocalDate) = dao.countOn(K_BOOST, day.toEpochDay()) > 0

    /**
     * Spins once if a spin is left today; returns the slot the wheel stops on and what was won.
     * [allHabitsDone] unlocks the second spin of the day.
     */
    suspend fun spin(allHabitsDone: Boolean, today: LocalDate = LocalDate.now()): Pair<Int, SpinOutcome>? = db.withTransaction {
        val day = today.toEpochDay()
        if (dao.countOn(K_SPIN, day) >= WheelEngine.spinsToday(allHabitsDone)) return@withTransaction null
        val index = WheelEngine.pick(random)
        val ticketHabit = habits.habitsNow().filter { it.tickets < 3 }.minByOrNull { it.tickets }
        val owned = dao.ownedNow().map { it.itemId }.toSet()
        val rares = Catalog.wheelRares.map { it.id }.filter { it !in owned }
        val out = WheelEngine.resolve(WheelEngine.segments[index], random, ticketHabit?.name, rares, boostedOn(today))
        earn(out.coins, R_WHEEL, day)
        out.itemId?.let { grant(it, day) }
        if (out.ticketHabit != null && ticketHabit != null) habits.updateHabit(ticketHabit.copy(tickets = ticketHabit.tickets + 1))
        if (out.boost) dao.log(GameLogEntity(day = day, kind = K_BOOST, at = clock()))
        dao.log(GameLogEntity(day = day, kind = K_SPIN, data = out.prize.name, at = clock()))
        index to out
    }

    // ------------------------------------------------------------------ real rewards

    val rewards: Flow<List<RealRewardEntity>> = dao.rewards()
    val vouchers: Flow<List<VoucherEntity>> = dao.vouchers()

    suspend fun saveReward(r: RealRewardEntity): Long = if (r.id == 0L) dao.insertReward(r) else r.id.also { dao.updateReward(r) }

    suspend fun archiveReward(id: Long) {
        dao.reward(id)?.let { dao.updateReward(it.copy(archived = true)) }
    }

    /** Pays the coins and keeps a voucher to use later. */
    suspend fun redeem(rewardId: Long, today: LocalDate = LocalDate.now()): BuyResult = db.withTransaction {
        val r = dao.reward(rewardId) ?: return@withTransaction BuyResult.NotForSale
        val balance = dao.balance()
        if (balance < r.coins) return@withTransaction BuyResult.NotEnough(r.coins - balance)
        spend(r.coins, R_REWARD, today.toEpochDay())
        dao.insertVoucher(VoucherEntity(rewardId = r.id, name = r.name, coins = r.coins, amount = r.amount, categoryId = r.categoryId, boughtDay = today.toEpochDay()))
        BuyResult.Ok(null)
    }

    /** Marks a voucher as enjoyed; false if it was already used. */
    suspend fun useVoucher(id: Long, today: LocalDate = LocalDate.now()): Boolean {
        val v = dao.voucher(id) ?: return false
        if (v.usedDay != null) return false
        dao.updateVoucher(v.copy(usedDay = today.toEpochDay()))
        return true
    }

    // ------------------------------------------------------------------ achievements

    val achievements: Flow<List<AchievementEntity>> = dao.achievements()

    suspend fun metrics(): Map<String, Long> {
        val diaryDays = dao.coinDays("diary", Long.MIN_VALUE / 2, Long.MAX_VALUE / 2).toSortedSet()
        var best = 0
        var run = 0
        var prev: Long? = null
        for (d in diaryDays) {
            run = if (prev != null && d == prev + 1) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return mapOf(
            Metric.LOG_DAYS to dao.loggedDays().toLong(),
            Metric.LOG_COUNT to dao.loggedTxns().toLong(),
            Metric.NO_SPEND to dao.coinCount(RewardRules.R_NO_SPEND).toLong(),
            Metric.GOALS to dao.goalsReached().toLong(),
            Metric.INCOMES to dao.coinCount(RewardRules.R_INCOME).toLong(),
            Metric.REPORTS to dao.reportsKept().toLong(),
            Metric.HABIT_STREAK to dao.bestHabitStreak().toLong(),
            Metric.HABIT_DONE to dao.habitDone().toLong(),
            Metric.HABITS_ALL to dao.coinCount(RewardRules.R_HABITS_ALL).toLong(),
            Metric.FOCUS_MIN to dao.focusMinutes().toLong(),
            Metric.TODOS to dao.todosDone().toLong(),
            Metric.EVENTS to dao.events().toLong(),
            Metric.DIARY_DAYS to diaryDays.size.toLong(),
            Metric.DIARY_STREAK to best.toLong(),
            Metric.NOTES to dao.notes().toLong(),
            Metric.PHOTOS to dao.photos().toLong(),
            Metric.FEEDS to dao.countAll(K_FEED).toLong(),
            Metric.CATS to dao.petsNow().size.toLong(),
            Metric.ITEMS to dao.ownedNow().count { Catalog[it.itemId]?.slot != Slot.FOOD }.toLong(),
            Metric.SPINS to dao.countAll(K_SPIN).toLong(),
            Metric.CHALLENGES to dao.challengesDone().toLong(),
            Metric.COINS to dao.coinsEarned(),
            Metric.VOUCHERS to dao.vouchersUsed().toLong(),
        )
    }

    /** Records the tiers reached since last time and pays their coins (once each). */
    suspend fun checkAchievements(today: LocalDate = LocalDate.now()): List<Pair<Achievement, Int>> {
        val have = dao.achievementsNow().map { it.id to it.tier }.toSet()
        val fresh = AchievementEngine.newTiers(metrics(), have)
        val day = today.toEpochDay()
        val paid = mutableListOf<Pair<Achievement, Int>>()
        for ((a, tier) in fresh) {
            if (dao.insertAchievement(AchievementEntity(a.id, tier, day)) == -1L) continue
            earn(AchievementEngine.TIER_COINS[tier - 1], "$R_ACH${a.id}_$tier", day)
            paid += a to tier
        }
        return paid
    }

    // ------------------------------------------------------------------ monthly challenge

    fun challengeFlow(month: YearMonth): Flow<ChallengeEntity?> = dao.challengeFlow(code(month))

    /** This month's suggestions, made the first time they are asked for. */
    suspend fun ensureChallenge(month: YearMonth): ChallengeEntity {
        dao.challenge(code(month))?.let { return it }
        val inputs = ChallengeInputs(
            daysInMonth = month.lengthOfMonth(),
            habits = habits.habitsNow().map { it.id to it.name },
            usesDiary = dao.coinCount("diary") > 0,
            usesTodos = dao.todosDone() > 0,
            usesFocus = dao.focusMinutes() > 0,
        )
        val c = ChallengeEntity(code(month), json.encodeToString(specs, ChallengeEngine.suggest(inputs, month)))
        dao.insertChallenge(c)
        return dao.challenge(code(month)) ?: c
    }

    suspend fun pickChallenge(month: YearMonth, index: Int) {
        val c = ensureChallenge(month)
        if (c.picked == null && index in options(c).indices) dao.updateChallenge(c.copy(picked = index))
    }

    fun options(c: ChallengeEntity): List<ChallengeSpec> = runCatching { json.decodeFromString(specs, c.options) }.getOrDefault(emptyList())

    private suspend fun challengeValue(spec: ChallengeSpec, month: YearMonth, today: LocalDate): Int {
        val from = month.atDay(1).toEpochDay()
        val to = month.atEndOfMonth().toEpochDay()
        return when (spec.kind) {
            ChallengeEngine.LOG_DAYS -> dao.loggedDaysBetween(from, to).size
            ChallengeEngine.NO_SPEND_DAYS -> dao.coinDays(RewardRules.R_NO_SPEND, from, to).size
            ChallengeEngine.DIARY_DAYS -> dao.coinDays("diary", from, to).size
            ChallengeEngine.TODOS -> dao.todosDoneBetween(from, to)
            ChallengeEngine.FOCUS_MIN -> dao.focusMinutesBetween(from, to)
            ChallengeEngine.HABIT_DAYS -> dao.habitDoneBetween(spec.ref, from, to)
            ChallengeEngine.WITHIN_DAYS -> reports.compute(month, minOf(today, month.atEndOfMonth()))?.daysWithin ?: 0
            ChallengeEngine.NO_LOANS -> if (reports.compute(month, minOf(today, month.atEndOfMonth()))?.hasJarLoans == false) 1 else 0
            ChallengeEngine.SAVE_PLAN -> reports.compute(month, minOf(today, month.atEndOfMonth()))?.let { d ->
                if (d.planned <= 0) 0 else (d.saved * 100 / d.planned).toInt()
            } ?: 0
            else -> 0
        }
    }

    /** Where the month's challenge stands; marks it done when it gets there. */
    suspend fun challengeView(month: YearMonth, today: LocalDate = LocalDate.now()): ChallengeView {
        val c = ensureChallenge(month)
        val opts = options(c)
        val picked = c.picked?.let { opts.getOrNull(it) }
        val value = picked?.let { challengeValue(it, month, today) } ?: 0
        val monthOver = today.isAfter(month.atEndOfMonth())
        var done = c.doneDay != null
        if (!done && picked != null && ChallengeEngine.done(picked, value, monthOver)) {
            dao.updateChallenge(c.copy(doneDay = today.toEpochDay()))
            done = true
        }
        return ChallengeView(month, opts, picked, value, done, c.claimed)
    }

    /** Opens the big chest once: coins plus two treats. Returns the coins (0 if not possible). */
    suspend fun claimChallenge(month: YearMonth, today: LocalDate = LocalDate.now()): Int = db.withTransaction {
        val c = dao.challenge(code(month)) ?: return@withTransaction 0
        if (c.doneDay == null || c.claimed) return@withTransaction 0
        dao.updateChallenge(c.copy(claimed = true))
        earn(ChallengeEngine.CHEST_COINS, R_CHALLENGE, today.toEpochDay())
        repeat(2) { grant(Catalog.treats.random(random).id, today.toEpochDay()) }
        ChallengeEngine.CHEST_COINS
    }

    // ------------------------------------------------------------------ seasons

    /** Today's red envelope during Tết (once a day); the coins, or null. */
    suspend fun claimLixi(today: LocalDate = LocalDate.now()): Int? = db.withTransaction {
        if (!SeasonEngine.lixiDay(today) || dao.countOn(K_LIXI, today.toEpochDay()) > 0) return@withTransaction null
        val coins = SeasonEngine.lixi(random)
        earn(coins, R_LIXI, today.toEpochDay())
        dao.log(GameLogEntity(day = today.toEpochDay(), kind = K_LIXI, data = "$coins", at = clock()))
        coins
    }

    suspend fun lixiTakenToday(today: LocalDate = LocalDate.now()) = dao.countOn(K_LIXI, today.toEpochDay()) > 0

    suspend fun questView(today: LocalDate = LocalDate.now()): QuestView? {
        val season = SeasonEngine.seasonOn(today) ?: return null
        val q = SeasonEngine.questFor(season) ?: return null
        val w = SeasonEngine.window(season, today) ?: return null
        val from = w.start.toEpochDay()
        val to = w.endInclusive.toEpochDay()
        val value = when (q.kind) {
            "LOG_DAYS" -> dao.loggedDaysBetween(from, to).size
            "HABITS_ALL" -> dao.coinDays(RewardRules.R_HABITS_ALL, from, to).size
            "DIARY_DAYS" -> dao.coinDays("diary", from, to).size
            else -> 0
        }
        return QuestView(q, value, dao.countData(K_QUEST, SeasonEngine.questKey(q, today)) > 0)
    }

    /** Gives the season quest's item (or coins if already owned). Returns the item id, "" for coins, null if not done. */
    suspend fun claimQuest(today: LocalDate = LocalDate.now()): String? {
        val v = questView(today) ?: return null
        if (!v.done || v.claimed) return null
        val day = today.toEpochDay()
        dao.log(GameLogEntity(day = day, kind = K_QUEST, data = SeasonEngine.questKey(v.quest, today), at = clock()))
        return if (dao.ownedItem(v.quest.rewardItem) == null) {
            grant(v.quest.rewardItem, day)
            v.quest.rewardItem
        } else {
            earn(100, R_QUEST, day)
            ""
        }
    }

    fun season(today: LocalDate = LocalDate.now()): Season? = SeasonEngine.seasonOn(today)

    companion object {
        const val K_SPIN = "SPIN"
        const val K_LIXI = "LIXI"
        const val K_FEED = "FEED"
        const val K_QUEST = "QUEST"
        const val K_BOOST = "BOOST"
        const val K_BUY = "BUY"

        const val R_SHOP = "shop"
        const val R_ADOPT = "adopt"
        const val R_REWARD = "real_reward"
        const val R_WHEEL = "wheel"
        const val R_ACH = "ach_"
        const val R_CHALLENGE = "challenge"
        const val R_LIXI = "lixi"
        const val R_QUEST = "season_quest"
        const val R_BOOST = "boost"

        /** How long a treat keeps the cat extra happy. */
        const val HAPPY_MS = 6 * 3_600_000L

        fun code(m: YearMonth) = m.year * 100 + m.monthValue
    }
}
