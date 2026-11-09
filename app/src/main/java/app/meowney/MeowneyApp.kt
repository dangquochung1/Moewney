package app.meowney

import android.app.Application
import android.content.Context
import app.meowney.core.AppEvents
import app.meowney.core.data.MoneyRepository
import app.meowney.core.data.SettingsStore
import app.meowney.core.db.MeowneyDb
import app.meowney.core.sound.Sfx

/** Manual dependency injection: one of each, created on first use. */
class AppContainer(private val context: Context) {
    val db: MeowneyDb by lazy { MeowneyDb.open(context) }
    val money: MoneyRepository by lazy { MoneyRepository(db) }
    val settings = SettingsStore(context)
    val events = AppEvents()
    val sfx: Sfx by lazy { Sfx(context) }
}

class MeowneyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as MeowneyApp).container
