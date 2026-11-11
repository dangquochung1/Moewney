package app.meowney.feature.habit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.data.HabitToday
import app.meowney.core.data.JarState
import app.meowney.core.data.hasMoney
import app.meowney.core.data.kind
import app.meowney.core.data.part
import app.meowney.core.data.rule
import app.meowney.core.db.GoalRow
import app.meowney.core.db.HabitEntity
import app.meowney.core.db.HabitLogEntity
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.habit.HabitEngine
import app.meowney.core.habit.HabitType
import app.meowney.core.habit.PartOfDay
import app.meowney.core.habit.Schedule
import app.meowney.core.money.AmountInput
import app.meowney.core.money.JarCode
import app.meowney.core.notify.HabitReminder
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelKeypad
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelProgress
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.RavenIcon
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** Raven icons that read well as habits. */
val HABIT_ICONS = listOf(
    656, 657, 100, 96, 6, 528, 529, 686, 658, 280, 432, 440,
    159, 503, 527, 539, 11, 665, 667, 660, 1, 292, 290, 12,
)

private val WEEKDAY_SHORT = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

fun scheduleLabel(h: HabitEntity): String = when (Schedule.valueOf(h.schedule)) {
    Schedule.DAILY -> "Hằng ngày"
    Schedule.WEEKLY -> "${h.timesPerWeek} lần/tuần"
    Schedule.WEEKDAYS -> WEEKDAY_SHORT.filterIndexed { i, _ -> h.weekdays and (1 shl i) != 0 }.joinToString(" ")
}

fun targetLabel(h: HabitEntity): String = when (h.kind) {
    HabitType.COUNT -> "${h.target} ${h.unit}".trim()
    HabitType.TIMER -> "${h.target} phút"
    HabitType.QUIT -> ""
    HabitType.CHECK -> ""
}

fun timeLabel(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

// ------------------------------------------------------------------ list

data class HabitsUi(val habits: List<HabitEntity> = emptyList(), val today: Map<Long, HabitToday> = emptyMap())

class HabitsViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<HabitsUi> = combine(c.habits.habits, c.habits.today(LocalDate.now())) { all, today ->
        HabitsUi(all, today.associateBy { it.habit.id })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUi())
}

@Composable
fun HabitsScreen(vm: HabitsViewModel, onBack: () -> Unit, onNew: () -> Unit, onOpen: (Long) -> Unit, onPomodoro: () -> Unit) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelScreen("Thói quen", onBack = onBack, actions = {
        PixelTextButton("Pomodoro", onClick = onPomodoro, look = ButtonLook.Light)
        PixelTextButton("+", onClick = onNew)
    }) {
        if (s.habits.isEmpty()) {
            PixelText(
                "Chưa có thói quen nào. Bấm + để tạo: uống nước, đọc sách, bỏ trà sữa... Xong mỗi cái là trẫm được vờn len!",
                style = type.body, color = Pal.InkSoft,
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            items(s.habits, key = { it.id }) { h -> HabitCard(h, s.today[h.id], onClick = { onOpen(h.id) }) }
        }
    }
}

@Composable
private fun HabitCard(h: HabitEntity, today: HabitToday?, onClick: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelPanel(Modifier.fillMaxWidth().clickable(onClick = onClick), sprite = "ui/wood/nine_path_panel_3") {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RavenIcon(h.icon)
                Spacer(Modifier.width(m.unit * 2))
                Column(Modifier.weight(1f)) {
                    PixelText(h.name, style = type.bodyBold, maxLines = 1)
                    val bits = listOfNotNull(
                        scheduleLabel(h), targetLabel(h).ifEmpty { null }, h.part.label,
                        h.reminderMinute?.let { "nhắc ${timeLabel(it)}" },
                    )
                    PixelText(bits.joinToString(" · "), style = type.caption, color = Pal.InkSoft, maxLines = 1)
                }
                val status = when {
                    today == null -> ""
                    today.done && h.kind == HabitType.QUIT -> "đang giữ"
                    today.done -> "xong"
                    h.kind == HabitType.COUNT || h.kind == HabitType.TIMER -> "${today.value}/${h.target}"
                    else -> "chưa"
                }
                PixelText(status, style = type.caption, color = if (today?.done == true) Pal.Green else Pal.InkSoft)
            }
            PixelProgress(h.strength / 100f, color = Pal.Green, heightPx = 4)
            PixelText(
                "Sức mạnh ${h.strength.toInt()}% · chuỗi ${h.streak} · vé nghỉ ${h.tickets}" + if (h.hasMoney) " · nối ${formatVndShort(h.moneyAmount)}" else "",
                style = type.caption, color = Pal.InkSoft,
            )
        }
    }
}

