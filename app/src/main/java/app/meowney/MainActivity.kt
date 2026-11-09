package app.meowney

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.res.ResourcesCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.ui.MeowneyTheme
import app.meowney.feature.room.HomeScreen
import app.meowney.feature.room.HomeState
import app.meowney.feature.room.QuickTemplate
import app.meowney.feature.room.RoomAssets
import app.meowney.feature.uikit.UiKitScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens need from assets/, loaded once. */
class GameAssets(val sprites: SpriteAtlas, val corner: RoomAssets, val pixelTypeface: Typeface)

/** Demo numbers until GĐ1 brings the database. */
private val DEMO = HomeState(
    catColor = 5,
    coins = 120,
    leftToday = 152_000,
    spentToday = 48_000,
    jarLevel = 0.6f,
    templates = listOf(
        QuickTemplate("Cà phê", 25_000),
        QuickTemplate("Gửi xe", 5_000),
        QuickTemplate("Ăn trưa", 40_000),
        QuickTemplate("Trà sữa", 35_000),
    ),
)

class MainActivity : ComponentActivity() {
    private var loaded by mutableStateOf<GameAssets?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { loaded == null }

        lifecycleScope.launch {
            loaded = withContext(Dispatchers.IO) {
                GameAssets(
                    sprites = SpriteAtlas.load(this@MainActivity.assets),
                    corner = RoomAssets.load(this@MainActivity.assets),
                    pixelTypeface = ResourcesCompat.getFont(this@MainActivity, R.font.meowney_pixel_regular)!!,
                )
            }
        }

        setContent {
            val game = loaded ?: return@setContent
            MeowneyTheme(game.sprites) {
                var screen by rememberSaveable { mutableStateOf("room") }
                when (screen) {
                    "uikit" -> {
                        BackHandler { screen = "room" }
                        UiKitScreen(onBack = { screen = "room" })
                    }
                    else -> HomeScreen(
                        corner = game.corner,
                        pixelTypeface = game.pixelTypeface,
                        initial = DEMO,
                        onOpenUiKit = { screen = "uikit" },
                    )
                }
            }
        }
    }
}
