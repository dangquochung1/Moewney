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
import app.meowney.feature.room.RoomAssets
import app.meowney.feature.room.RoomScreen
import app.meowney.feature.room.RoomState
import app.meowney.feature.uikit.UiKitScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the screens need from assets/, loaded once. */
class GameAssets(val sprites: SpriteAtlas, val room: RoomAssets, val pixelTypeface: Typeface)

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
                    room = RoomAssets.load(this@MainActivity.assets),
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
                    else -> RoomScreen(
                        room = game.room,
                        pixelTypeface = game.pixelTypeface,
                        state = RoomState(leftToday = 152_000, habitsDone = 3, habitsTotal = 5, coins = 120),
                        onHotspot = {},
                        onOpenUiKit = { screen = "uikit" },
                    )
                }
            }
        }
    }
}
