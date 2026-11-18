package app.meowney.core.data

import app.meowney.AppContainer
import app.meowney.core.game.MoodEngine
import app.meowney.core.game.SeasonEngine
import app.meowney.core.money.JarCode
import app.meowney.core.notify.NudgeEngine
import app.meowney.feature.room.Mood
import app.meowney.feature.room.QuickTemplate
import app.meowney.feature.room.RoomLook
import app.meowney.feature.room.look
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate

/** How the cat looks on the home-screen widget. Night is decided by the clock when drawing. */
enum class CatScene { CALM, HAPPY, WORRIED, ANGRY, SAD }

/**
 * What the home-screen widgets show, in one read, for places without a ViewModel.
 * There is no money here on purpose: the widget is the cat's room, not a balance.
 */
data class TodaySnapshot(
    val onboarded: Boolean,
    val catColor: Int,
    val scene: CatScene,
    /** How the room is dressed (what the shop sold and the wardrobe put on). */
    val look: RoomLook,
    /** One-tap expenses for the wide widget. */
    val templates: List<QuickTemplate>,
)

/** Re-reads the snapshot whenever money, to-dos, the wardrobe, quick buttons or settings change. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun todaySnapshots(c: AppContainer): Flow<TodaySnapshot> =
    combine(c.money.jarStates, c.calendar.todos, c.money.templates, c.settings.flow, c.game.state) { _, _, _, _, _ -> }
        .mapLatest { todaySnapshot(c) }

/** Angry about overdue work beats sad about being left alone beats worried about money. */
fun catScene(overdue: Int, daysAway: Int, mood: Mood): CatScene = when {
    overdue > 0 -> CatScene.ANGRY
    daysAway >= NudgeEngine.SAD_AFTER_DAYS -> CatScene.SAD
    mood == Mood.Worried -> CatScene.WORRIED
    mood == Mood.Happy -> CatScene.HAPPY
    else -> CatScene.CALM
}

suspend fun todaySnapshot(c: AppContainer, today: LocalDate = LocalDate.now()): TodaySnapshot {
    val settings = c.settings.now()
    if (!settings.onboarded) return TodaySnapshot(false, settings.catColor, CatScene.CALM, RoomLook(), emptyList())
    val jars = c.money.jarStates.first()
    val logged = c.money.countOn(today).first() > 0
    val mood = MoodEngine.mood(jars.any { it.code != JarCode.UNALLOC && it.balance < 0 }, c.money.loansNow().isNotEmpty(), logged)
    val overdue = c.calendar.inboxAndOverdue(today).first().second.size
    val daysAway = settings.lastOpenDay?.let { (today.toEpochDay() - it).toInt() } ?: 0
    val names = c.money.categoriesNow().associate { it.id to it.name }
    val pinned = c.money.templates.first().map { QuickTemplate(it.id, it.label, it.amount, it.categoryId, true) }
    val frequent = c.money.frequentExpenses(today.minusDays(60), 6).first()
        .filter { f -> pinned.none { it.categoryId == f.categoryId && it.amount == f.amount } }
        .map { QuickTemplate(null, names[it.categoryId] ?: "Chi", it.amount, it.categoryId, false) }
    return TodaySnapshot(
        onboarded = true,
        catColor = settings.catColor,
        scene = catScene(overdue, daysAway, mood),
        look = c.game.state.first().look(SeasonEngine.seasonOn(today)),
        templates = (pinned + frequent).take(3),
    )
}
