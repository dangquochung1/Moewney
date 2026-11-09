package app.meowney.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.HabitEntity
import app.meowney.core.db.MeowneyDb
import app.meowney.core.db.RealRewardEntity
import app.meowney.core.game.Catalog
import app.meowney.core.game.ChallengeEngine
import app.meowney.core.game.Slot
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameRepositoryTest {
    private lateinit var db: MeowneyDb
    private lateinit var game: GameRepository
    private var cat = 5 to "Mít"
    private val today = LocalDate.of(2026, 11, 10)

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeowneyDb::class.java)
            .allowMainThreadQueries().build()
        game = GameRepository(db, object : CatProfile {
            override suspend fun current() = cat
            override suspend fun set(color: Int, name: String) {
                cat = color to name
            }
        }, Random(7))
        game.ensureStarter(today)
    }

    @After fun tearDown() = db.close()

    private suspend fun give(coins: Int) = db.money().insertCoins(listOf(CoinEntryEntity(delta = coins, reason = "test", day = today.toEpochDay())))
    private suspend fun coins() = db.game().balance()

    @Test fun buyNeedsCoinsAndKeepsItemsOnce() = runBlocking<Unit> {
        give(250)
        assertThat(game.buy("bowl_blue", today)).isEqualTo(BuyResult.Ok("bowl_blue"))
        assertThat(coins()).isEqualTo(50)
        give(500)
        assertThat(game.buy("bowl_blue", today)).isEqualTo(BuyResult.AlreadyOwned)
        assertThat(game.buy("bowl_red", today)).isEqualTo(BuyResult.AlreadyOwned) // the free one
        // 550 coins: the gold frame (500) fits, then the fish bed (400) is 350 short
        assertThat(game.buy("frame_gold", today)).isEqualTo(BuyResult.Ok("frame_gold"))
        assertThat(game.buy("bed_fish", today)).isEqualTo(BuyResult.NotEnough(350))
        // seasonal things only in their season
        assertThat(game.buy("hat_santa", today)).isEqualTo(BuyResult.NotForSale)
        give(300)
        assertThat(game.buy("hat_santa", LocalDate.of(2026, 12, 20))).isEqualTo(BuyResult.Ok("hat_santa"))
    }

    @Test fun equipOnlyOwnedOrDefault() = runBlocking<Unit> {
        assertThat(game.equip(Slot.BOWL, "bowl_blue")).isFalse()
        give(200)
        game.buy("bowl_blue", today)
        assertThat(game.equip(Slot.BOWL, "bowl_blue")).isTrue()
        assertThat(game.equip(Slot.BED, "bowl_blue")).isFalse()
        assertThat(game.state.first().equipped(Slot.BOWL)).isEqualTo("bowl_blue")
        game.equip(Slot.BOWL, "bowl_red")
        assertThat(game.state.first().equipped(Slot.BOWL)).isEqualTo("bowl_red")
    }

    @Test fun treatsStackAndFeedRaisesAffection() = runBlocking<Unit> {
        give(100)
        game.buy("food_pate", today)
        game.buy("food_pate", today)
        assertThat(game.state.first().qty("food_pate")).isEqualTo(2)
        val r = game.feed("food_pate", today)!!
        assertThat(r.affection).isEqualTo(3)
        game.feed("food_pate", today)
        assertThat(game.state.first().qty("food_pate")).isEqualTo(0)
        assertThat(game.feed("food_pate", today)).isNull()
        assertThat(game.state.first().pets.single().affection).isEqualTo(6)
        assertThat(game.lastFeedAt.first()).isNotNull()
    }

    @Test fun adoptPricesRiseAndShowCat() = runBlocking<Unit> {
        assertThat(game.state.first().pets.map { it.color }).containsExactly(5)
        assertThat(game.adoptPrice()).isEqualTo(1000)
        give(999)
        assertThat(game.adopt(2, "Su", today)).isEqualTo(BuyResult.NotEnough(1))
        give(1)
        assertThat(game.adopt(2, "Su", today)).isEqualTo(BuyResult.Ok(null))
        assertThat(game.adopt(2, "Su", today)).isEqualTo(BuyResult.AlreadyOwned)
        assertThat(game.adoptPrice()).isEqualTo(1500)
        game.showCat(2)
        assertThat(cat).isEqualTo(2 to "Su")
        game.renameCat(2, "Bơ")
        assertThat(cat).isEqualTo(2 to "Bơ")
    }

    @Test fun wheelOneFreeSpinPlusBonus() = runBlocking<Unit> {
        assertThat(game.spin(false, today)).isNotNull()
        assertThat(game.spin(false, today)).isNull()
        assertThat(game.spin(true, today)).isNotNull()
        assertThat(game.spin(true, today)).isNull()
        assertThat(game.spinsUsed(today).first()).isEqualTo(2)
        assertThat(game.spin(false, today.plusDays(1))).isNotNull()
    }

    @Test fun wheelTicketGoesToAHabit() = runBlocking<Unit> {
        db.habits().insertHabit(HabitEntity(name = "Đọc sách", type = "CHECK", icon = 1, createdDay = today.toEpochDay(), closedThrough = today.toEpochDay() - 1))
        var got = false
        for (d in 0 until 60) {
            val (_, out) = game.spin(false, today.plusDays(d.toLong())) ?: continue
            if (out.ticketHabit != null) {
                got = true
                break
            }
        }
        assertThat(got).isTrue()
        assertThat(db.habits().habitsNow().single().tickets).isEqualTo(1)
    }

    @Test fun boostDoublesLogCoins() = runBlocking<Unit> {
        val money = MoneyRepository(db)
        val day = LocalDate.now()
        db.game().log(app.meowney.core.db.GameLogEntity(day = day.toEpochDay(), kind = GameRepository.K_BOOST))
        val percents = mapOf(app.meowney.core.money.JarCode.NEC to 55, app.meowney.core.money.JarCode.FFA to 10, app.meowney.core.money.JarCode.LTSS to 10, app.meowney.core.money.JarCode.EDU to 10, app.meowney.core.money.JarCode.PLAY to 10, app.meowney.core.money.JarCode.GIVE to 5)
        money.onboard(listOf(WalletDraft("Tiền mặt", "CASH", 1_000_000)), 0, percents, mapOf(app.meowney.core.money.JarCode.NEC to 1_000_000L), day.minusDays(3))
        val before = coins()
        val wallet = money.wallets.first().first().id
        val cat = money.categories.first().first { it.type == "EXPENSE" }.id
        val nec = money.jarsNow().first { it.code == "NEC" }.id
        val r = money.addExpense(TxnDraft(35_000, wallet, categoryId = cat, jarId = nec, date = day), app.meowney.core.money.BorrowPlan(emptyList(), 0))
        assertThat(coins() - before).isEqualTo(r.coins.toLong())
        val boost = db.money().coinsOn(day.toEpochDay(), GameRepository.R_BOOST)
        // first log of the day (5) + one log (2), paid twice
        assertThat(boost).isEqualTo(7)
        assertThat(r.coins).isEqualTo(14)
    }

    @Test fun realRewardVoucherUsedOnce() = runBlocking<Unit> {
        val id = game.saveReward(RealRewardEntity(name = "Trà sữa", coins = 300, amount = 35_000))
        assertThat(game.redeem(id, today)).isEqualTo(BuyResult.NotEnough(300))
        give(300)
        assertThat(game.redeem(id, today)).isEqualTo(BuyResult.Ok(null))
        assertThat(coins()).isEqualTo(0)
        val v = game.vouchers.first().single()
        assertThat(v.amount).isEqualTo(35_000)
        assertThat(game.useVoucher(v.id, today)).isTrue()
        assertThat(game.useVoucher(v.id, today)).isFalse()
    }

    @Test fun achievementsPayOnce() = runBlocking<Unit> {
        for (id in listOf("bowl_blue", "toy_bone", "decor_jug")) {
            give(Catalog[id]!!.price)
            game.buy(id, today)
        }
        val first = game.checkAchievements(today)
        assertThat(first.map { it.first.id }).contains("items")
        val before = coins()
        assertThat(game.checkAchievements(today)).isEmpty()
        assertThat(coins()).isEqualTo(before)
    }

    @Test fun challengePickProgressAndChest() = runBlocking<Unit> {
        val month = YearMonth.from(today)
        val view = game.challengeView(month, today)
        assertThat(view.options).hasSize(3)
        assertThat(view.picked).isNull()
        val i = view.options.indexOfFirst { it.kind == ChallengeEngine.NO_SPEND_DAYS }.takeIf { it >= 0 } ?: 0
        game.pickChallenge(month, i)
        game.pickChallenge(month, (i + 1) % 3) // cannot change once picked
        assertThat(game.challengeView(month, today).picked).isEqualTo(view.options[i])
        assertThat(game.claimChallenge(month, today)).isEqualTo(0)
    }

    @Test fun lixiOncePerTetDay() = runBlocking<Unit> {
        assertThat(game.claimLixi(today)).isNull()
        val tet = LocalDate.of(2027, 2, 6)
        val c = game.claimLixi(tet)
        assertThat(c).isNotNull()
        assertThat(game.claimLixi(tet)).isNull()
        assertThat(coins()).isEqualTo(c!!.toLong())
    }
}
