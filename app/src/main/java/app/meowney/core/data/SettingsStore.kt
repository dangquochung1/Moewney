package app.meowney.core.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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
        val lastBackupDay = longPreferencesKey("last_backup_day")
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
        }
    }

    suspend fun markBackup(day: Long) {
        context.settingsDataStore.edit { it[K.lastBackupDay] = day }
    }

    private fun Preferences.toSettings() = Settings(
        onboarded = this[K.onboarded] ?: false,
        catName = this[K.catName] ?: "Mít",
        catColor = this[K.catColor] ?: 5,
        payday = this[K.payday] ?: 0,
        reminderOn = this[K.reminderOn] ?: true,
        reminderMinute = this[K.reminderMinute] ?: (21 * 60),
        soundOn = this[K.soundOn] ?: true,
    )
}