// ------------------------------------------------------------------ create / edit

data class HabitEditUi(
    val habit: HabitEntity? = null,
    val loaded: Boolean = false,
    val jars: List<JarState> = emptyList(),
    val goals: List<GoalRow> = emptyList(),
)

class HabitEditViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val loadedHabit = MutableStateFlow<HabitEntity?>(null)
    private val loaded = MutableStateFlow(id <= 0)
    val closed = MutableStateFlow(false)

    init {
        if (id > 0) viewModelScope.launch {
            loadedHabit.value = c.habits.habitNow(id)
            loaded.value = true
        }
    }

    val state: StateFlow<HabitEditUi> = combine(loadedHabit, loaded, c.money.jarStates, c.money.goals) { h, l, jars, goals ->
        HabitEditUi(h, l, jars, goals.filter { it.achievedDay == null && !it.archived })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitEditUi())

    fun save(h: HabitEntity) = viewModelScope.launch {
        c.habits.save(h)
        HabitReminder.schedule(c.context)
        closed.value = true
    }

    fun archive() = viewModelScope.launch {
        c.habits.archive(id)
        HabitReminder.schedule(c.context)
        closed.value = true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitEditScreen(vm: HabitEditViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    val closed by vm.closed.collectAsState()
    LaunchedEffect(closed) { if (closed) onDone() }
    if (!s.loaded) return
    val init = s.habit
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current

    var name by remember { mutableStateOf(init?.name ?: "") }
    var icon by remember { mutableStateOf(init?.icon ?: HABIT_ICONS.first()) }
    var kind by remember { mutableStateOf(init?.kind ?: HabitType.CHECK) }
    var target by remember { mutableStateOf(init?.target ?: 1) }
    var unit by remember { mutableStateOf(init?.unit ?: "") }
    var schedule by remember { mutableStateOf(init?.rule?.schedule ?: Schedule.DAILY) }
    var weekdays by remember { mutableStateOf(init?.weekdays ?: 0x7F) }
    var perWeek by remember { mutableStateOf(init?.timesPerWeek?.takeIf { it > 0 } ?: 3) }
    var part by remember { mutableStateOf(init?.part ?: PartOfDay.ALL) }
    var remind by remember { mutableStateOf(init?.reminderMinute) }
    var difficulty by remember { mutableStateOf(init?.difficulty ?: 2) }
    var linked by remember { mutableStateOf(init?.hasMoney == true) }
    var amount by remember { mutableStateOf(AmountInput(init?.moneyAmount ?: 0)) }
    var typingAmount by remember { mutableStateOf(false) }
    val play = s.jars.firstOrNull { it.code == JarCode.PLAY }?.jar?.id
    var fromJar by remember { mutableStateOf(init?.moneyFromJarId ?: play) }
    var toGoal by remember { mutableStateOf(init?.moneyToGoalId) }
    var toJar by remember { mutableStateOf(init?.moneyToJarId) }

    PixelScreen(if (init == null) "Thói quen mới" else "Sửa thói quen", onBack = onBack) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        ) {
            PixelTextField(name, { name = it.take(28) }, Modifier.fillMaxWidth(), placeholder = "vd: Uống nước, Đọc sách, Không trà sữa")
            Label("Biểu tượng")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (i in HABIT_ICONS) {
                    Box(
                        Modifier
                            .border(m.dp(m.ui), if (i == icon) Pal.Gold else Color.Transparent)
                            .clickable { icon = i }
                            .padding(m.unit),
                    ) { RavenIcon(i) }
                }
            }
            Label("Kiểu")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (k in HabitType.entries) PixelChip(k.label, selected = kind == k) {
                    kind = k
                    target = when (k) {
                        HabitType.COUNT -> if (target > 1) target else 8
                        HabitType.TIMER -> if (target >= 5) target else 45
                        else -> 1
                    }
                    if (k == HabitType.QUIT && schedule == Schedule.WEEKLY) schedule = Schedule.DAILY
                }
            }
            when (kind) {
                HabitType.COUNT -> {
                    Stepper("Mỗi ngày", "$target", onMinus = { target = (target - 1).coerceAtLeast(2) }, onPlus = { target = (target + 1).coerceAtMost(99) })
                    PixelTextField(unit, { unit = it.take(10) }, Modifier.fillMaxWidth(), placeholder = "đơn vị, vd: cốc, trang, lần")
                }
                HabitType.TIMER -> Stepper("Mỗi ngày", "$target phút", onMinus = { target = (target - 5).coerceAtLeast(5) }, onPlus = { target = (target + 5).coerceAtMost(480) })
                HabitType.QUIT -> Hint("Mặc định Sen giữ được mỗi ngày; lỡ thì bấm \"Lỡ rồi\". Hết ngày mới tính.")
                HabitType.CHECK -> Unit
            }
            Label("Lặp lại")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Hằng ngày", selected = schedule == Schedule.DAILY) { schedule = Schedule.DAILY }
                PixelChip("Thứ cố định", selected = schedule == Schedule.WEEKDAYS) { schedule = Schedule.WEEKDAYS }
                if (kind != HabitType.QUIT) PixelChip("X lần/tuần", selected = schedule == Schedule.WEEKLY) { schedule = Schedule.WEEKLY }
            }
            if (schedule == Schedule.WEEKDAYS) {
                Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                    WEEKDAY_SHORT.forEachIndexed { i, label ->
                        val bit = 1 shl i
                        PixelChip(label, selected = weekdays and bit != 0) {
                            val next = weekdays xor bit
                            if (next != 0) weekdays = next
                        }
                    }
                }
            }
            if (schedule == Schedule.WEEKLY) {
                Stepper("Mỗi tuần", "$perWeek lần", onMinus = { perWeek = (perWeek - 1).coerceAtLeast(1) }, onPlus = { perWeek = (perWeek + 1).coerceAtMost(6) })
            }
            Label("Buổi")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                for (p in PartOfDay.entries) PixelChip(p.label, selected = part == p) { part = p }
            }
            Label("Nhắc")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Không", selected = remind == null) { remind = null }
                PixelChip(if (remind == null) "Có" else timeLabel(remind!!), selected = remind != null) {
                    if (remind == null) remind = defaultReminder(part)
                }
                if (remind != null) {
                    PixelTextButton("-1h", onClick = { remind = ((remind!! - 60) + 1440) % 1440 }, look = ButtonLook.Light)
                    PixelTextButton("+1h", onClick = { remind = (remind!! + 60) % 1440 }, look = ButtonLook.Light)
                    PixelTextButton("+15p", onClick = { remind = (remind!! + 15) % 1440 }, look = ButtonLook.Light)
                }
            }
            Label("Độ khó (xu mỗi lần xong)")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Dễ · 2 xu", selected = difficulty == 1) { difficulty = 1 }
                PixelChip("Vừa · 4 xu", selected = difficulty == 2) { difficulty = 2 }
                PixelChip("Khó · 6 xu", selected = difficulty == 3) { difficulty = 3 }
            }
            Label("Nối tiền")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Không", selected = !linked) { linked = false }
                PixelChip("Có", selected = linked) { linked = true }
            }
            if (linked) {
                Hint(
                    if (kind == HabitType.QUIT) "Mỗi ngày giữ được, trẫm chuyển số tiền này sang chỗ Sen chọn. Tiền vẫn trong ví, chỉ đổi lọ."
                    else "Mỗi lần xong, trẫm thưởng Sen số tiền này vào chỗ Sen chọn. Tiền vẫn trong ví, chỉ đổi lọ.",
                )
                PixelTextButton(
                    if (amount.value == 0L) "Bao nhiêu?" else formatVnd(amount.value),
                    onClick = { typingAmount = !typingAmount }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth(),
                )
                if (typingAmount) PixelKeypad(onKey = { amount = amount.press(it) })
                Label("Lấy từ lọ")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    for (j in s.jars) PixelChip(j.jar.name, selected = fromJar == j.jar.id) { fromJar = j.jar.id }
                }
                Label("Chuyển vào")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    for (g in s.goals) PixelChip("Mục tiêu ${g.name}", selected = toGoal == g.id) {
                        toGoal = g.id
                        toJar = null
                    }
                    for (j in s.jars.filter { it.jar.id != fromJar }) PixelChip(j.jar.name, selected = toGoal == null && toJar == j.jar.id) {
                        toJar = j.jar.id
                        toGoal = null
                    }
                }
            }
            val canSave = name.isNotBlank() && (!linked || (amount.value > 0 && fromJar != null && (toGoal != null || toJar != null)))
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Lưu", enabled = canSave, onClick = {
                    val base = init ?: HabitEntity(name = "", type = kind.name, icon = icon, closedThrough = 0, createdDay = 0)
                    vm.save(
                        base.copy(
                            name = name.trim(), type = kind.name, icon = icon, target = target, unit = if (kind == HabitType.COUNT) unit.trim() else "",
                            schedule = schedule.name, weekdays = weekdays, timesPerWeek = if (schedule == Schedule.WEEKLY) perWeek else 0,
                            partOfDay = part.name, reminderMinute = remind, difficulty = difficulty,
                            moneyAmount = if (linked) amount.value else 0, moneyFromJarId = if (linked) fromJar else null,
                            moneyToGoalId = if (linked) toGoal else null, moneyToJarId = if (linked && toGoal == null) toJar else null,
                        ),
                    )
                })
                if (init != null) PixelTextButton("Lưu trữ", onClick = vm::archive, look = ButtonLook.Light)
            }
            Spacer(Modifier.height(m.unit * 4))
        }
    }
}

