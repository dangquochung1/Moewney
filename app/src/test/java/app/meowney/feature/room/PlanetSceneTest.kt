package app.meowney.feature.room

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlanetSceneTest {
    @Test fun aPhoneStageKeepsTheCatAboutTheOldSize() {
        // 1080 x 2400 phone, stage 57% high
        val s = PlanetScene.of(1080, 1368)
        assertThat(s.scale).isEqualTo(16)
        assertThat(s.width * s.scale).isAtLeast(1080)
        assertThat(s.height * s.scale).isAtLeast(1368)
        // the scene is cut by less than one scene pixel
        assertThat(-s.offsetX).isLessThan(s.scale)
        assertThat(-s.offsetY).isLessThan(s.scale)
        // the planet is wider than the screen and only its cap shows
        assertThat(s.radius * 2).isGreaterThan(s.width.toFloat())
        assertThat(s.cy - s.radius).isEqualTo((s.height - PlanetScene.CAP).toFloat())
        // the cat stands on top, in the middle, with room for its ~28 px above
        assertThat(s.feetX).isEqualTo(s.cx)
        assertThat(s.feetY).isEqualTo(s.cy - s.radius + 1)
        assertThat(s.feetY).isAtLeast(28f)
    }

    @Test fun aShortStageScalesDownSoTheCatStillFits() {
        val s = PlanetScene.of(1000, 400)
        assertThat(s.feetY).isAtLeast(28f)
        assertThat(s.scale).isAtLeast(2)
        val tiny = PlanetScene.of(100, 50)
        assertThat(tiny.scale).isEqualTo(2)
    }

    @Test fun walkingHalfwayRoundTurnsThePlanetHalfARound() {
        val r = PlanetScene.RADIUS
        assertThat(PlanetScene.turn((Math.PI * r).toFloat(), r)).isWithin(0.01f).of(-180f)
        assertThat(PlanetScene.turn(0f, r)).isWithin(0.0001f).of(0f)
        // walking right turns the ground the other way (counter-clockwise)
        assertThat(PlanetScene.turn(10f, r)).isLessThan(0f)
    }

    @Test fun screenToSceneRoundTrips() {
        val s = PlanetScene.of(1080, 1368)
        val (x, y) = s.toScene(s.offsetX + s.feetX * s.scale, s.offsetY + s.feetY * s.scale)
        assertThat(x).isWithin(0.001f).of(s.feetX)
        assertThat(y).isWithin(0.001f).of(s.feetY)
    }
}
