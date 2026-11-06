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

/**
 * A cat wandering the room. Pure state + rules; [update] is called every frame
 * with room-pixel coordinates, [draw] paints it at room resolution.
 */
class Cat(
    val color: Int,
    startX: Float,
    startY: Float,
    private val room: RoomAssets,
    private val random: Random = Random.Default,
) {
    var x = startX; private set
    var y = startY; private set
    var mood = Mood.Happy

    private var anim = "sit"
    private var animStart = 0L
    private var once = false
    private var target: Pair<Float, Float>? = null
    private var targetName = "rug"
    private var busyUntil = 0L
    private var lastDirection = "down"

    /** Time when the current one-shot animation ends. */
    private fun play(name: String, now: Long, loop: Boolean, atlas: SpriteAtlas) {
        anim = name
        animStart = now
        once = !loop
        if (!loop) {
            val s = atlas["cat/$color/$name"]
            busyUntil = now + s.frames * 1000L / s.entry.fps.coerceAtLeast(1)
        }
    }

    private fun idle(now: Long, atlas: SpriteAtlas, night: Boolean) {
        if (night && targetName == "bed") {
            play(if (random.nextBoolean()) "sleep1_r" else "sleep2_l", now, loop = true, atlas)
            busyUntil = now + 60_000
            return
        }
        val choices = when (mood) {
            Mood.Happy -> listOf("sit" to 4, "wash_sit" to 3, "yawn_sit" to 1, "meow_sit" to 2, "lie" to 2,
                "paw_down" to 2, "scratch_r" to 1, "rest" to 1)
            Mood.Calm -> listOf("sit" to 4, "lie" to 3, "rest" to 2, "wash_sit" to 2, "yawn_sit" to 2)
            Mood.Worried -> listOf("sit" to 3, "hiss_r" to 1, "stand" to 2, "wash_sit" to 1)
            Mood.Sad -> listOf("sad" to 4, "lie" to 3, "rest" to 2)
        }
        val pick = weighted(choices)
        val loops = pick in setOf("sit", "lie", "rest", "sad", "wash_sit")
        play(pick, now, loop = loops, atlas)
        if (loops) busyUntil = now + random.nextLong(2_500, 6_000)
    }

    private fun weighted(choices: List<Pair<String, Int>>): String {
        var r = random.nextInt(choices.sumOf { it.second })
        for ((name, w) in choices) {
            if (r < w) return name
            r -= w
        }
        return choices.first().first
    }

    fun goTo(spot: String) {
        targetName = spot
        target = room.spot(spot)
    }

    /** Tap on the cat: stop, look up, meow. */
    fun poke(now: Long, atlas: SpriteAtlas) {
        target = null
        play("meow_sit", now, loop = false, atlas)
        busyUntil = now + 3_000
    }

    fun update(now: Long, dtMs: Long, atlas: SpriteAtlas, hour: Int) {
        val night = hour >= 22 || hour < 6
        val goal = target
        if (goal != null) {
            val dx = goal.first - x
            val dy = goal.second - y
            val dist = hypot(dx, dy)
            val step = SPEED * dtMs / 1000f
            if (dist <= step) {
                x = goal.first; y = goal.second
                target = null
                idle(now, atlas, night)
            } else {
                x += dx / dist * step
                y += dy / dist * step
                val dir = direction(dx, dy)
                if (anim != "walk_$dir") play("walk_$dir", now, loop = true, atlas)
                lastDirection = dir
            }
            return
        }
        if (now < busyUntil) return
        when {
            night && targetName != "bed" -> goTo("bed")
            random.nextFloat() < 0.55f -> goTo(room.file.spots.keys.filter { it != targetName && it != "bed" }.random(random))
            else -> idle(now, atlas, night)
        }
    }

    fun draw(scope: DrawScope, atlas: SpriteAtlas, now: Long) {
        val sprite = atlas.getOrNull("cat/$color/$anim") ?: atlas["cat/$color/sit"]
        val elapsed = now - animStart
        val frame = if (once) {
            (elapsed * sprite.entry.fps / 1000).toInt().coerceAtMost(sprite.frames - 1)
        } else {
            sprite.frameAt(elapsed)
        }
        scope.drawSprite(sprite, frame, x, y)
    }

    fun hit(px: Float, py: Float) = abs(px - x) <= 12 && py <= y + 2 && py >= y - 26

    private fun direction(dx: Float, dy: Float): String {
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = right, 90 = down
        val names = arrayOf("right", "downright", "down", "downleft", "left", "upleft", "up", "upright")
        val index = (((angle + 360 + 22.5) % 360) / 45).toInt()
        return names[index]
    }

    private companion object {
        const val SPEED = 22f // room pixels per second
    }
}
