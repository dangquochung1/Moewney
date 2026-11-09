package app.meowney.feature.habit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.data.HabitToday
import app.meowney.core.data.kind
import app.meowney.core.db.HabitEntity
import app.meowney.core.habit.HabitType
import app.meowney.core.habit.Phase
import app.meowney.core.habit.PomodoroConfig
import app.meowney.core.habit.PomodoroEngine
import app.meowney.core.habit.PomodoroState
import app.meowney.core.notify.PomodoroTimer
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelButton
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.feature.room.Cat
import app.meowney.feature.room.CatStage
import app.meowney.feature.room.RoomAssets
import app.meowney.feature.room.stageScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PomodoroUi(
    val cfg: PomodoroConfig = PomodoroConfig(),
    val state: PomodoroState? = null,
    val now: Long = System.currentTimeMillis(),
    /** The habit the minutes go to (running one, or the one picked before starting). */
    val habit: HabitEntity? = null,
    val habitToday: HabitToday? = null,
    val timerHabits: List<HabitEntity> = emptyList(),
    val catColor: Int = 5,
) {
    val running get() = state != null && state.phase != Phase.READY
    val phase get() = state?.phase ?: Phase.READY
    val remainingMs get() = state?.let { if (it.phase == Phase.READY) PomodoroEngine.lengthMs(cfg, Phase.FOCUS) else PomodoroEngine.remainingMs(cfg, it, now) }
        ?: PomodoroEngine.lengthMs(cfg, Phase.FOCUS)
}

class PomodoroViewModel(private val c: AppContainer, habitId: Long) : ViewModel() {
    private val picked = MutableStateFlow(habitId.takeIf { it > 0 })
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val state: StateFlow<PomodoroUi> = combine(
        c.settings.pomodoro, clock, picked, c.habits.habits, combine(c.habits.today(LocalDate.now()), c.settings.flow) { a, b -> a to b },
    ) { (cfg, s), now, pick, habits, (today, settings) ->
        val id = s?.habitId ?: pick
        PomodoroUi(
            cfg, s, now,
            habit = habits.firstOrNull { it.id == id },
            habitToday = today.firstOrNull { it.habit.id == id },
            timerHabits = habits.filter { it.kind == HabitType.TIMER },
            catColor = settings.catColor,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PomodoroUi())

    init {
        viewModelScope.launch { PomodoroTimer.catchUp(c.context) }
        // a phase that ends while the screen is open (the alarm also handles it)
        viewModelScope.launch {
            state.collect { s ->
                if (s.running && s.state?.pausedAt == null && s.remainingMs <= 0) PomodoroTimer.catchUp(c.context)
            }
        }
    }

    fun pick(id: Long?) {
        picked.value = id
    }

    fun start() = viewModelScope.launch { PomodoroTimer.start(c.context, picked.value) }
    fun pause() = viewModelScope.launch { PomodoroTimer.pause(c.context) }
    fun resume() = viewModelScope.launch { PomodoroTimer.resume(c.context) }
    fun stop() = viewModelScope.launch { PomodoroTimer.stop(c.context) }
    fun skipBreak() = viewModelScope.launch { PomodoroTimer.skipBreak(c.context) }
    fun saveConfig(cfg: PomodoroConfig) = viewModelScope.launch { PomodoroTimer.saveConfig(c.context, cfg) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PomodoroScreen(vm: PomodoroViewModel, corner: RoomAssets, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var editing by remember { mutableStateOf(false) }
    val cat = remember(s.catColor) { Cat(s.catColor, corner.circle, corner.spots) }
    val paused = s.state?.pausedAt != null
    // focus: the cat curls up asleep; break: it plays with the yarn
    LaunchedEffect(s.phase, paused) {
        cat.forceSleep = s.phase == Phase.FOCUS && !paused
        if (s.phase == Phase.SHORT_BREAK || s.phase == Phase.LONG_BREAK) {
            while (true) {
                cat.play()
                delay(7_000)
            }
        }
    }

    PixelScreen("Pomodoro", onBack = onBack) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val scale = stageScale(constraints.maxWidth, (constraints.maxWidth * 1.6f).toInt(), corner.circle.diameter, heightShare = 0.36f, widthShare = 0.6f)
                CatStage(corner, cat, scale, onCatTap = { cat.poke() }, onCatLongPress = {})
            }
            PixelText(PomodoroTimer.phaseLabel(s.phase) + if (paused) " · tạm dừng" else "", style = type.title, color = Pal.Gold)
            val left = (s.remainingMs + 999) / 1000
            PixelText(
                "%02d:%02d".format(left / 60, left % 60),
                style = type.display.copy(fontSize = type.display.fontSize * 2),
                color = Pal.Cream, align = TextAlign.Center,
            )
            SessionDots(s)
            s.habit?.let { h ->
                val done = s.habitToday?.value ?: 0
                PixelText("${h.name} · $done/${h.target} phút hôm nay", style = type.body, color = Pal.Paper)
            }
            if (!s.running && s.timerHabits.isNotEmpty()) {
                PixelText("Tính phút vào", style = type.caption, color = Pal.Paper)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    PixelChip("Tự do", selected = s.habit == null) { vm.pick(null) }
                    for (h in s.timerHabits) PixelChip(h.name, selected = s.habit?.id == h.id) { vm.pick(h.id) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                when {
                    !s.running -> PixelButton(onClick = vm::start) {
                        PixelText(if (s.state == null) "Bắt đầu tập trung" else "Phiên tiếp theo", style = type.title, color = Pal.Cream)
                    }
                    paused -> PixelTextButton("Tiếp tục", onClick = vm::resume)
                    s.phase == Phase.FOCUS -> PixelTextButton("Tạm dừng", onClick = vm::pause, look = ButtonLook.Light)
                    else -> PixelTextButton("Bỏ qua nghỉ", onClick = vm::skipBreak, look = ButtonLook.Light)
                }
                if (s.state != null) PixelTextButton("Dừng", onClick = vm::stop, look = ButtonLook.Light)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelText(
                    "Tập trung ${s.cfg.focus}p · nghỉ ${s.cfg.shortBreak}p · nghỉ dài ${s.cfg.longBreak}p sau ${s.cfg.longEvery} phiên",
                    style = type.caption, color = Pal.Paper, modifier = Modifier.weight(1f),
                )
                if (!s.running) PixelTextButton("Chỉnh", onClick = { editing = true }, look = ButtonLook.Light)
            }
            PixelText("Thoát app vẫn chạy: trẫm đếm trên thanh thông báo, hết giờ có chuông.", style = type.caption, color = Pal.InkSoft)
        }
    }

    if (editing) ConfigDialog(s.cfg, onSave = { vm.saveConfig(it); editing = false }, onDismiss = { editing = false })
}

@Composable
private fun SessionDots(s: PomodoroUi) {
    val m = LocalPixelMetrics.current
    val done = (s.state?.focusDone ?: 0) % s.cfg.longEvery
    val full = done == 0 && (s.state?.focusDone ?: 0) > 0 && s.phase == Phase.LONG_BREAK
    Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        for (i in 0 until s.cfg.longEvery) {
            val on = full || i < done
            Canvas(Modifier.size(m.unit * 5)) {
                val u = m.ui.toFloat()
                drawRect(Pal.WoodDark)
                drawRect(if (on) Pal.Gold else Pal.Night, androidx.compose.ui.geometry.Offset(u, u), androidx.compose.ui.geometry.Size(size.width - 2 * u, size.height - 2 * u))
            }
        }
    }
}

