package app.meowney

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import app.meowney.core.data.TxnDraft
import app.meowney.core.notify.EveningReminder
import app.meowney.core.notify.MorningReminder
import app.meowney.core.notify.HabitReminder
import app.meowney.core.notify.PomodoroTimer
import app.meowney.feature.habit.PomodoroScreen
import app.meowney.feature.habit.PomodoroViewModel
import app.meowney.widget.OPEN_POMODORO
import app.meowney.widget.EXTRA_HABIT
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
import app.meowney.feature.money.DebtsScreen
import app.meowney.feature.money.DebtsViewModel
import app.meowney.feature.money.GoalScreen
import app.meowney.feature.money.GoalViewModel
import app.meowney.feature.money.JarDetailScreen
import app.meowney.feature.money.JarDetailViewModel
import app.meowney.feature.money.MoneyScreen
import app.meowney.feature.money.RecurringScreen
import app.meowney.feature.money.RecurringViewModel
import app.meowney.feature.money.ReportScreen
import app.meowney.feature.habit.HabitDetailScreen
import app.meowney.feature.habit.HabitDetailViewModel
import app.meowney.feature.habit.HabitEditScreen
import app.meowney.feature.habit.HabitEditViewModel
import app.meowney.feature.habit.HabitsScreen
import app.meowney.feature.habit.HabitsViewModel
import app.meowney.feature.money.ReportViewModel
import app.meowney.core.data.code
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
import app.meowney.widget.EXTRA_OPEN
import app.meowney.core.data.Settings
import app.meowney.core.data.todaySnapshot
import app.meowney.core.format.formatVnd
import app.meowney.core.ui.Zoom
import app.meowney.feature.room.WakeIntro
import app.meowney.feature.room.wakeGreeting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.time.LocalTime
import app.meowney.widget.OPEN_ENTRY
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
    /** Null = no intro (onboarding shows instead). */
    val intro: IntroPlan?,
)

/** [full] = the first open of the day: the cat wakes up. */
class IntroPlan(val full: Boolean, val catColor: Int, val greeting: String?)

@Serializable object HomeRoute
@Serializable object OnboardingRoute
@Serializable data class EntryRoute(val mode: String = "EXPENSE", val txnId: Long = -1)
@Serializable data class PourRoute(val amount: Long, val walletId: Long, val categoryId: Long, val note: String, val day: Long, val editing: Long = -1)
@Serializable object MoneyRoute
@Serializable data class JarRoute(val id: Long)
@Serializable data class GoalRoute(val id: Long)
@Serializable object DebtsRoute
@Serializable object RecurringRoute
@Serializable data class ReportRoute(val yyyymm: Int = -1)
@Serializable object HabitsRoute
@Serializable data class HabitRoute(val id: Long)
@Serializable data class HabitEditRoute(val id: Long = -1)
@Serializable data class PomodoroRoute(val habitId: Long = -1)
@Serializable object WalletsRoute
@Serializable object SettingsRoute
@Serializable object XiaomiRoute
@Serializable object CreditsRoute
@Serializable object UiKitRoute

class MainActivity : ComponentActivity() {
    private var loaded by mutableStateOf<GameAssets?>(null)

    /** A screen asked for from outside (the widget "+"), consumed once shown. */
    private var openRequest by mutableStateOf<String?>(null)
    private var openHabit = -1L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openHabit = intent.getLongExtra(EXTRA_HABIT, -1L)
        openRequest = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        splash.setKeepOnScreenCondition { loaded == null }
        val c = container
        if (savedInstanceState == null) {
            openHabit = intent.getLongExtra(EXTRA_HABIT, -1L)
            openRequest = intent.getStringExtra(EXTRA_OPEN)
        }

        lifecycleScope.launch {
            loaded = withContext(Dispatchers.IO) {
                val settings = c.settings.now()
                c.sfx.enabled = settings.soundOn
                EveningReminder.createChannel(this@MainActivity)
                EveningReminder.schedule(this@MainActivity, settings.reminderMinute, settings.reminderOn)
                MorningReminder.createChannel(this@MainActivity)
                MorningReminder.schedule(this@MainActivity)
                HabitReminder.createChannel(this@MainActivity)
                HabitReminder.schedule(this@MainActivity)
                HabitReminder.scheduleClose(this@MainActivity)
                PomodoroTimer.createChannels(this@MainActivity)
                PomodoroTimer.catchUp(this@MainActivity)
                GameAssets(
                    sprites = c.sprites,
                    corner = RoomAssets.load(assets),
                    icons = c.icons,
                    pixelTypeface = c.typeface,
                    onboarded = settings.onboarded,
                    intro = introPlan(c, settings, fromWidget = openRequest != null),
                )
            }
        }

        setContent {
            val game = loaded ?: return@setContent
            MeowneyTheme(game.sprites) {
                CompositionLocalProvider(LocalIcons provides game.icons) {
                    Box(Modifier.fillMaxSize()) {
                        App(game, c, openRequest, openHabit) { openRequest = null }
                        var intro by remember { mutableStateOf(game.intro) }
                        intro?.let { plan ->
                            WakeIntro(plan.full, plan.catColor, plan.greeting, onDone = { intro = null })
                        }
                    }
                }
            }
        }
    }
}

