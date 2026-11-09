package app.meowney.core.data

import app.meowney.AppContainer
import app.meowney.core.game.MoodEngine
import app.meowney.core.money.JarCode
import app.meowney.core.money.MoneyEngine
import app.meowney.feature.room.Mood
import app.meowney.feature.room.QuickTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import java.time.LocalDate

/** Today's numbers in one read, for places without a ViewModel (the home-screen widgets). */
data class TodaySnapshot(
    val onboarded: Boolean,
    val catColor: Int,
    val left: Long,
    val spent: Long,
    val mood: Mood,
    val templates: List<QuickTemplate>,
)

/** Re-reads the snapshot whenever money, bills, quick buttons or settings change. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun todaySnapshots(c: AppContainer): Flow<TodaySnapshot> =
    combine(c.money.jarStates, c.money.recurring, c.money.templates, c.settings.flow) { _, _, _, _ -> }
        .mapLatest { todaySnapshot(c) }

suspend fun todaySnapshot(c: AppContainer, today: LocalDate = LocalDate.now()): TodaySnapshot {
    val settings = c.settings.now()
    if (!settings.onboarded) return TodaySnapshot(false, settings.catColor, 0, 0, Mood.Calm, emptyList())
    val jars = c.money.jarStates.first()
    val spendIds = jars.filter { it.code == JarCode.NEC || it.code == JarCode.PLAY }.map { it.jar.id }
    val period = MoneyEngine.period(today, settings.payday)
    val spent = c.money.spentOn(today, spendIds).first()
    val reserved = c.money.recurringNow()
        .filter { it.jarId in spendIds && it.nextDueDay <= period.end.toEpochDay() }
        .sumOf { it.amount }
    val allowance = MoneyEngine.allowance(today, period, jars.filter { it.jar.id in spendIds }.sumOf { it.balance }, spent, reserved)
    val logged = c.money.countOn(today).first() > 0
    val names = c.money.categoriesNow().associate { it.id to it.name }
    val pinned = c.money.templates.first().map { QuickTemplate(it.id, it.label, it.amount, it.categoryId, true) }
    val frequent = c.money.frequentExpenses(today.minusDays(60), 6).first()
        .filter { f -> pinned.none { it.categoryId == f.categoryId && it.amount == f.amount } }
        .map { QuickTemplate(null, names[it.categoryId] ?: "Chi", it.amount, it.categoryId, false) }
    return TodaySnapshot(
        onboarded = true,
        catColor = settings.catColor,
        left = allowance.left,
        spent = spent,
        mood = MoodEngine.mood(allowance.left, c.money.loansNow().isNotEmpty(), logged),
        templates = (pinned + frequent).take(3),
    )
}
