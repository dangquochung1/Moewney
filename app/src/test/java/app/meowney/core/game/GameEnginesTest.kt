package app.meowney.core.game

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random

class GameEnginesTest {
    @Test fun catalogDefaultsAndCatPrices() {
        for (slot in Catalog.decorSlots) assertThat(Catalog.defaultFor(slot)).isNotNull()
        assertThat(Catalog.items.map { it.id }.toSet()).hasSize(Catalog.items.size)
        assertThat(Catalog.items.filter { it.default }.all { it.price == 0 }).isTrue()
        assertThat((1..6).map { Catalog.catPrice(it) }).containsExactly(1000, 1500, 2000, 2500, 2500, 2500).inOrder()
        assertThat(Catalog.wheelRares.none { it.season != null || it.slot == Slot.FOOD || it.default }).isTrue()
    }

    @Test fun wheelFallsBackToCoins() {
        val r = Random(1)
        assertThat(WheelEngine.resolve(Prize.TICKET, r, null, emptyList(), false).coins).isEqualTo(15)
        assertThat(WheelEngine.resolve(Prize.TICKET, r, "Đọc sách", emptyList(), false).ticketHabit).isEqualTo("Đọc sách")
        assertThat(WheelEngine.resolve(Prize.RARE, r, null, emptyList(), false).coins).isEqualTo(50)
        assertThat(WheelEngine.resolve(Prize.RARE, r, null, listOf("bowl_blue"), false).itemId).isEqualTo("bowl_blue")
        assertThat(WheelEngine.resolve(Prize.BOOST, r, null, emptyList(), true).coins).isEqualTo(10)
        assertThat(WheelEngine.resolve(Prize.BOOST, r, null, emptyList(), false).boost).isTrue()
        assertThat(Catalog[WheelEngine.resolve(Prize.TREAT, r, null, emptyList(), false).itemId!!]!!.slot).isEqualTo(Slot.FOOD)
        assertThat(WheelEngine.spinsToday(false)).isEqualTo(1)
        assertThat(WheelEngine.spinsToday(true)).isEqualTo(2)
        // every slot comes up
        val seen = (0 until 2000).map { WheelEngine.pick(r) }.toSet()
        assertThat(seen).hasSize(8)
    }

    @Test fun achievementsGiveEachTierOnce() {
        val metrics = mapOf(Metric.LOG_DAYS to 35L, Metric.CATS to 1L)
        val fresh = AchievementEngine.newTiers(metrics, emptySet())
        assertThat(fresh.filter { it.first.id == "log_days" }.map { it.second }).containsExactly(1, 2)
        assertThat(fresh.none { it.first.id == "cats" }).isTrue()
        val again = AchievementEngine.newTiers(metrics, setOf("log_days" to 1, "log_days" to 2))
        assertThat(again.none { it.first.id == "log_days" }).isTrue()
    }

    @Test fun challengesFitWhatSenUses() {
        val month = YearMonth.of(2026, 12)
        val plain = ChallengeEngine.suggest(ChallengeInputs(31), month)
        assertThat(plain).hasSize(3)
        assertThat(plain.map { it.kind }.toSet()).hasSize(3)
        assertThat(plain.none { it.kind == ChallengeEngine.HABIT_DAYS || it.kind == ChallengeEngine.DIARY_DAYS }).isTrue()
        val busy = ChallengeEngine.suggest(ChallengeInputs(31, habits = listOf(1L to "Đọc sách"), usesDiary = true, usesTodos = true), month)
        assertThat(busy).hasSize(3)
        assertThat(busy.count { it.kind in setOf(ChallengeEngine.HABIT_DAYS, ChallengeEngine.DIARY_DAYS, ChallengeEngine.TODOS) }).isEqualTo(2)
        assertThat(ChallengeEngine.suggest(ChallengeInputs(31, usesDiary = true), month)).isEqualTo(ChallengeEngine.suggest(ChallengeInputs(31, usesDiary = true), month))
        val noLoans = ChallengeSpec(ChallengeEngine.NO_LOANS, 1, "x")
        assertThat(ChallengeEngine.done(noLoans, 1, monthOver = false)).isFalse()
        assertThat(ChallengeEngine.done(noLoans, 1, monthOver = true)).isTrue()
    }

    @Test fun seasonsByDate() {
        assertThat(SeasonEngine.seasonOn(LocalDate.of(2026, 12, 20))).isEqualTo(Season.NOEL)
        assertThat(SeasonEngine.seasonOn(LocalDate.of(2027, 1, 1))).isEqualTo(Season.NOEL)
        assertThat(SeasonEngine.seasonOn(LocalDate.of(2026, 10, 31))).isEqualTo(Season.HALLOWEEN)
        assertThat(SeasonEngine.seasonOn(LocalDate.of(2026, 9, 20))).isEqualTo(Season.AUTUMN)
        assertThat(SeasonEngine.seasonOn(LocalDate.of(2027, 2, 6))).isEqualTo(Season.TET)
        assertThat(SeasonEngine.seasonOn(LocalDate.of(2027, 1, 31))).isEqualTo(Season.TET) // 23 tháng chạp
        assertThat(SeasonEngine.seasonOn(LocalDate.of(2026, 11, 10))).isNull()
        assertThat(SeasonEngine.lixiDay(LocalDate.of(2027, 2, 6))).isTrue()
        assertThat(SeasonEngine.lixiDay(LocalDate.of(2027, 2, 12))).isTrue()
        assertThat(SeasonEngine.lixiDay(LocalDate.of(2027, 2, 13))).isFalse()
        assertThat(SeasonEngine.lixiDay(LocalDate.of(2027, 2, 5))).isFalse()
        assertThat(SeasonEngine.freeHat(Season.NOEL)).isEqualTo("santa")
        val q = SeasonEngine.questFor(Season.NOEL)!!
        assertThat(SeasonEngine.questKey(q, LocalDate.of(2027, 1, 1))).isEqualTo(SeasonEngine.questKey(q, LocalDate.of(2026, 12, 20)))
    }
}
