package app.meowney.feature.room

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File
import kotlin.random.Random

class StageGeometryTest {
    private val circle = StageCircle(32f, 35f, 27f)

    @Test fun randomWalkNeverLeavesTheCircle() {
        val random = Random(42)
        var x = 32f
        var y = 57f
        assertThat(circle.fits(x, y)).isTrue()
        repeat(10_000) {
            // Any target, even far outside, is clamped first - exactly what the cat does.
            val (tx, ty) = circle.clamp(random.nextFloat() * 80 - 8, random.nextFloat() * 80 - 8)
            assertThat(circle.fits(tx, ty)).isTrue()
            // Walk there in small steps; every step must stay inside.
            repeat(8) { step ->
                val t = (step + 1) / 8f
                val px = x + (tx - x) * t
                val py = y + (ty - y) * t
                assertThat(circle.fits(px, py)).isTrue()
            }
            x = tx
            y = ty
        }
    }

    @Test fun clampKeepsValidPointsAndPullsOthersIn() {
        assertThat(circle.clamp(32f, 57f)).isEqualTo(32f to 57f)
        val (x, y) = circle.clamp(0f, 64f)
        assertThat(circle.fits(x, y)).isTrue()
        assertThat(x).isGreaterThan(0f)
    }

    @Test fun cornerSpotsFitTheCircle() {
        val json = Json.parseToJsonElement(File("src/main/assets/room/corner.json").readText()).jsonObject
        val (cx, cy, r) = json["circle"]!!.jsonArray.map { it.jsonPrimitive.float }
        val stage = StageCircle(cx, cy, r)
        val spots = json["spots"]!!.jsonObject.mapValues { (_, v) ->
            v.jsonArray.map { it.jsonPrimitive.float }.let { it[0] to it[1] }
        }
        for (name in listOf("home", "bowl_cat", "yarn_cat", "bed_cat")) {
            val (x, y) = spots.getValue(name)
            assertWithMessage(name).that(stage.fits(x, y)).isTrue()
        }
        // Props are small; their base must at least sit inside the circle.
        for (name in listOf("bowl", "yarn")) {
            val (x, y) = spots.getValue(name)
            assertWithMessage(name).that(stage.fits(x, y, w = 8f, h = 8f)).isTrue()
        }
    }

    @Test fun stageScaleIsWholeAndFitsTheScreen() {
        assertThat(stageScale(1080, 2200, 54)).isEqualTo(16)
        assertThat(stageScale(720, 1480, 54)).isEqualTo(10)
        assertThat(stageScale(1440, 2960, 54)).isEqualTo(21)
        // never wider than the screen
        for (w in listOf(720, 1080, 1440)) {
            assertThat(stageScale(w, 4000, 54) * 54).isAtMost(w)
        }
    }
}