@Composable
private fun ConfigDialog(initial: PomodoroConfig, onSave: (PomodoroConfig) -> Unit, onDismiss: () -> Unit) {
    var cfg by remember { mutableStateOf(initial) }
    fun step(v: Int, up: Boolean) = if (up) (if (v < 5) v + 1 else v + 5) else (if (v <= 5) v - 1 else v - 5)
    PixelDialog(onDismiss) {
        PixelText("Chỉnh Pomodoro", style = LocalPixelType.current.title)
        Row2("Tập trung", "${cfg.focus} phút", { cfg = cfg.copy(focus = step(cfg.focus, false).coerceAtLeast(1)) }, { cfg = cfg.copy(focus = step(cfg.focus, true).coerceAtMost(180)) })
        Row2("Nghỉ ngắn", "${cfg.shortBreak} phút", { cfg = cfg.copy(shortBreak = step(cfg.shortBreak, false).coerceAtLeast(1)) }, { cfg = cfg.copy(shortBreak = step(cfg.shortBreak, true).coerceAtMost(60)) })
        Row2("Nghỉ dài", "${cfg.longBreak} phút", { cfg = cfg.copy(longBreak = step(cfg.longBreak, false).coerceAtLeast(1)) }, { cfg = cfg.copy(longBreak = step(cfg.longBreak, true).coerceAtMost(90)) })
        Row2("Nghỉ dài sau", "${cfg.longEvery} phiên", { cfg = cfg.copy(longEvery = (cfg.longEvery - 1).coerceAtLeast(2)) }, { cfg = cfg.copy(longEvery = (cfg.longEvery + 1).coerceAtMost(8)) })
        Row(horizontalArrangement = Arrangement.spacedBy(LocalPixelMetrics.current.unit * 2)) {
            PixelTextButton("Lưu", onClick = { onSave(cfg) })
            PixelTextButton("45/10", onClick = { cfg = PomodoroConfig() }, look = ButtonLook.Light)
        }
    }
}

@Composable
private fun Row2(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        PixelText(label, style = type.body, modifier = Modifier.weight(1f))
        PixelTextButton("-", onClick = onMinus, look = ButtonLook.Light)
        PixelText(value, style = type.bodyBold)
        PixelTextButton("+", onClick = onPlus, look = ButtonLook.Light)
    }
}
