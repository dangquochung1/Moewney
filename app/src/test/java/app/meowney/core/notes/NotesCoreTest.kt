package app.meowney.core.notes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotesCoreTest {
    @Test fun foldRemovesVietnameseAccents() {
        assertThat(NoteText.fold("Đà Lạt")).isEqualTo("da lat")
        assertThat(NoteText.fold("  Sạc   DỰ phòng ")).isEqualTo("sac du phong")
        assertThat(NoteText.cleanTag(" #du  lịch ")).isEqualTo("du lịch")
    }

    @Test fun promptsPutTheDayFirstAndStayStable() {
        val ctx = PromptContext(overspent = true, spent = 500_000, allHabitsDone = true)
        val first = DiaryPrompts.pick(ctx, 20_400)
        assertThat(first).contains("mạnh tay")
        assertThat(DiaryPrompts.pick(ctx, 20_400, 1)).contains("thói quen")
        assertThat(DiaryPrompts.pick(ctx, 20_400, 5)).isEqualTo(DiaryPrompts.pick(ctx, 20_400, 5))
        val all = DiaryPrompts.list(ctx, 20_400)
        assertThat(DiaryPrompts.pick(ctx, 20_400, all.size)).isEqualTo(first)
        // a plain day starts from the general list, in a day-dependent order
        assertThat(DiaryPrompts.general).contains(DiaryPrompts.pick(PromptContext(spent = 1), 20_401))
        assertThat(DiaryPrompts.pick(PromptContext(yesterdayMood = 1, spent = 1), 20_401)).contains("Hôm qua")
    }

    @Test fun pixelFilterSizeAndPalette() {
        val w = 300; val h = 200
        val src = IntArray(w * h) { i -> (0xFF shl 24) or ((i % w) shl 16) or (((i / w) % 256) shl 8) or 0x40 }
        val (warm, tw, th) = PixelFilter.apply(src, w, h, PhotoStyle.WARM, 96)
        assertThat(tw).isEqualTo(96)
        assertThat(th).isEqualTo(64)
        assertThat(warm.size).isEqualTo(96 * 64)
        val allowed = PixelFilter.WARM.toSet()
        assertThat(warm.all { it in allowed }).isTrue()
        val (px, _, _) = PixelFilter.apply(src, w, h, PhotoStyle.PIXEL, 64)
        assertThat(px.toSet().size).isAtMost(32)
        // a small picture is not blown up
        assertThat(PixelFilter.targetSize(40, 30, 96)).isEqualTo(40 to 30)
    }

    @Test fun streaksAndLowMood() {
        val written = setOf(10L, 11L, 12L, 14L)
        assertThat(MoodEngine.streak(written, 12)).isEqualTo(3)
        assertThat(MoodEngine.streak(written, 13)).isEqualTo(3) // today not written yet
        assertThat(MoodEngine.streak(written, 14)).isEqualTo(1)
        assertThat(MoodEngine.streak(written, 16)).isEqualTo(0)
        assertThat(MoodEngine.lowStreak(mapOf(10L to 2, 11L to 1, 12L to 2), 12)).isTrue()
        assertThat(MoodEngine.lowStreak(mapOf(10L to 2, 11L to 3, 12L to 2), 12)).isFalse()
        assertThat(MoodEngine.lowStreak(mapOf(11L to 1, 12L to 2), 12)).isFalse()
    }

    @Test fun moodMonthComparesGoodAndBadDays() {
        val m = MoodEngine.month(
            moods = mapOf(1L to 5, 2L to 4, 3L to 1),
            from = 1, to = 30,
            spent = mapOf(1L to 100L, 2L to 300L, 3L to 1000L),
            habits = mapOf(1L to (3 to 4), 2L to (1 to 4), 3L to (0 to 4)),
        )
        assertThat(m.spentGood).isEqualTo(200)
        assertThat(m.spentBad).isEqualTo(1000)
        assertThat(m.habitsGood).isEqualTo(50)
        assertThat(m.habitsBad).isEqualTo(0)
        assertThat(m.written).isEqualTo(3)
        assertThat(m.days).isEqualTo(30)
    }

    @Test fun lockClosesAfterAMinuteAwayButNotForOwnTrips() {
        var now = 0L
        val lock = NotesLock { now }
        assertThat(lock.unlocked.value).isFalse()
        lock.unlock()
        lock.onAppHidden(); now += 30_000; lock.onAppShown()
        assertThat(lock.unlocked.value).isTrue()
        lock.ownTrip(); lock.onAppHidden(); now += 300_000; lock.onAppShown()
        assertThat(lock.unlocked.value).isTrue()
        lock.onAppHidden(); now += 61_000; lock.onAppShown()
        assertThat(lock.unlocked.value).isFalse()
    }
}
