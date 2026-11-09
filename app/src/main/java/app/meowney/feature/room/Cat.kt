package app.meowney.feature.room

import androidx.compose.ui.graphics.drawscope.DrawScope
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.sprite.drawSprite
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.random.Random

/** How the cat feels; later computed by MoodEngine from money, habits and logging. */
enum class Mood { Happy, Calm, Worried, Sad }

/** Things that appear in the circle only while the cat uses them. */
enum class Prop(val sprite: String, val spot: String) {
    Bed("prop/catbed", "bed"),
    Bowl("prop/pet_food_red", "bowl"),
    Yarn("prop/yarnball", "yarn"),
}

/**
 * The cat in its round corner. Pure state + rules: [update] runs every frame in scene
 * pixels, [draw] paints the props and the cat. Behaviour is a queue of small steps
 * (walk, animate, show/hide a prop) so events like feeding are just a sequence.
 */
class Cat(
    val color: Int,
    private val stage: StageCircle,
    private val spots: Map<String, Pair<Float, Float>>,
    private val random: Random = Random.Default,
) {
    var x: Float
        private set
    var y: Float
        private set
    var mood = Mood.Happy

    /** When true the cat sleeps regardless of the clock (demo / sad mood). */
    var forceSleep = false

    /** "santa", "santa2", "antlers" (sprites hat/<name>/<anim>), or null. */
    var hat: String? = null

    /** Sprites of the bed, bowl and toy Sen picked in the wardrobe. */
    var propSprites: Map<Prop, String> = emptyMap()

    private val visible = linkedSetOf<Prop>()
    private val steps = ArrayDeque<Step>()
    private var current: Step? = null
    private var stepEnds = 0L
    private var sleeping = false

    private var anim = "sit"
    private var animStart = 0L
    private var once = false

    init {
        val (hx, hy) = spot("home")
        x = hx
        y = hy
    }

    private sealed interface Step {
        data class Walk(val x: Float, val y: Float) : Step
        data class Anim(val name: String, val loop: Boolean, val ms: Long? = null) : Step
        data class Show(val prop: Prop) : Step
        data class Hide(val prop: Prop) : Step
    }

    fun spot(name: String): Pair<Float, Float> = spots[name] ?: (stage.cx to stage.cy + 20f)

    // ------------------------------------------------------------------ events

    /** Sen logged money: a bowl appears and the cat eats. */
    fun feed() = interrupt {
        add(Step.Show(Prop.Bowl))
        add(walkTo("bowl_cat"))
        add(Step.Anim("eat_left", loop = true, ms = 4_500))
        add(Step.Hide(Prop.Bowl))
        add(Step.Anim("wash_sit", loop = true, ms = 2_500))
    }

    /** A habit is done: a yarn ball rolls in and the cat plays with it. */
    fun play() = interrupt {
        add(Step.Show(Prop.Yarn))
        add(walkTo("yarn_cat"))
        add(Step.Anim("paw_right", loop = true, ms = 4_000))
        add(Step.Hide(Prop.Yarn))
        add(Step.Anim("meow_sit", loop = false))
    }

    /** A happy diary page: meows standing up, then sits proudly. */
    fun cheer() = interrupt {
        add(Step.Anim("meow_stand", loop = false))
        add(Step.Anim("meow_sit", loop = false))
        add(Step.Anim("sit", loop = true, ms = 3_000))
    }

    /** A sad diary page: comes to the front and lies down close by for a while. */
    fun comfort() = interrupt {
        val (cx, cy) = stage.clamp(stage.cx, stage.cy + 24f)
        add(Step.Walk(cx, cy))
        add(Step.Anim("lie", loop = true, ms = 7_000))
        add(Step.Anim("wash_lie", loop = true, ms = 2_500))
    }

    /** Tap on the cat: it stops and meows. */
    fun poke() = interrupt {
        add(Step.Anim("meow_sit", loop = false))
        add(Step.Anim("sit", loop = true, ms = 2_000))
    }

    private fun interrupt(block: ArrayDeque<Step>.() -> Unit) {
        steps.clear()
        current = null
        if (sleeping) {
            sleeping = false
            steps.add(Step.Hide(Prop.Bed))
            steps.add(Step.Anim("yawn_sit", loop = false))
        }
        steps.block()
    }

    private fun walkTo(name: String): Step.Walk {
        val (sx, sy) = spot(name)
        val (cx, cy) = stage.clamp(sx, sy)
        return Step.Walk(cx, cy)
    }

    // ------------------------------------------------------------------ frame update

    fun update(now: Long, dtMs: Long, atlas: SpriteAtlas, hour: Int) {
        val wantsSleep = forceSleep || hour >= 22 || hour < 6 || mood == Mood.Sad
        if (wantsSleep && !sleeping && steps.isEmpty() && current !is Step.Walk) {
            current = null
            sleeping = true
            steps.add(Step.Show(Prop.Bed))
            steps.add(walkTo("bed_cat"))
            steps.add(Step.Anim(if (random.nextBoolean()) "sleep1_r" else "sleep1_l", loop = true, ms = Long.MAX_VALUE / 4))
        } else if (!wantsSleep && sleeping) {
            interrupt { add(Step.Anim("stand", loop = false)) }
        }

        // Show/Hide steps finish instantly, so drain them in the same frame.
        var guard = 0
        while (guard++ < 8) {
            val step = current ?: steps.removeFirstOrNull()?.also { start(it, now, atlas) } ?: run {
                chooseIdle(now, atlas)
                return
            }
            current = step
            when (step) {
                is Step.Show, is Step.Hide -> current = null
                is Step.Walk -> {
                    walk(step, dtMs, now, atlas)
                    return
                }
                is Step.Anim -> {
                    if (now >= stepEnds) current = null else return
                }
            }
        }
    }

    private fun start(step: Step, now: Long, atlas: SpriteAtlas) {
        when (step) {
            is Step.Show -> visible += step.prop
            is Step.Hide -> visible -= step.prop
            is Step.Walk -> Unit
            is Step.Anim -> {
                setAnim(step.name, now, loop = step.loop)
                val sprite = atlas.getOrNull("cat/$color/${step.name}")
                val oneShot = if (sprite == null) 1_000L else sprite.frames * 1000L / sprite.entry.fps.coerceAtLeast(1)
                stepEnds = now + (step.ms ?: oneShot)
            }
        }
    }

    private fun walk(step: Step.Walk, dtMs: Long, now: Long, atlas: SpriteAtlas) {
        val dx = step.x - x
        val dy = step.y - y
        val dist = hypot(dx, dy)
        val move = SPEED * dtMs / 1000f
        if (dist <= move) {
            x = step.x
            y = step.y
            current = null
            return
        }
        x += dx / dist * move
        y += dy / dist * move
        val name = "walk_" + direction(dx, dy)
        if (anim != name && atlas.getOrNull("cat/$color/$name") != null) setAnim(name, now, loop = true)
    }

    private fun chooseIdle(now: Long, atlas: SpriteAtlas) {
        if (random.nextFloat() < 0.4f) {
            val (hx, hy) = spot("home")
            val (tx, ty) = stage.clamp(hx + random.nextInt(-9, 10), hy + random.nextInt(-5, 3))
            steps.add(Step.Walk(tx, ty))
        }
        val choices = when (mood) {
            Mood.Happy -> listOf("sit" to 4, "wash_sit" to 3, "yawn_sit" to 1, "meow_sit" to 2, "paw_down" to 2, "rest" to 1)
            Mood.Calm -> listOf("sit" to 4, "rest" to 2, "wash_sit" to 2, "yawn_sit" to 2)
            Mood.Worried -> listOf("sit" to 3, "hiss_r" to 1, "stand" to 2, "wash_sit" to 1)
            Mood.Sad -> listOf("sad" to 4, "rest" to 2)
        }
        val pick = weighted(choices)
        val loops = pick in setOf("sit", "rest", "sad", "wash_sit")
        steps.add(Step.Anim(pick, loop = loops, ms = if (loops) random.nextLong(2_500, 6_000) else null))
        steps.removeFirstOrNull()?.let {
            current = it
            start(it, now, atlas)
        }
    }

    private fun setAnim(name: String, now: Long, loop: Boolean) {
        anim = name
        animStart = now
        once = !loop
    }

    private fun weighted(choices: List<Pair<String, Int>>): String {
        var r = random.nextInt(choices.sumOf { it.second })
        for ((name, w) in choices) {
            if (r < w) return name
            r -= w
        }
        return choices.first().first
    }

    // ------------------------------------------------------------------ drawing

    fun draw(scope: DrawScope, atlas: SpriteAtlas, now: Long) {
        // The bed is under the cat; bowl and yarn sit on the floor beside it.
        for (prop in Prop.entries) {
            if (prop !in visible) continue
            val (px, py) = spot(prop.spot)
            (atlas.getOrNull(propSprites[prop] ?: prop.sprite) ?: atlas.getOrNull(prop.sprite))?.let { scope.drawSprite(it, 0, px, py) }
        }
        val sprite = atlas.getOrNull("cat/$color/$anim") ?: atlas["cat/$color/sit"]
        val elapsed = now - animStart
        val frame = if (once) {
            (elapsed * sprite.entry.fps / 1000).toInt().coerceAtMost(sprite.frames - 1)
        } else {
            sprite.frameAt(elapsed)
        }
        scope.drawSprite(sprite, frame, x, y)
        // a hat drawn on top, frame by frame (birthdays now, seasons later)
        hat?.let { h -> atlas.getOrNull("hat/$h/$anim")?.let { scope.drawSprite(it, frame.coerceAtMost(it.frames - 1), x, y) } }
    }

    fun hit(px: Float, py: Float) = abs(px - x) <= 12 && py <= y + 2 && py >= y - 26

    private fun direction(dx: Float, dy: Float): String {
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = right, 90 = down
        val names = arrayOf("right", "downright", "down", "downleft", "left", "upleft", "up", "upright")
        return names[(((angle + 360 + 22.5) % 360) / 45).toInt()]
    }

    private companion object {
        const val SPEED = 12f // scene pixels per second (x16 on screen)
    }
}
