package app.meowney.feature.room

import androidx.compose.ui.graphics.drawscope.DrawScope
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import kotlin.math.abs
import kotlin.random.Random

/** How the cat feels; computed by MoodEngine from money, habits and logging. */
enum class Mood { Happy, Calm, Worried, Sad }

/** Things that appear beside the cat only while it uses them; [dx], [dy] from its feet (scene pixels). */
enum class Prop(val sprite: String, val dx: Int, val dy: Int) {
    Bed("prop/catbed", 0, 4),
    Bowl("prop/pet_food_red", -14, 0),
    Yarn("prop/yarnball", 14, 0),
}

/** The cat's day: when it naps and when the sky is dark. */
object CatClock {
    /** Nap hours: 08:00–11:59 and 21:00–03:59. */
    fun sleepHour(hour: Int) = hour in 8..11 || hour >= 21 || hour < 4

    /** Night sky from 18:00 to 06:59. */
    fun isNight(hour: Int) = hour >= 18 || hour < 7
}

/**
 * The cat's behaviour, with no idea where it is drawn: the stage keeps it in the middle and
 * turns the ground by [walked].
 *
 * A lazy cat: it holds one pose for a long while, now and then washes or yawns, and every
 * few minutes walks a few steps (left or right only) to lie somewhere else. In its nap hours
 * it sleeps; stroking wakes it (yawn, then washing while stroked) and it dozes off again.
 *
 * Important for the art: `sit`, `lie`, `rest` and `stand` are sheets of poses (one frame per
 * facing), never animations. Showing them is always a [Step.Pose] with a fixed frame.
 */
class Cat(val color: Int, private val random: Random = Random.Default) {
    var mood = Mood.Calm

    /** When true the cat sleeps whatever the hour (focus time in Pomodoro). */
    var forceSleep = false

    /** "santa", "santa2", "antlers" (sprites hat/<name>/<anim>), or null. */
    var hat: String? = null

    /** Sprites of the bed, bowl and toy Sen picked. */
    var propSprites: Map<Prop, String> = emptyMap()

    /** Signed distance walked, in scene pixels (right is positive). */
    var walked = 0f
        private set

    var asleep = false
        private set

    val props: Set<Prop> get() = visible

    /** What is shown now: [anim] and, for a pose, its fixed [poseFrame] (null while animating). */
    var anim = "sit"
        private set
    var poseFrame: Int? = 0
        private set

    private var once = false
    private var animStart = 0L
    private var now = 0L
    private var started = false
    private var wasSleepy = false

    private enum class Posture { SIT, LOAF, LOAF_RIGHT, STRETCH, SAD }

    private var posture = Posture.SIT
    private var facing = -1
    private var nextWalkAt = 0L
    private var petting = false
    private var petLying = false

    private val visible = linkedSetOf<Prop>()
    private val steps = ArrayDeque<Step>()
    private var current: Step? = null
    private var stepEnds = 0L

    private sealed interface Step {
        data class Pose(val name: String, val frame: Int, val ms: Long) : Step
        data class Anim(val name: String, val loop: Boolean, val ms: Long? = null) : Step
        data class Walk(val dir: Int, val ms: Long) : Step
        data class Nap(val name: String, val ms: Long) : Step
        data class Show(val prop: Prop) : Step
        data class Hide(val prop: Prop) : Step
    }

    // ------------------------------------------------------------------ events

    /** Sen logged money: a bowl appears and the cat eats. */
    fun feed() = interrupt {
        add(Step.Show(Prop.Bowl))
        add(Step.Anim("eat_left", loop = true, ms = 4_500))
        add(Step.Hide(Prop.Bowl))
        add(Step.Anim("wash_sit", loop = true, ms = 2_500))
        posture = Posture.SIT
        add(rest())
    }

    /** A habit is done: a yarn ball rolls in and the cat plays with it. */
    fun play() = interrupt {
        add(Step.Show(Prop.Yarn))
        add(Step.Anim("paw_right", loop = true, ms = 4_000))
        add(Step.Hide(Prop.Yarn))
        add(Step.Anim("meow_sit", loop = false))
        posture = Posture.SIT
        add(rest())
    }

    /** A happy diary page: meows standing up, then sits proudly. */
    fun cheer() = interrupt {
        add(Step.Anim("meow_stand", loop = false))
        add(Step.Anim("meow_sit", loop = false))
        posture = Posture.SIT
        add(Step.Pose("sit", 0, 6_000))
    }

    /** A sad diary page: lies down close by for a while. */
    fun comfort() = interrupt {
        posture = Posture.LOAF
        add(Step.Pose("lie", 0, 7_000))
        add(Step.Anim("wash_lie", loop = true, ms = 2_500))
        add(Step.Pose("lie", 0, 8_000))
    }

    /** Tap: an awake cat meows; a sleeping one does not wake (returns false). */
    fun poke(): Boolean {
        if (asleep) return false
        clear()
        posture = Posture.SIT
        steps.add(Step.Anim("meow_sit", loop = false))
        steps.add(rest())
        return true
    }

