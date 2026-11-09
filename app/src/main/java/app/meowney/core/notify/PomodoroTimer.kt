package app.meowney.core.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.meowney.MainActivity
import app.meowney.R
import app.meowney.container
import app.meowney.core.CatEvent
import app.meowney.core.db.CoinEntryEntity
import app.meowney.core.db.FocusSessionEntity
import app.meowney.core.game.RewardRules
import app.meowney.core.habit.Phase
import app.meowney.core.habit.PomodoroConfig
import app.meowney.core.habit.PomodoroEngine
import app.meowney.core.habit.PomodoroState
import app.meowney.widget.EXTRA_HABIT
import app.meowney.widget.EXTRA_OPEN
import app.meowney.widget.OPEN_POMODORO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/**
 * Pomodoro that keeps running with the app closed, without a service: the state is a few
 * timestamps in DataStore, an exact alarm fires at the end of each phase, and an ongoing
 * notification counts down by itself (chronometer).
 */
object PomodoroTimer {
    private const val CHANNEL_RUN = "pomodoro"
    private const val CHANNEL_BELL = "pomodoro_bell"
    private const val ONGOING_ID = 45
    private const val BELL_ID = 46
    private val lock = Mutex()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Pomodoro đang chạy", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Đồng hồ đếm ngược khi đang tập trung hoặc nghỉ"
                setShowBadge(false)
            },
        )
        val bell = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.raw.pomodoro_bell}")
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BELL, "Pomodoro hết giờ", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Chuông khi hết giờ tập trung / nghỉ"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(bell, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            },
        )
    }

    // ------------------------------------------------------------------ controls

    suspend fun start(context: Context, habitId: Long?) = lock.withLock {
        val c = context.container
        val (cfg, old) = c.settings.pomodoroNow()
        val state = PomodoroEngine.start(habitId ?: old?.habitId, System.currentTimeMillis(), old?.focusDone ?: 0)
        apply(context, cfg, state)
    }

    suspend fun pause(context: Context) = lock.withLock {
        val (cfg, s) = context.container.settings.pomodoroNow()
        if (s != null) apply(context, cfg, PomodoroEngine.pause(s, System.currentTimeMillis()))
    }

    suspend fun resume(context: Context) = lock.withLock {
        val (cfg, s) = context.container.settings.pomodoroNow()
        if (s != null) apply(context, cfg, PomodoroEngine.resume(s, System.currentTimeMillis()))
    }

    /** Ends a break early: the next focus waits. */
    suspend fun skipBreak(context: Context) = lock.withLock {
        val (cfg, s) = context.container.settings.pomodoroNow()
        if (s != null && s.phase != Phase.FOCUS) apply(context, cfg, s.copy(phase = Phase.READY, phaseStart = System.currentTimeMillis(), pausedAt = null))
    }

    /** Stops everything. Focus time already spent still counts for the habit (no coins). */
    suspend fun stop(context: Context) = lock.withLock {
        val c = context.container
        val (cfg, s) = c.settings.pomodoroNow()
        if (s != null && s.phase == Phase.FOCUS) {
            val spent = ((PomodoroEngine.lengthMs(cfg, Phase.FOCUS) - PomodoroEngine.remainingMs(cfg, s, System.currentTimeMillis())) / 60_000).toInt()
            if (spent >= 1) record(context, s.habitId, s.phaseStart, spent, completed = false)
        }
        cancelAlarm(context)
        NotificationManagerCompat.from(context).cancel(ONGOING_ID)
        c.settings.savePomodoroState(null)
    }

    suspend fun saveConfig(context: Context, cfg: PomodoroConfig) = context.container.settings.savePomodoroConfig(cfg)

    /** Phases that ended while nobody was looking (alarm, or the app opening late). */
    suspend fun catchUp(context: Context) = lock.withLock {
        val c = context.container
        val (cfg, s) = c.settings.pomodoroNow()
        if (s == null) return@withLock
        val now = System.currentTimeMillis()
        val adv = PomodoroEngine.advance(cfg, s, now)
        if (adv.focusEnded.isEmpty() && adv.state == s) return@withLock
        var coins = 0
        for (start in adv.focusEnded) coins += record(context, s.habitId, start, cfg.focus, completed = true)
        apply(context, cfg, adv.state)
        val text = when (adv.state.phase) {
            Phase.SHORT_BREAK, Phase.LONG_BREAK -> "Xong phiên tập trung! Nghỉ ${if (adv.state.phase == Phase.LONG_BREAK) cfg.longBreak else cfg.shortBreak} phút nha. +$coins xu"
            else -> "Hết giờ nghỉ rồi. Sẵn sàng phiên tiếp chưa Sen?"
        }
        bell(context, text)
        if (coins > 0) c.events.post(CatEvent.Coins(coins, text))
    }

    // ------------------------------------------------------------------ internals

    /** Saves the state, then sets the alarm and the countdown to match it. */
    private suspend fun apply(context: Context, cfg: PomodoroConfig, s: PomodoroState) {
        context.container.settings.savePomodoroState(s)
        if (s.phase == Phase.READY || s.pausedAt != null) {
            cancelAlarm(context)
        } else {
            setAlarm(context, PomodoroEngine.endsAt(cfg, s))
        }
        ongoing(context, cfg, s)
    }

    /** One focus block: the session row, coins when finished, minutes for its habit. */
    private suspend fun record(context: Context, habitId: Long?, start: Long, minutes: Int, completed: Boolean): Int {
        val c = context.container
        val day = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()
        val db = c.db
        val id = db.habits().insertFocus(FocusSessionEntity(habitId = habitId, startedAt = start, focusMinutes = minutes, completed = completed, day = day.toEpochDay()))
        var coins = 0
        if (completed) {
            db.money().insertCoins(listOf(CoinEntryEntity(delta = RewardRules.FOCUS_SESSION, reason = RewardRules.R_FOCUS, refId = id, day = day.toEpochDay())))
            coins += RewardRules.FOCUS_SESSION
        }
        if (habitId != null) coins += c.habits.addMinutes(habitId, minutes, day).coins
        return coins
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, 45, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun setAlarm(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(context)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openIntent(context: Context, habitId: Long?) = PendingIntent.getActivity(
        context, 44,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, OPEN_POMODORO).putExtra(EXTRA_HABIT, habitId ?: -1L)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun phaseLabel(p: Phase) = when (p) {
        Phase.FOCUS -> "Tập trung"
        Phase.SHORT_BREAK -> "Nghỉ ngắn"
        Phase.LONG_BREAK -> "Nghỉ dài"
        Phase.READY -> "Sẵn sàng"
    }

    private fun ongoing(context: Context, cfg: PomodoroConfig, s: PomodoroState) {
        val nm = NotificationManagerCompat.from(context)
        if (!allowed(context) || s.phase == Phase.READY) {
            nm.cancel(ONGOING_ID)
            return
        }
        val b = NotificationCompat.Builder(context, CHANNEL_RUN)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Pomodoro · ${phaseLabel(s.phase)}")
            .setContentIntent(openIntent(context, s.habitId))
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
        if (s.pausedAt != null) {
            val left = PomodoroEngine.remainingMs(cfg, s, System.currentTimeMillis()) / 1000
            b.setContentText("Tạm dừng · còn %d:%02d".format(left / 60, left % 60))
        } else {
            b.setContentText(if (s.phase == Phase.FOCUS) "Trẫm canh giờ, Sen cứ tập trung." else "Duỗi lưng, uống nước đi Sen.")
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(PomodoroEngine.endsAt(cfg, s))
                .setShowWhen(true)
        }
        nm.notify(ONGOING_ID, b.build())
    }

    private fun bell(context: Context, text: String) {
        if (!allowed(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_BELL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Meowney · Pomodoro")
            .setContentText(text)
            .setContentIntent(openIntent(context, null))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(BELL_ID, n)
    }

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    catchUp(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