private fun defaultReminder(part: PartOfDay) = when (part) {
    PartOfDay.MORNING -> 7 * 60
    PartOfDay.AFTERNOON -> 14 * 60
    PartOfDay.EVENING -> 20 * 60
    PartOfDay.ALL -> 9 * 60
}

@Composable
private fun Label(text: String) = PixelText(text.uppercase(), style = LocalPixelType.current.caption, color = Pal.Gold)

@Composable
private fun Hint(text: String) = PixelText(text, style = LocalPixelType.current.caption, color = Pal.InkSoft)

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        PixelText(label, style = type.body, color = Pal.Ink, modifier = Modifier.weight(1f))
        PixelTextButton("-", onClick = onMinus, look = ButtonLook.Light)
        PixelText(value, style = type.bodyBold, color = Pal.Ink, align = TextAlign.Center)
        PixelTextButton("+", onClick = onPlus, look = ButtonLook.Light)
    }
}

// ------------------------------------------------------------------ detail

data class HabitDetailUi(val habit: HabitEntity? = null, val month: YearMonth = YearMonth.now(), val logs: List<HabitLogEntity> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class HabitDetailViewModel(c: AppContainer, id: Long) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())

    val state: StateFlow<HabitDetailUi> = month.flatMapLatest { ym ->
        combine(c.habits.habit(id), c.habits.logs(id, ym.atDay(1), ym.atEndOfMonth())) { h, logs -> HabitDetailUi(h, ym, logs) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitDetailUi())

    fun shift(months: Long) {
        val next = month.value.plusMonths(months)
        if (!next.isAfter(YearMonth.now())) month.value = next
    }
}