    /** Sen starts stroking: a sleeping cat yawns and washes lying down, an awake one sits and washes. */
    fun petStart() {
        if (petting) return
        petting = true
        clear()
        petLying = asleep
        if (asleep) {
            asleep = false
            steps.add(Step.Anim("yawn_lie", loop = false))
        }
    }

    /** Sen stops stroking: a few more licks, a short rest, then the usual (back to sleep in nap hours). */
    fun petEnd() {
        if (!petting) return
        petting = false
        clear()
        if (petLying) {
            posture = Posture.LOAF
            steps.add(Step.Anim("wash_lie", loop = true, ms = 1_500))
            steps.add(Step.Pose("lie", 0, 6_000))
        } else {
            posture = Posture.SIT
            steps.add(Step.Anim("wash_sit", loop = true, ms = 1_500))
            steps.add(rest())
        }
    }

    private fun clear() {
        steps.clear()
        current = null
    }

    private fun interrupt(block: ArrayDeque<Step>.() -> Unit) {
        if (petting) return
        clear()
        if (asleep) {
            asleep = false
            steps.add(Step.Anim("yawn_lie", loop = false))
            steps.add(Step.Hide(Prop.Bed))
        }
        steps.block()
    }

    // ------------------------------------------------------------------ frame update

    /**
     * Runs every frame. [now] is the stage clock in ms; [durationOf] gives a one-shot
     * animation's length (the atlas knows it, tests pass a fixed value).
     */
    fun update(now: Long, dtMs: Long, hour: Int, durationOf: (String) -> Long) {
        this.now = now
        val sleepy = forceSleep || CatClock.sleepHour(hour)
        if (!started) {
            started = true
            nextWalkAt = now + random.nextLong(WALK_EVERY_MIN, WALK_EVERY_MAX)
            if (sleepy) {
                // opened in nap hours: already asleep in its bed
                visible += Prop.Bed
                steps.add(nap())
            } else {
                steps.add(Step.Pose("sit", 0, random.nextLong(HOLD_MIN, HOLD_MAX)))
            }
        }
        // nap time is over, or it has just begun (Pomodoro focus): do not wait for the pose to end
        val c = current
        if (!petting && c is Step.Nap && !sleepy) current = null
        if (!petting && sleepy && !wasSleepy && !asleep && c is Step.Pose && steps.isEmpty()) current = null
        wasSleepy = sleepy

        var guard = 0
        while (guard++ < 10) {
            val step = current ?: steps.removeFirstOrNull()?.also { begin(it, now, durationOf) }
            if (step == null) {
                if (petting) {
                    val loop = if (petLying) "wash_lie" else "wash_sit"
                    if (anim != loop) setAnim(loop, loop = true)
                    return
                }
                choose(now, sleepy)
                continue
            }
            current = step
            when (step) {
                is Step.Show, is Step.Hide -> current = null
                is Step.Walk -> {
                    if (now >= stepEnds) {
                        current = null
                    } else {
                        walked += step.dir * SPEED * dtMs / 1000f
                        return
                    }
                }
                is Step.Pose, is Step.Anim, is Step.Nap -> if (now >= stepEnds) current = null else return
            }
        }
    }

    private fun begin(step: Step, now: Long, durationOf: (String) -> Long) {
        when (step) {
            is Step.Show -> visible += step.prop
            is Step.Hide -> visible -= step.prop
            is Step.Pose -> {
                setPose(step.name, step.frame)
                stepEnds = now + step.ms
            }
            is Step.Anim -> {
                setAnim(step.name, loop = step.loop)
                stepEnds = now + (step.ms ?: durationOf(step.name))
            }
            is Step.Walk -> {
                facing = step.dir
                setAnim(if (step.dir > 0) "walk_right" else "walk_left", loop = true)
                stepEnds = now + step.ms
            }
            is Step.Nap -> {
                asleep = true
                setAnim(step.name, loop = true)
                stepEnds = now + step.ms
            }
        }
    }

    /** Nothing queued: decide what the next minute or so looks like. */
    private fun choose(now: Long, sleepy: Boolean) {
        when {
            sleepy && asleep -> steps.add(nap()) // turn over now and then
            sleepy -> {
                // doze off where it is
                if (posture == Posture.SIT) steps.add(Step.Anim("yawn_sit", loop = false))
                else if (posture == Posture.LOAF) steps.add(Step.Anim("yawn_lie", loop = false))
                steps.add(Step.Pose("lie", 2, 3_000))
                steps.add(Step.Show(Prop.Bed))
                steps.add(nap())
            }
            asleep -> {
                // nap hours are over
                asleep = false
                posture = Posture.LOAF
                steps.add(Step.Anim("yawn_lie", loop = false))
                steps.add(Step.Hide(Prop.Bed))
                steps.add(Step.Pose("lie", 0, random.nextLong(HOLD_MIN, HOLD_MAX)))
            }
            now >= nextWalkAt -> {
                val dir = if (random.nextBoolean()) 1 else -1
                nextWalkAt = now + random.nextLong(WALK_EVERY_MIN, WALK_EVERY_MAX)
                steps.add(Step.Walk(dir, random.nextLong(3_000, 5_001)))
                facing = dir
                posture = settleAfterWalk()
                steps.add(rest())
            }
            else -> {
                if (random.nextFloat() < 0.7f) action()?.let { steps.add(it) }
                steps.add(rest())
            }
        }
    }

