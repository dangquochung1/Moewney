package app.meowney.core.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.meowney.container
import app.meowney.core.format.formatVndShort
import app.meowney.core.money.JarCode
import app.meowney.core.ui.hhmm
import app.meowney.widget.OPEN_CALENDAR
import app.meowney.widget.OPEN_MONEY
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * 08:00 every day, up to three messages from the cat: the day's agenda, money worries
 * (bills and debts due, jars below zero), and missing Sen after two days away.
 */
object MorningReminder {
    private const val WORK = "morning_reminder"
    const val MINUTE = 8 * 60

    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atStartOfDay().plusMinutes(MINUTE.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Worker>().setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build(),
        )
    }

    /** What the morning knows about [today]. */
    suspend fun inputs(context: Context, today: LocalDate = LocalDate.now()): MorningInputs {
        val c = context.container
        val settings = c.settings.now()
        val bills = c.money.recurringNow().filter { it.active && it.nextDueDay <= today.toEpochDay() }
        val debts = c.money.debtsDue(today)
        val agenda = if (settings.calendarRemindOn) {
            val day = c.calendar.days(today, today).first()[today]
            val labels = c.calendar.labels.first().associateBy { it.id }
            val events = day?.events.orEmpty().filter { it.event.remind && (it.occ.allDay || it.occ.multiDay) }
                .map { e -> if (labels[e.event.labelId]?.kind == "BIRTHDAY") "${e.event.title} hôm nay" else e.event.title }
            val todos = day?.todos.orEmpty().filter { !it.done && it.todo.remind }
                .map { t -> t.todo.title + (t.todo.dueMinute?.let { " (${hhmm(it)})" } ?: "") }
            events + todos
        } else {
            emptyList()
        }
        return MorningInputs(
            agenda = agenda,
            due = bills.map { "${it.name} ${formatVndShort(it.amount)}" } +
                debts.map { if (it.direction == "LENT") "${it.person} trả Sen ${formatVndShort(it.left)}" else "Trả ${it.person} ${formatVndShort(it.left)}" },
            negativeJars = c.money.jarStates.first().filter { it.code != JarCode.UNALLOC && it.balance < 0 }.map { it.jar.name },
            daysAway = settings.lastOpenDay?.let { (today.toEpochDay() - it).toInt() } ?: 0,
        )
    }

    private fun idFor(tone: CatTone) = when (tone) {
        CatTone.MORNING -> 8
        CatTone.WORRIED -> 9
        else -> 10
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            if (ctx.container.settings.now().onboarded) {
                for (n in NudgeEngine.morning(inputs(ctx))) {
                    val id = idFor(n.tone)
                    val open = when (n.tone) {
                        CatTone.MORNING -> OPEN_CALENDAR
                        CatTone.WORRIED -> OPEN_MONEY
                        else -> null
                    }
                    CatNotifier.show(ctx, id, n, CatNotifier.openIntent(ctx, id, open))
                }
            }
            schedule(ctx)
            return Result.success()
        }
    }
}