@Composable
fun HabitDetailScreen(vm: HabitDetailViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val s by vm.state.collectAsState()
    val h = s.habit ?: return
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelScreen(h.name, onBack = onBack, actions = { PixelTextButton("Sửa", onClick = onEdit, look = ButtonLook.Light) }) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RavenIcon(h.icon, scale = m.ui * 2)
                        Spacer(Modifier.width(m.unit * 2))
                        Column(Modifier.weight(1f)) {
                            PixelText("Sức mạnh ${h.strength.toInt()}%", style = type.title)
                            PixelText(listOf(h.kind.label, scheduleLabel(h), targetLabel(h)).filter { it.isNotEmpty() }.joinToString(" · "), style = type.caption, color = Pal.InkSoft)
                        }
                    }
                    PixelProgress(h.strength / 100f, color = Pal.Green)
                    PixelText("Chuỗi ${h.streak} · kỷ lục ${h.bestStreak} · vé nghỉ ${h.tickets}/${HabitEngine.MAX_TICKETS}", style = type.body)
                    PixelText("Giữ ${HabitEngine.TICKET_EVERY} lần liên tiếp được 1 vé; lỡ một lần trẫm tự dùng vé cho Sen.", style = type.caption, color = Pal.InkSoft)
                    if (h.hasMoney) PixelText("Nối tiền: ${formatVnd(h.moneyAmount)} mỗi lần", style = type.caption, color = Pal.InkSoft)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PixelTextButton("<", onClick = { vm.shift(-1) }, look = ButtonLook.Light)
                PixelText("Tháng ${s.month.monthValue}/${s.month.year}", style = type.bodyBold, color = Pal.Ink, align = TextAlign.Center, modifier = Modifier.weight(1f))
                PixelTextButton(">", onClick = { vm.shift(1) }, enabled = s.month.isBefore(YearMonth.now()), look = ButtonLook.Light)
            }
            MonthGrid(h, s.month, s.logs)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                Legend(Pal.Green, "xong")
                Legend(Pal.Sky, "nghỉ (vé)")
                Legend(Pal.Orange, "lỡ")
                Legend(Pal.WoodDark, "không cần")
            }
        }
    }
}

