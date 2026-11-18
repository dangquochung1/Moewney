package app.meowney.core.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import app.meowney.container
import app.meowney.core.calendar.CalendarEngine
import app.meowney.core.calendar.ReminderAt
import app.meowney.core.ui.hhmm
import app.meowney.widget.OPEN_CALENDAR
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
    private const val PREFS = "calendar_reminder"
    private const val KEY_LAST = "last_ms"
    const val ACTION_TODO_DONE = "app.meowney.TODO_DONE"
    const val EXTRA_TODO = "todo_id"

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

    private suspend fun notify(context: Context, r: ReminderAt) {
        val id = if (r.todoId != null) 30_000 + r.todoId.toInt() else 20_000 + (r.eventId ?: 0).toInt() * 7 + r.minutesBefore % 7
        val start = r.at.plusMinutes(r.minutesBefore.toLong())
        val at = hhmm(start.hour * 60 + start.minute)
        val what = if (r.todoId != null) "Việc" else "Sự kiện"
        val text = when {
            r.todoId != null -> "Việc \"${r.title}\" đến hạn rồi Sen ơi ($at)."
            r.minutesBefore == 0 -> "$what \"${r.title}\" bắt đầu rồi ($at)."
            r.minutesBefore >= 1440 -> "$what \"${r.title}\" vào ngày mai lúc $at."
            r.minutesBefore >= 60 -> "$what \"${r.title}\" lúc $at, còn ${r.minutesBefore / 60} tiếng nữa."
            else -> "$what \"${r.title}\" lúc $at, còn ${r.minutesBefore} phút nữa."
        }
        CatNotifier.show(context, id, Nudge(CatTone.REMIND, text), CatNotifier.openIntent(context, id, OPEN_CALENDAR)) {
            if (r.todoId != null) {
                addAction(
                    0, "Xong",
                    PendingIntent.getBroadcast(
                        context, id,
                        Intent(context, AlarmReceiver::class.java).setAction(ACTION_TODO_DONE).putExtra(EXTRA_TODO, r.todoId),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
        }
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
