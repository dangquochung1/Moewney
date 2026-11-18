package app.meowney.core.notify

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.meowney.MainActivity
import app.meowney.container
import app.meowney.core.data.HabitToday
import app.meowney.core.data.kind
import app.meowney.core.habit.HabitType
import app.meowney.widget.EXTRA_HABIT
import app.meowney.widget.EXTRA_OPEN
import app.meowney.widget.OPEN_HABITS
import app.meowney.widget.OPEN_POMODORO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Per-habit reminders. One unique work waits for the next reminder minute of any habit,
 * notifies the habits of that minute that are still open, then waits for the next one.
 * Notifications carry "Xong" / "+1" so a habit can be ticked without opening the app.
 */
object HabitReminder {
    private const val WORK = "habit_reminder"
    private const val CLOSE_WORK = "habit_close"
    private const val KEY_MINUTE = "minute"
    const val ACTION_DONE = "app.meowney.HABIT_DONE"
    const val ACTION_PLUS = "app.meowney.HABIT_PLUS"
    const val EXTRA_ID = "habit_id"

    fun notificationId(habitId: Long) = 1_000 + habitId.toInt()

    /** Waits for the next reminder minute of any active habit (today or later). */
    suspend fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val habits = context.container.habits.habitsNow().filter { it.reminderMinute != null }
        if (habits.isEmpty()) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val now = LocalDateTime.now()
        val nowMinute = now.hour * 60 + now.minute
        val minutes = habits.mapNotNull { it.reminderMinute }.distinct().sorted()
        val nextMinute = minutes.firstOrNull { it > nowMinute } ?: minutes.first()
        var next = now.toLocalDate().atStartOfDay().plusMinutes(nextMinute.toLong())
        if (!next.isAfter(now)) next = next.plusDays(1)
        wm.enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Worker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_MINUTE to nextMinute))
                .build(),
        )
    }

    /** 00:05 every day: close yesterday's habits even if the app is not opened. */
    fun scheduleClose(context: Context) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atStartOfDay().plusMinutes(5)
        if (!next.isAfter(now)) next = next.plusDays(1)
        WorkManager.getInstance(context).enqueueUniqueWork(
            CLOSE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CloseWorker>().setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS).build(),
        )
    }

    suspend fun notify(context: Context, h: HabitToday) {
        val habit = h.habit
        val id = notificationId(habit.id)
        val text = when (habit.kind) {
            HabitType.CHECK -> "Đến giờ \"${habit.name}\" rồi Sen ơi. Xong thì bấm Xong nha."
            HabitType.COUNT -> "\"${habit.name}\": mới ${h.value}/${habit.target} ${habit.unit}. Thêm một nha!".replace("  ", " ")
            HabitType.TIMER -> "\"${habit.name}\": ${habit.target} phút thôi, trẫm canh giờ cho."
            HabitType.QUIT -> "Nhớ giữ \"${habit.name}\" nha Sen, trẫm tin Sen."
        }
        CatNotifier.show(context, id, Nudge(CatTone.HABIT, text), CatNotifier.openIntent(context, id, OPEN_HABITS)) {
            fun action(label: String, actionName: String) {
                val pi = PendingIntent.getBroadcast(
                    context, id * 10 + actionName.length,
                    Intent(context, ActionReceiver::class.java).setAction(actionName).putExtra(EXTRA_ID, habit.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                addAction(0, label, pi)
            }
            when (habit.kind) {
                HabitType.CHECK -> action("Xong", ACTION_DONE)
                HabitType.COUNT -> action("+1", ACTION_PLUS)
                HabitType.TIMER -> addAction(
                    0, "Bắt đầu",
                    PendingIntent.getActivity(
                        context, id * 10 + 7,
                        Intent(context, MainActivity::class.java)
                            .putExtra(EXTRA_OPEN, OPEN_POMODORO).putExtra(EXTRA_HABIT, habit.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                HabitType.QUIT -> Unit
            }
        }
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = applicationContext.container
            val minute = inputData.getInt(KEY_MINUTE, -1)
            if (c.settings.now().onboarded && minute >= 0) {
                for (h in c.habits.todayNow(LocalDate.now())) {
                    if (h.habit.reminderMinute == minute && !(h.done && h.habit.kind != HabitType.QUIT) && !h.failed) notify(applicationContext, h)
                }
            }
            schedule(applicationContext)
            return Result.success()
        }
    }

    class CloseWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            applicationContext.container.habits.closeThrough(LocalDate.now().minusDays(1))
            // the notebook bin empties itself after 30 days
            runCatching { applicationContext.container.notes.purge() }
            scheduleClose(applicationContext)
            return Result.success()
        }
    }

    /** "Xong" / "+1" on a reminder. */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(EXTRA_ID, -1)
            if (id < 0) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val c = context.container
                    when (intent.action) {
                        ACTION_DONE -> {
                            c.habits.complete(id)
                            NotificationManagerCompat.from(context).cancel(notificationId(id))
                        }
                        ACTION_PLUS -> {
                            c.habits.count(id)
                            val now = c.habits.todayNow(LocalDate.now()).firstOrNull { it.habit.id == id }
                            if (now == null || now.done) NotificationManagerCompat.from(context).cancel(notificationId(id)) else notify(context, now)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