private fun cellColor(h: HabitEntity, log: HabitLogEntity?, day: LocalDate, today: LocalDate): Color? {
    val epoch = day.toEpochDay()
    if (epoch < h.createdDay || day.isAfter(today)) return null
    return when (log?.status) {
        "DONE", "KEPT" -> Pal.Green
        "RESTED" -> Pal.Sky
        "MISSED", "FAILED" -> Pal.Orange
        else -> when {
            !HabitEngine.isScheduled(h.rule, day) -> Pal.WoodDark
            h.kind == HabitType.QUIT && day == today -> Pal.Green.copy(alpha = 0.5f)
            else -> Pal.Night
        }
    }
}

@Composable
private fun MonthGrid(h: HabitEntity, month: YearMonth, logs: List<HabitLogEntity>) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    val byDay = logs.associateBy { it.day }
    val lead = month.atDay(1).dayOfWeek.value - 1
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        Row {
            for (d in WEEKDAY_SHORT) PixelText(d, style = type.caption, color = Pal.InkSoft, align = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        for (week in 0 until (cells + 6) / 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                for (col in 0 until 7) {
                    val n = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).aspectRatio(1.4f), contentAlignment = Alignment.Center) {
                        if (n in 1..month.lengthOfMonth()) {
                            val day = month.atDay(n)
                            val color = cellColor(h, byDay[day.toEpochDay()], day, today)
                            if (color != null) {
                                Canvas(Modifier.fillMaxWidth().aspectRatio(1.4f)) {
                                    val u = m.ui.toFloat()
                                    drawRect(if (day == today) Pal.Gold else Pal.WoodDark)
                                    drawRect(color, androidx.compose.ui.geometry.Offset(u, u), androidx.compose.ui.geometry.Size(size.width - 2 * u, size.height - 2 * u))
                                }
                            }
                            PixelText("$n", style = type.caption, color = if (color == null) Pal.InkSoft else Pal.Cream)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    val m = LocalPixelMetrics.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(m.unit * 4)) { drawRect(color) }
        Spacer(Modifier.width(m.unit))
        PixelText(label, style = LocalPixelType.current.caption, color = Pal.InkSoft)
    }
}