private suspend fun introPlan(c: AppContainer, settings: Settings, fromWidget: Boolean): IntroPlan? {
    if (!settings.onboarded) return null
    val today = LocalDate.now().toEpochDay()
    val full = !fromWidget && settings.lastIntroDay != today
    if (full) c.settings.markIntro(today)
    val left = if (full) runCatching { todaySnapshot(c).left }.getOrNull() else null
    val greeting = wakeGreeting(LocalTime.now().hour, settings.catName, left, ::formatVnd)
    return IntroPlan(full, settings.catColor, greeting)
}

@androidx.compose.runtime.Composable
private fun App(game: GameAssets, c: AppContainer, openRequest: String?, openHabit: Long, onOpened: () -> Unit) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    LaunchedEffect(openRequest) {
        if (openRequest == OPEN_ENTRY) {
            val onboarding = nav.currentBackStackEntry?.destination?.hasRoute(OnboardingRoute::class) ?: !game.onboarded
            if (!onboarding) nav.navigate(EntryRoute()) { launchSingleTop = true }
        }
        if (openRequest == OPEN_POMODORO && game.onboarded) nav.navigate(PomodoroRoute(openHabit)) { launchSingleTop = true }
        if (openRequest != null) onOpened()
    }
    // first open of a new month: the cat hands over last month's report card
    LaunchedEffect(Unit) {
        if (game.onboarded && openRequest == null) {
            val pending = withContext(Dispatchers.IO) { c.reports.pending(LocalDate.now()) }
            if (pending != null) nav.navigate(ReportRoute(pending.code())) { launchSingleTop = true }
        }
    }
    NavHost(
        nav,
        startDestination = if (game.onboarded) HomeRoute else OnboardingRoute,
        enterTransition = Zoom.enter,
        exitTransition = Zoom.exit,
        popEnterTransition = Zoom.popEnter,
        popExitTransition = Zoom.popExit,
    ) {
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
                        onPayBill = { bill, amount -> vm.payBill(bill, amount) },
                        onSkipBill = { vm.skipBill(it) },
                        onBackupNudged = vm::markBackupNudge,
                        onHabits = { nav.navigate(HabitsRoute) },
                        onHabitTap = vm::habitTap,
                        onHabitCount = vm::habitCount,
                        onHabitFailed = vm::habitFailed,
                        onTimer = { nav.navigate(PomodoroRoute(it.habit.id)) },
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
                onGoal = { nav.navigate(GoalRoute(it)) },
                onDebts = { nav.navigate(DebtsRoute) },
                onRecurring = { nav.navigate(RecurringRoute) },
                onReport = { nav.navigate(ReportRoute()) },
            )
        }
        composable<HabitsRoute> {
            val vm: HabitsViewModel = viewModel(factory = vmFactory { HabitsViewModel(c) })
            HabitsScreen(
                vm, onBack = back,
                onNew = { nav.navigate(HabitEditRoute()) },
                onOpen = { nav.navigate(HabitRoute(it)) },
                onPomodoro = { nav.navigate(PomodoroRoute()) },
            )
        }
        composable<PomodoroRoute> { entry ->
            val habitId = entry.toRoute<PomodoroRoute>().habitId
            val vm: PomodoroViewModel = viewModel(factory = vmFactory { PomodoroViewModel(c, habitId) })
            PomodoroScreen(vm, game.corner, onBack = back)
        }
        composable<HabitRoute> { entry ->
            val id = entry.toRoute<HabitRoute>().id
            val vm: HabitDetailViewModel = viewModel(factory = vmFactory { HabitDetailViewModel(c, id) })
            HabitDetailScreen(vm, onBack = back, onEdit = { nav.navigate(HabitEditRoute(id)) })
        }
        composable<HabitEditRoute> { entry ->
            val id = entry.toRoute<HabitEditRoute>().id
            val vm: HabitEditViewModel = viewModel(factory = vmFactory { HabitEditViewModel(c, id) })
            HabitEditScreen(vm, onBack = back, onDone = { nav.popBackStack() })
        }
        composable<ReportRoute> { entry ->
            val month = entry.toRoute<ReportRoute>().yyyymm
            val vm: ReportViewModel = viewModel(factory = vmFactory { ReportViewModel(c, month) })
            val settings by c.settings.flow.collectAsState(initial = null)
            ReportScreen(vm, catColor = settings?.catColor ?: 0, onBack = back)
        }
        composable<RecurringRoute> {
            val vm: RecurringViewModel = viewModel(factory = vmFactory { RecurringViewModel(c) })
            RecurringScreen(vm, onBack = back)
        }
        composable<DebtsRoute> {
            val vm: DebtsViewModel = viewModel(factory = vmFactory { DebtsViewModel(c) })
            DebtsScreen(vm, onBack = back)
        }
        composable<GoalRoute> { entry ->
            val id = entry.toRoute<GoalRoute>().id
            val vm: GoalViewModel = viewModel(factory = vmFactory { GoalViewModel(c, id) })
            GoalScreen(vm, onBack = back)
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
