package app.meowney.core.notify

import android.Manifest
import android.app.AlarmManager
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
import app.meowney.MainActivity
import app.meowney.R
import app.meowney.container
import app.meowney.core.calendar.CalendarEngine
import app.meowney.core.calendar.ReminderAt
import app.meowney.core.ui.hhmm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Exact reminders for timed events and to-dos. Only the next one is armed; when it rings,
 * everything due since the last ring is shown and the following one is armed.
 * (All-day events and to-dos without a time go in the 8:00 morning message.)
 */
object CalendarReminder {
    private const val CHANNEL = "calendar"
    private const val PREFS = "calendar_reminder"
    private const val KEY_LAST = "last_ms"
    const val ACTION_TODO_DONE = "app.meowney.TODO_DONE"
    const val EXTRA_TODO = "todo_id"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Nhắc lịch", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Sự kiện sắp bắt đầu và việc đến hạn"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 60, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Arms the alarm for the next reminder (or clears it). */
    suspend fun schedule(context: Context) {
        val c = context.container
        val am = context.getSystemService(AlarmManager::class.java)
        val settings = c.settings.now()
        if (!settings.onboarded || !settings.calendarRemindOn) {
            am.cancel(alarmIntent(context))
            return
        }
        val next = CalendarEngine.nextReminders(c.calendar.eventsNow(), c.calendar.todosNow(), LocalDateTime.now()).firstOrNull()
        if (next == null) {
            am.cancel(alarmIntent(context))
            return
        }
        val at = millis(next.at)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
        }
    }

    /** Shows the reminders due since the last ring (at most a day back). */
    private suspend fun ring(context: Context) {
        val c = context.container
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = LocalDateTime.now()
        val last = prefs.getLong(KEY_LAST, 0L).coerceAtLeast(millis(now.minusDays(1)))
        if (c.settings.now().calendarRemindOn) {
            val since = java.time.Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault()).toLocalDateTime()
            CalendarEngine.nextReminders(c.calendar.eventsNow(), c.calendar.todosNow(), since, days = 2)
                .filter { !it.at.isAfter(now.plusSeconds(30)) }
                .forEach { notify(context, it) }
        }
        prefs.edit().putLong(KEY_LAST, millis(now.plusSeconds(30))).apply()
        schedule(context)
    }

    private fun notify(context: Context, r: ReminderAt) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val id = if (r.todoId != null) 30_000 + r.todoId.toInt() else 20_000 + (r.eventId ?: 0).toInt() * 7 + r.minutesBefore % 7
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val start = r.at.plusMinutes(r.minutesBefore.toLong())
        val text = when {
            r.todoId != null -> "Đến hạn rồi Sen ơi (${hhmm(start.hour * 60 + start.minute)})."
            r.minutesBefore == 0 -> "Bắt đầu rồi (${hhmm(start.hour * 60 + start.minute)})."
            r.minutesBefore >= 1440 -> "Ngày mai lúc ${hhmm(start.hour * 60 + start.minute)}."
            r.minutesBefore >= 60 -> "Còn ${r.minutesBefore / 60} tiếng nữa (${hhmm(start.hour * 60 + start.minute)})."
            else -> "Còn ${r.minutesBefore} phút nữa (${hhmm(start.hour * 60 + start.minute)})."
        }
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(r.title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(if (r.todoId != null) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
        if (r.todoId != null) {
            b.addAction(
                0, "Xong",
                PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, AlarmReceiver::class.java).setAction(ACTION_TODO_DONE).putExtra(EXTRA_TODO, r.todoId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        NotificationManagerCompat.from(context).notify(id, b.build())
    }

    /** The alarm, the "Xong" button, and the phone restarting (alarms are lost on reboot). */
    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    when (intent.action) {
                        ACTION_TODO_DONE -> {
                            val id = intent.getLongExtra(EXTRA_TODO, -1)
                            val todo = context.container.calendar.todo(id)
                            if (todo != null && todo.doneAt == null) context.container.calendar.toggleTodo(id)
                            NotificationManagerCompat.from(context).cancel(30_000 + id.toInt())
                            schedule(context)
                        }
                        Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                            schedule(context)
                            PomodoroTimer.catchUp(context)
                        }
                        else -> ring(context)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
