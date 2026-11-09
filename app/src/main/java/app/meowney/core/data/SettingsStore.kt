package app.meowney.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.meowney.core.habit.Phase
import app.meowney.core.habit.PomodoroConfig
import app.meowney.core.habit.PomodoroState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val onboarded: Boolean = false,
    val catName: String = "Mít",
    val catColor: Int = 5,
    /** 0 = calendar month, 1..28 = payday of the month. */
    val payday: Int = 0,
    val reminderOn: Boolean = true,
    /** Minutes after midnight, default 21:00. */
    val reminderMinute: Int = 21 * 60,
    val soundOn: Boolean = true,
    /** Exact reminders for events and to-dos. */
    val calendarRemindOn: Boolean = true,
    /** Epoch days; null = never. Read-only here, written by the mark* functions. */
    val lastBackupDay: Long? = null,
    val lastBackupNudgeDay: Long? = null,
    val lastIntroDay: Long? = null,
)

val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val catName = stringPreferencesKey("cat_name")
        val catColor = intPreferencesKey("cat_color")
        val payday = intPreferencesKey("payday")
        val reminderOn = booleanPreferencesKey("reminder_on")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val soundOn = booleanPreferencesKey("sound_on")
        val calendarRemindOn = booleanPreferencesKey("calendar_remind_on")
        val lastBackupDay = longPreferencesKey("last_backup_day")
        val lastBackupNudgeDay = longPreferencesKey("last_backup_nudge_day")
        val lastIntroDay = longPreferencesKey("last_intro_day")
        val pomoFocus = intPreferencesKey("pomo_focus")
        val pomoShort = intPreferencesKey("pomo_short")
        val pomoLong = intPreferencesKey("pomo_long")
        val pomoEvery = intPreferencesKey("pomo_every")
        val pomoPhase = stringPreferencesKey("pomo_phase")
        val pomoStart = longPreferencesKey("pomo_start")
        val pomoDone = intPreferencesKey("pomo_done")
        val pomoHabit = longPreferencesKey("pomo_habit")
        val pomoPaused = longPreferencesKey("pomo_paused")
    }

    val flow: Flow<Settings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun now(): Settings = flow.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.settingsDataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[K.onboarded] = s.onboarded
            prefs[K.catName] = s.catName
            prefs[K.catColor] = s.catColor
            prefs[K.payday] = s.payday
            prefs[K.reminderOn] = s.reminderOn
            prefs[K.reminderMinute] = s.reminderMinute
            prefs[K.soundOn] = s.soundOn
            prefs[K.calendarRemindOn] = s.calendarRemindOn
        }
    }

    suspend fun markBackup(day: Long) {
        context.settingsDataStore.edit { it[K.lastBackupDay] = day }
    }

    suspend fun markBackupNudge(day: Long) {
        context.settingsDataStore.edit { it[K.lastBackupNudgeDay] = day }
    }

    // ------------------------------------------------------------------ Pomodoro

    /** The config and the running timer (null when none). */
    val pomodoro: Flow<Pair<PomodoroConfig, PomodoroState?>> = context.settingsDataStore.data.map { p ->
        val cfg = PomodoroConfig(p[K.pomoFocus] ?: 45, p[K.pomoShort] ?: 10, p[K.pomoLong] ?: 20, p[K.pomoEvery] ?: 4)
        val state = p[K.pomoPhase]?.let { phase ->
            PomodoroState(Phase.valueOf(phase), p[K.pomoStart] ?: 0L, p[K.pomoDone] ?: 0, p[K.pomoHabit]?.takeIf { it > 0 }, p[K.pomoPaused])
        }
        cfg to state
    }

    suspend fun pomodoroNow() = pomodoro.first()

    suspend fun savePomodoroConfig(cfg: PomodoroConfig) {
        context.settingsDataStore.edit {
            it[K.pomoFocus] = cfg.focus
            it[K.pomoShort] = cfg.shortBreak
            it[K.pomoLong] = cfg.longBreak
            it[K.pomoEvery] = cfg.longEvery
        }
    }

    suspend fun savePomodoroState(state: PomodoroState?) {
        context.settingsDataStore.edit {
            if (state == null) {
                listOf(K.pomoPhase).forEach { k -> it.remove(k) }
                it.remove(K.pomoStart)
                it.remove(K.pomoDone)
                it.remove(K.pomoHabit)
                it.remove(K.pomoPaused)
            } else {
                it[K.pomoPhase] = state.phase.name
                it[K.pomoStart] = state.phaseStart
                it[K.pomoDone] = state.focusDone
                if (state.habitId != null) it[K.pomoHabit] = state.habitId else it.remove(K.pomoHabit)
                if (state.pausedAt != null) it[K.pomoPaused] = state.pausedAt else it.remove(K.pomoPaused)
            }
        }
    }

    suspend fun markIntro(day: Long) {
        context.settingsDataStore.edit { it[K.lastIntroDay] = day }
    }

    private fun Preferences.toSettings() = Settings(
        onboarded = this[K.onboarded] ?: false,
        catName = this[K.catName] ?: "Mít",
        catColor = this[K.catColor] ?: 5,
        payday = this[K.payday] ?: 0,
        reminderOn = this[K.reminderOn] ?: true,
        reminderMinute = this[K.reminderMinute] ?: (21 * 60),
        soundOn = this[K.soundOn] ?: true,
        calendarRemindOn = this[K.calendarRemindOn] ?: true,
        lastBackupDay = this[K.lastBackupDay],
        lastBackupNudgeDay = this[K.lastBackupNudgeDay],
        lastIntroDay = this[K.lastIntroDay],
    )
}
