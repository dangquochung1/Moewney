package app.meowney.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.meowney.MainActivity
import app.meowney.R
import app.meowney.container
import app.meowney.core.data.HabitToday
import app.meowney.core.data.kind
import app.meowney.core.habit.HabitType
import app.meowney.widget.EXTRA_HABIT
import app.meowney.widget.EXTRA_OPEN
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
    private const val CHANNEL = "habits"
    private const val KEY_MINUTE = "minute"
    const val ACTION_DONE = "app.meowney.HABIT_DONE"
    const val ACTION_PLUS = "app.meowney.HABIT_PLUS"
    const val EXTRA_ID = "habit_id"

    fun notificationId(habitId: Long) = 1_000 + habitId.toInt()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Nhắc thói quen", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Giờ nhắc riêng của từng thói quen"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

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

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(context: Context, h: HabitToday) {
        if (!allowed(context)) return
        val habit = h.habit
        val id = notificationId(habit.id)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (habit.kind) {
            HabitType.CHECK -> "Đến giờ rồi Sen ơi. Xong thì bấm Xong nha."
            HabitType.COUNT -> "Mới ${h.value}/${habit.target} ${habit.unit}. Thêm một nha!".replace("  ", " ")
            HabitType.TIMER -> "${habit.target} phút thôi, trẫm canh giờ cho."
            HabitType.QUIT -> "Nhớ giữ nha Sen, trẫm tin Sen."
        }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(habit.name)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        fun action(label: String, actionName: String) {
            val pi = PendingIntent.getBroadcast(
                context, id * 10 + actionName.length,
                Intent(context, ActionReceiver::class.java).setAction(actionName).putExtra(EXTRA_ID, habit.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, label, pi)
        }
        when (habit.kind) {
            HabitType.CHECK -> action("Xong", ACTION_DONE)
            HabitType.COUNT -> action("+1", ACTION_PLUS)
            HabitType.TIMER -> b.addAction(
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
        NotificationManagerCompat.from(context).notify(id, b.build())
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
