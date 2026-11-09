package app.meowney

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.res.ResourcesCompat
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.AppEvents
import app.meowney.core.data.MoneyRepository
import app.meowney.core.data.Reports
import app.meowney.core.data.HabitRepository
import app.meowney.core.data.SettingsStore
import app.meowney.core.db.MeowneyDb
import app.meowney.core.sound.Sfx
import app.meowney.widget.updateWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Manual dependency injection: one of each, created on first use. */
class AppContainer(val context: Context) {
    val db: MeowneyDb by lazy { MeowneyDb.open(context) }
    val money: MoneyRepository by lazy { MoneyRepository(db) }
    val reports: Reports by lazy { Reports(db) }
    val habits: HabitRepository by lazy { HabitRepository(db, money) }
    val settings = SettingsStore(context)
    val events = AppEvents()
    val sfx: Sfx by lazy { Sfx(context) }

    // Art shared by the app and the home-screen widgets (which may run without the activity).
    val sprites: SpriteAtlas by lazy { SpriteAtlas.load(context.assets) }
    val icons: ImageBitmap by lazy { SpriteAtlas.loadBitmap(context.assets, "icons/raven16.png") }
    val typeface: Typeface by lazy { ResourcesCompat.getFont(context, R.font.meowney_pixel_regular)!! }
}

class MeowneyApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Lives as long as the process: keeps the home-screen widgets in step with the ledger. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            val m = container.money
            combine(m.jarStates, m.recurring, m.templates, container.settings.flow) { a, b, c, d -> listOf(a, b, c, d) }
                .drop(1) // the first value is what the widget already shows
                .debounce(800)
                .collect { runCatching { updateWidgets(this@MeowneyApp) } }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as MeowneyApp).container
