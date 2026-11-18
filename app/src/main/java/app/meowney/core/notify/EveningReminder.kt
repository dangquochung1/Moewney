package app.meowney.core.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.meowney.container
import app.meowney.core.data.kind
import app.meowney.core.habit.HabitType
import app.meowney.feature.room.CatLines
import app.meowney.widget.OPEN_CALENDAR
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The evening check at the time Sen picked: what is still open today, what is overdue, and
 * the money book. The cat reminds, gets angry (once a day) or says well done.
 */
object EveningReminder {
    private const val WORK = "evening_reminder"
    private const val NOTIFICATION_ID = 21

    fun schedule(context: Context, minuteOfDay: Int, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atStartOfDay().plusMinutes(minuteOfDay.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        wm.enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Worker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }

    /** What the evening knows about [today]. */
    suspend fun inputs(context: Context, today: LocalDate = LocalDate.now()): EveningInputs {
        val c = context.container
        val todos = c.calendar.days(today, today).first()[today]?.todos.orEmpty()
        val overdue = c.calendar.inboxAndOverdue(today).first().second
        // quit habits are "held" all day, so they are neither open nor a reason for praise
        val habits = c.habits.todayNow(today).filter { it.habit.kind != HabitType.QUIT }
        return EveningInputs(
            undone = todos.filter { !it.done }.map { it.todo.title },
            overdue = overdue.map { it.todo.title },
            todosDone = todos.count { it.done },
            habitsOpen = habits.filter { !it.done }.map { it.habit.name },
            habitsDone = habits.count { it.done },
            logged = c.money.countOn(today).first() > 0 || c.money.noSpendClaims(today).first() > 0,
            angryToday = CatNotifier.angryToday(context, today),
        )
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            val settings = ctx.container.settings.now()
            if (settings.onboarded && settings.reminderOn) {
                val today = LocalDate.now()
                NudgeEngine.evening(inputs(ctx, today), CatLines.evening())?.let { n ->
                    val open = if (n.tone == CatTone.HAPPY) null else OPEN_CALENDAR
                    val shown = CatNotifier.show(ctx, NOTIFICATION_ID, n, CatNotifier.openIntent(ctx, NOTIFICATION_ID, open))
                    if (shown && n.tone == CatTone.ANGRY) CatNotifier.markAngry(ctx, today)
                }
            }
            schedule(ctx, settings.reminderMinute, settings.reminderOn)
            return Result.success()
        }
    }
}