    private fun settleAfterWalk(): Posture = if (mood == Mood.Sad) {
        if (random.nextBoolean()) Posture.SAD else Posture.LOAF
    } else {
        val r = random.nextFloat()
        when {
            r < 0.40f -> Posture.SIT
            r < 0.70f -> Posture.LOAF
            r < 0.85f -> Posture.LOAF_RIGHT
            else -> Posture.STRETCH
        }
    }

    /** The still pose of the current posture, held 20–40 seconds. */
    private fun rest(): Step.Pose {
        val ms = random.nextLong(HOLD_MIN, HOLD_MAX)
        return when (posture) {
            Posture.SIT -> Step.Pose("sit", 0, ms)
            Posture.LOAF -> Step.Pose("lie", 0, ms)
            Posture.LOAF_RIGHT -> Step.Pose("lie", 1, ms)
            Posture.STRETCH -> Step.Pose("rest", if (facing > 0) 3 else 2, ms)
            Posture.SAD -> Step.Pose("sad", 0, ms)
        }
    }

    /** A wash or a yawn that starts from the current pose (none for poses without matching art). */
    private fun action(): Step? = when (posture) {
        Posture.SIT -> when (weighted(listOf(0 to 3, 1 to 2, 2 to if (mood == Mood.Happy) 1 else 0))) {
            0 -> Step.Anim("wash_sit", loop = true, ms = random.nextLong(3_000, 5_001))
            1 -> Step.Anim("yawn_sit", loop = false)
            else -> Step.Anim("meow_sit", loop = false)
        }
        Posture.LOAF -> if (random.nextInt(5) < 3) {
            Step.Anim("wash_lie", loop = true, ms = random.nextLong(3_000, 5_001))
        } else {
            Step.Anim("yawn_lie", loop = false)
        }
        else -> null
    }

    private fun nap(): Step.Nap {
        val others = SLEEPS.filter { it != anim }
        return Step.Nap(others[random.nextInt(others.size)], random.nextLong(120_000, 240_001))
    }

    private fun weighted(choices: List<Pair<Int, Int>>): Int {
        var r = random.nextInt(choices.sumOf { it.second })
        for ((v, w) in choices) {
            if (r < w) return v
            r -= w
        }
        return choices.first().first
    }

    private fun setPose(name: String, frame: Int) {
        anim = name
        poseFrame = frame
        once = false
        animStart = now
    }

    private fun setAnim(name: String, loop: Boolean) {
        anim = name
        poseFrame = null
        once = !loop
        animStart = now
    }

    // ------------------------------------------------------------------ drawing

    /** Paints the props and the cat with its feet at ([x], [y]) in scene pixels. */
    fun draw(scope: DrawScope, atlas: SpriteAtlas, now: Long, x: Float, y: Float) {
        for (prop in Prop.entries) {
            if (prop !in visible) continue
            val sprite = atlas.getOrNull(propSprites[prop] ?: prop.sprite) ?: atlas.getOrNull(prop.sprite) ?: continue
            scope.drawSprite(sprite, 0, x + prop.dx, y + prop.dy)
        }
        val sprite = atlas.getOrNull("cat/$color/$anim") ?: atlas["cat/$color/sit"]
        val elapsed = now - animStart
        val frame = poseFrame?.coerceIn(0, sprite.frames - 1) ?: if (once) {
            (elapsed * sprite.entry.fps / 1000).toInt().coerceAtMost(sprite.frames - 1)
        } else {
            sprite.frameAt(elapsed)
        }
        scope.drawSprite(sprite, frame, x, y)
        // a hat drawn on top, frame by frame (birthdays, Noel)
        hat?.let { h -> atlas.getOrNull("hat/$h/$anim")?.let { scope.drawSprite(it, frame.coerceAtMost(it.frames - 1), x, y) } }
    }

    companion object {
        /** Scene pixels per second while walking. */
        const val SPEED = 12f

        private const val HOLD_MIN = 20_000L
        private const val HOLD_MAX = 40_001L
        private const val WALK_EVERY_MIN = 180_000L
        private const val WALK_EVERY_MAX = 300_001L

        val SLEEPS = listOf("sleep1_l", "sleep1_r", "sleep2_l", "sleep2_r", "sleep3_l", "sleep3_r", "sleep4_l", "sleep4_r")

        /** A touch at ([px], [py]) lands on a cat whose feet are at ([x], [y]). */
        fun hit(px: Float, py: Float, x: Float, y: Float) = abs(px - x) <= 12 && py <= y + 2 && py >= y - 26
    }
}
