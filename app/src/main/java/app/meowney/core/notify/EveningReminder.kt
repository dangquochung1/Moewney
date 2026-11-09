package app.meowney.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import app.meowney.MainActivity
import app.meowney.R
import app.meowney.container
import app.meowney.feature.room.CatLines
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * "Sen ơi, hôm nay chưa ghi sổ": one-off work scheduled for the next reminder time, which
 * schedules the following one when it runs. It only notifies when nothing was logged today.
 */
object EveningReminder {
    private const val WORK = "evening_reminder"
    private const val CHANNEL = "reminder"
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

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Nhắc ghi sổ", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Mèo nhắc ghi chi tiêu buổi tối"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notify(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Meowney")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val c = applicationContext.container
            val settings = c.settings.now()
            if (settings.onboarded && settings.reminderOn) {
                val today = LocalDate.now()
                val logged = c.money.countOn(today).first() > 0
                val claimed = c.money.noSpendClaims(today).first() > 0
                val open = c.habits.todayNow(today).filter { !it.done }.map { it.habit.name }
                val habits = if (open.isEmpty()) "" else "Còn ${open.size} thói quen chưa xong: ${open.joinToString(", ")}."
                when {
                    !logged && !claimed -> notify(applicationContext, listOf(CatLines.evening(), habits).filter { it.isNotEmpty() }.joinToString(" "))
                    habits.isNotEmpty() -> notify(applicationContext, "Sen ơi, $habits")
                }
            }
            schedule(applicationContext, settings.reminderMinute, settings.reminderOn)
            return Result.success()
        }
    }
}
