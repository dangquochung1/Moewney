package app.meowney.feature.room

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

class CatBrainTest {
    private val oneShot: (String) -> Long = { 1_000L }

    /** Drives [cat] for [ms] at [hour], one 40 ms frame at a time, calling [each] after every frame. */
    private class Sim(val cat: Cat, var hour: Int) {
        var now = 0L

        fun run(ms: Long, each: (Cat) -> Unit = {}) {
            val end = now + ms
            while (now < end) {
                now += 40
                cat.update(now, 40, hour) { 1_000L }
                each(cat)
            }
        }
    }

    @Test fun napHoursAndNightSky() {
        val nap = (0..23).filter { CatClock.sleepHour(it) }
        assertThat(nap).containsExactly(0, 1, 2, 3, 8, 9, 10, 11, 21, 22, 23).inOrder()
        val night = (0..23).filter { CatClock.isNight(it) }
        assertThat(night).containsExactly(0, 1, 2, 3, 4, 5, 6, 18, 19, 20, 21, 22, 23).inOrder()
    }

    @Test fun openedInNapHoursItIsAlreadyAsleepAndStaysAsleep() {
        val sim = Sim(Cat(5, Random(1)), hour = 9)
        sim.run(40)
        assertThat(sim.cat.asleep).isTrue()
        assertThat(sim.cat.props).contains(Prop.Bed)
        val seen = mutableSetOf<String>()
        sim.run(30 * 60_000L) { seen += it.anim }
        assertThat(Cat.SLEEPS).containsAtLeastElementsIn(seen)
        assertThat(seen.size).isAtLeast(2) // it turns over now and then
    }

    @Test fun strokingWakesItWithAYawnThenItWashesAndDozesOffAgain() {
        val sim = Sim(Cat(5, Random(2)), hour = 22)
        sim.run(5_000)
        sim.cat.petStart()
        sim.run(40)
        assertThat(sim.cat.asleep).isFalse()
        assertThat(sim.cat.anim).isEqualTo("yawn_lie")
        sim.run(2_000)
        assertThat(sim.cat.anim).isEqualTo("wash_lie")
        sim.run(10_000)
        assertThat(sim.cat.anim).isEqualTo("wash_lie") // as long as Sen strokes

        sim.cat.petEnd()
        sim.run(3_000)
        assertThat(sim.cat.anim).isEqualTo("lie")
        assertThat(sim.cat.poseFrame).isEqualTo(0)
        assertThat(sim.cat.asleep).isFalse()
        sim.run(12_000)
        assertThat(sim.cat.asleep).isTrue()
        assertThat(sim.cat.anim).isIn(Cat.SLEEPS)
    }

    @Test fun strokingAnAwakeCatMakesItSitAndWash() {
        val sim = Sim(Cat(5, Random(3)), hour = 15)
        sim.run(1_000)
        sim.cat.petStart()
        sim.run(40)
        assertThat(sim.cat.anim).isEqualTo("wash_sit")
        sim.cat.petEnd()
        sim.run(2_000)
        assertThat(sim.cat.anim).isEqualTo("sit")
    }

    @Test fun aTapDoesNotWakeASleepingCat() {
        val sim = Sim(Cat(5, Random(4)), hour = 23)
        sim.run(1_000)
        val before = sim.cat.anim
        assertThat(sim.cat.poke()).isFalse()
        sim.run(500)
        assertThat(sim.cat.anim).isEqualTo(before)

        val awake = Sim(Cat(5, Random(4)), hour = 15)
        awake.run(1_000)
        assertThat(awake.cat.poke()).isTrue()
        awake.run(40)
        assertThat(awake.cat.anim).isEqualTo("meow_sit")
    }

    @Test fun awakeItIsLazyHoldsPosesStillAndWalksRarely() {
        val allowed = setOf("sit", "lie", "rest", "sad", "wash_sit", "yawn_sit", "meow_sit", "wash_lie", "yawn_lie", "walk_left", "walk_right")
        for (seed in 1..20) {
            val sim = Sim(Cat(5, Random(seed)), hour = 15)
            var changes = 0
            var walks = 0
            var last = ""
            var lastWalked = 0f
            var lastPose: Pair<String, Int?>? = null
            sim.run(10 * 60_000L) { c ->
                assertThat(c.anim).isIn(allowed)
                val shown = c.anim + c.poseFrame
                if (shown != last) {
                    changes++
                    if (c.anim.startsWith("walk_")) walks++
                    last = shown
                }
                // the ground only moves while walking
                if (c.walked != lastWalked) assertThat(c.anim).startsWith("walk_")
                lastWalked = c.walked
                // a pose sheet always shows one fixed frame
                if (c.anim in setOf("sit", "lie", "rest")) {
                    assertThat(c.poseFrame).isNotNull()
                    val pose = c.anim to c.poseFrame
                    lastPose = pose
                }
            }
            assertThat(lastPose).isNotNull()
            assertThat(walks).isAtMost(4)
            // 20–40 s per pose: a few dozen changes in 10 minutes, not hundreds
            assertThat(changes).isAtMost(60)
        }
    }

    @Test fun napHoursEndWithAYawn() {
        val sim = Sim(Cat(5, Random(5)), hour = 11)
        sim.run(1_000)
        assertThat(sim.cat.asleep).isTrue()
        sim.hour = 12
        sim.run(40)
        assertThat(sim.cat.asleep).isFalse()
        assertThat(sim.cat.anim).isEqualTo("yawn_lie")
        sim.run(2_000)
        assertThat(sim.cat.props).doesNotContain(Prop.Bed)
        assertThat(sim.cat.anim).isEqualTo("lie")
    }

    @Test fun focusTimeSendsItToSleepSoon() {
        val sim = Sim(Cat(5, Random(6)), hour = 15)
        sim.run(2_000)
        assertThat(sim.cat.asleep).isFalse()
        sim.cat.forceSleep = true
        sim.run(8_000)
        assertThat(sim.cat.asleep).isTrue()
    }

    @Test fun eventsWakeItBrieflyInNapHours() {
        val sim = Sim(Cat(5, Random(7)), hour = 10)
        sim.run(1_000)
        sim.cat.feed()
        sim.run(40)
        assertThat(sim.cat.anim).isEqualTo("yawn_lie")
        sim.run(2_000)
        assertThat(sim.cat.anim).isEqualTo("eat_left")
        assertThat(sim.cat.props).contains(Prop.Bowl)
        sim.run(60_000)
        assertThat(sim.cat.asleep).isTrue()
        assertThat(sim.cat.props).doesNotContain(Prop.Bowl)
    }
}
