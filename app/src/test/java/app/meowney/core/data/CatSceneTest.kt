package app.meowney.core.data

import app.meowney.feature.room.Mood
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatSceneTest {
    @Test fun overdueWorkBeatsEverythingElse() {
        assertThat(catScene(overdue = 1, daysAway = 5, mood = Mood.Happy)).isEqualTo(CatScene.ANGRY)
    }

    @Test fun twoDaysAwayMakesTheCatSad() {
        assertThat(catScene(overdue = 0, daysAway = 2, mood = Mood.Worried)).isEqualTo(CatScene.SAD)
        assertThat(catScene(overdue = 0, daysAway = 1, mood = Mood.Calm)).isEqualTo(CatScene.CALM)
    }

    @Test fun otherwiseTheMoneyMoodShows() {
        assertThat(catScene(0, 0, Mood.Worried)).isEqualTo(CatScene.WORRIED)
        assertThat(catScene(0, 0, Mood.Happy)).isEqualTo(CatScene.HAPPY)
        assertThat(catScene(0, 0, Mood.Calm)).isEqualTo(CatScene.CALM)
    }
}
