package app.meowney

import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.res.ResourcesCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.meowney.core.data.TxnDraft
import app.meowney.core.notify.EveningReminder
import app.meowney.core.sprite.SpriteAtlas
import app.meowney.core.ui.LocalIcons
import app.meowney.core.ui.MeowneyTheme
import app.meowney.core.ui.vmFactory
import app.meowney.feature.entry.BorrowDialog
import app.meowney.feature.entry.EntryMode
import app.meowney.feature.entry.EntryScreen
import app.meowney.feature.entry.EntryViewModel
import app.meowney.feature.entry.PourScreen
import app.meowney.feature.entry.PourViewModel
import app.meowney.feature.money.JarDetailScreen
import app.meowney.feature.money.JarDetailViewModel
import app.meowney.feature.money.MoneyScreen
import app.meowney.feature.money.MoneyViewModel
import app.meowney.feature.money.WalletsScreen
import app.meowney.feature.money.WalletsViewModel
import app.meowney.feature.onboarding.OnboardingScreen
import app.meowney.feature.onboarding.OnboardingViewModel
import app.meowney.feature.room.HomeActions
import app.meowney.feature.room.HomeScreen
import app.meowney.feature.room.HomeViewModel
import app.meowney.feature.room.RoomAssets
import app.meowney.feature.settings.CreditsScreen
import app.meowney.feature.settings.SettingsScreen
import app.meowney.feature.settings.SettingsViewModel
import app.meowney.feature.settings.XiaomiGuideScreen
import app.meowney.feature.uikit.UiKitScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Everything the screens need from assets/, loaded once. */
class GameAssets(
    val sprites: SpriteAtlas,
    val corner: RoomAssets,
    val icons: ImageBitmap,
    val pixelTypeface: Typeface,
    val onboarded: Boolean,
)

@Serializable object HomeRoute
@Serializable object OnboardingRoute
@Serializable data class EntryRoute(val mode: String = "EXPENSE", val txnId: Long = -1)
@Serializable data class PourRoute(val amount: Long, val walletId: Long, val categoryId: Long, val note: String, val day: Long, val editing: Long = -1)
@Serializable object MoneyRoute
@Serializable data class JarRoute(val id: Long)
@Serializable object WalletsRoute
@Serializable object SettingsRoute
@Serializable object XiaomiRoute
@Serializable object CreditsRoute
@Serializable object UiKitRoute

class MainActivity : ComponentActivity() {
    private var loaded by mutableStateOf<GameAssets?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { loaded == null }
        val c = container

        lifecycleScope.launch {
            loaded = withContext(Dispatchers.IO) {
                val settings = c.settings.now()
                c.sfx.enabled = settings.soundOn
                EveningReminder.createChannel(this@MainActivity)
                EveningReminder.schedule(this@MainActivity, settings.reminderMinute, settings.reminderOn)
                GameAssets(
                    sprites = SpriteAtlas.load(assets),
                    corner = RoomAssets.load(assets),
                    icons = SpriteAtlas.loadBitmap(assets, "icons/raven16.png"),
                    pixelTypeface = ResourcesCompat.getFont(this@MainActivity, R.font.meowney_pixel_regular)!!,
                    onboarded = settings.onboarded,
                )
            }
        }

        setContent {
            val game = loaded ?: return@setContent
            MeowneyTheme(game.sprites) {
                CompositionLocalProvider(LocalIcons provides game.icons) {
                    App(game, c)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun App(game: GameAssets, c: AppContainer) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = if (game.onboarded) HomeRoute else OnboardingRoute) {
        composable<OnboardingRoute> {
            val vm: OnboardingViewModel = viewModel(factory = vmFactory { OnboardingViewModel(c) })
            OnboardingScreen(vm, game.pixelTypeface, onFinished = {
                nav.navigate(HomeRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
            })
        }
        composable<HomeRoute> {
            val vm: HomeViewModel = viewModel(factory = vmFactory { HomeViewModel(c) })
            val state by vm.state.collectAsState()
            val event by c.events.pending.collectAsState()
            val pending by vm.pending.collectAsState()
            if (state.ready) {
                HomeScreen(
                    corner = game.corner,
                    pixelTypeface = game.pixelTypeface,
                    state = state,
                    catEvent = event,
                    onCatEventHandled = c.events::consume,
                    actions = HomeActions(
                        onSettings = { nav.navigate(SettingsRoute) },
                        onMoney = { nav.navigate(MoneyRoute) },
                        onAdd = { nav.navigate(EntryRoute()) },
                        onQuick = vm::quickLog,
                        onPin = vm::togglePin,
                        onNoSpend = vm::claimNoSpend,
                    ),
                )
            }
            pending?.let { BorrowDialog(it, state.jarNames, onConfirm = vm::confirmBorrow, onDismiss = vm::dismissBorrow) }
        }
        composable<EntryRoute> { entry ->
            val r = entry.toRoute<EntryRoute>()
            val vm: EntryViewModel = viewModel(factory = vmFactory {
                EntryViewModel(c, EntryMode.valueOf(r.mode), r.txnId.takeIf { it >= 0 })
            })
            EntryScreen(vm, onBack = back, onDone = back, onPour = { p ->
                nav.navigate(
                    PourRoute(p.draft.amount, p.draft.walletId, p.draft.categoryId ?: -1, p.draft.note, p.draft.date.toEpochDay(), p.editing ?: -1),
                ) { popUpTo<EntryRoute> { inclusive = true } }
            })
        }
        composable<PourRoute> { entry ->
            val r = entry.toRoute<PourRoute>()
            val vm: PourViewModel = viewModel(factory = vmFactory {
                PourViewModel(
                    c,
                    TxnDraft(r.amount, r.walletId, categoryId = r.categoryId.takeIf { it >= 0 }, note = r.note, date = LocalDate.ofEpochDay(r.day)),
                    r.editing.takeIf { it >= 0 },
                )
            })
            PourScreen(vm, game.pixelTypeface, onBack = back, onDone = back)
        }
        composable<MoneyRoute> {
            val vm: MoneyViewModel = viewModel(factory = vmFactory { MoneyViewModel(c) })
            MoneyScreen(
                vm, game.pixelTypeface, onBack = back,
                onJar = { nav.navigate(JarRoute(it)) },
                onWallets = { nav.navigate(WalletsRoute) },
                onTxn = { nav.navigate(EntryRoute(txnId = it)) },
                onAdd = { nav.navigate(EntryRoute()) },
            )
        }
        composable<JarRoute> { entry ->
            val id = entry.toRoute<JarRoute>().id
            val vm: JarDetailViewModel = viewModel(factory = vmFactory { JarDetailViewModel(c, id) })
            JarDetailScreen(vm, onBack = back)
        }
        composable<WalletsRoute> {
            val vm: WalletsViewModel = viewModel(factory = vmFactory { WalletsViewModel(c) })
            WalletsScreen(vm, onBack = back)
        }
        composable<SettingsRoute> {
            val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(c, ctx) })
            SettingsScreen(
                vm, onBack = back,
                onXiaomi = { nav.navigate(XiaomiRoute) },
                onCredits = { nav.navigate(CreditsRoute) },
                onUiKit = { nav.navigate(UiKitRoute) },
            )
        }
        composable<XiaomiRoute> { XiaomiGuideScreen(onBack = back) }
        composable<CreditsRoute> { CreditsScreen(onBack = back) }
        composable<UiKitRoute> { UiKitScreen(onBack = back) }
    }
}
