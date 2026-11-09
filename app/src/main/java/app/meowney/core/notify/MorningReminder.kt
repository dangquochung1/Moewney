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
import app.meowney.core.format.formatVndShort
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** 08:00 every day: recurring bills and debts that are due today or overdue. */
object MorningReminder {
    private const val WORK = "morning_reminder"
    private const val CHANNEL = "due"
    private const val NOTIFICATION_ID = 8
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

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Đến hạn trả", NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = "Khoản định kỳ và khoản nợ đến hạn"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** The lines of the morning message, or empty when nothing is due. */
    suspend fun dueLines(context: Context, today: LocalDate = LocalDate.now()): List<String> {
        val c = context.container
        val bills = c.money.recurringNow().filter { it.nextDueDay <= today.toEpochDay() }
        val debts = c.money.debtsDue(today)
        return bills.map { "${it.name} ${formatVndShort(it.amount)}" } +
            debts.map { if (it.direction == "LENT") "${it.person} trả Sen ${formatVndShort(it.left)}" else "Trả ${it.person} ${formatVndShort(it.left)}" }
    }

    private fun notify(context: Context, lines: List<String>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = "Sen ơi, hôm nay đến hạn ${lines.size} khoản"
        val text = lines.joinToString(" · ")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
    }

    class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (applicationContext.container.settings.now().onboarded) {
                val lines = dueLines(applicationContext)
                if (lines.isNotEmpty()) notify(applicationContext, lines)
            }
            schedule(applicationContext)
            return Result.success()
        }
    }
}
