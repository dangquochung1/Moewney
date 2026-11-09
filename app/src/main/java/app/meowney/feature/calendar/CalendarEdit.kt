package app.meowney.feature.calendar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.calendar.EventRepeat
import app.meowney.core.calendar.offsets
import app.meowney.core.calendar.rep
import app.meowney.core.data.LABEL_COLORS
import app.meowney.core.db.CalLabelEntity
import app.meowney.core.db.CategoryEntity
import app.meowney.core.db.EventEntity
import app.meowney.core.db.TodoEntity
import app.meowney.core.db.TodoStepEntity
import app.meowney.core.format.formatVnd
import app.meowney.core.money.AmountInput
import app.meowney.core.notify.CalendarReminder
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelCheckbox
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDatePicker
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelKeypad
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.PixelTimePicker
import app.meowney.core.ui.hhmm
import app.meowney.core.ui.shortDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

private val OFFSETS = listOf(0 to "Đúng giờ", 5 to "5p", 15 to "15p", 30 to "30p", 60 to "1h", 1440 to "1 ngày")

@Composable
fun LabelDot(color: Long, sizeUnits: Int = 3) {
    val m = LocalPixelMetrics.current
    Canvas(Modifier.size(m.unit * sizeUnits)) {
        drawRect(Pal.WoodDark)
        val u = m.ui.toFloat()
        drawRect(Color(color), androidx.compose.ui.geometry.Offset(u, u), androidx.compose.ui.geometry.Size(size.width - 2 * u, size.height - 2 * u))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelChips(labels: List<CalLabelEntity>, selected: Long?, onPick: (Long?) -> Unit) {
    val m = LocalPixelMetrics.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
        PixelChip("Không nhãn", selected = selected == null) { onPick(null) }
        for (l in labels) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelDot(l.color)
                PixelChip(l.name, selected = selected == l.id) { onPick(l.id) }
            }
        }
    }
}

@Composable
private fun Label(text: String) = PixelText(text.uppercase(), style = LocalPixelType.current.caption, color = Pal.Gold)

// ------------------------------------------------------------------ event

data class EventEditUi(
    val loaded: Boolean = false,
    val event: EventEntity? = null,
    val labels: List<CalLabelEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
)

class EventEditViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val loaded = MutableStateFlow<Pair<Boolean, EventEntity?>>((id <= 0) to null)
    val closed = MutableStateFlow(false)

    init {
        if (id > 0) viewModelScope.launch { loaded.value = true to c.calendar.event(id) }
    }

    val state: StateFlow<EventEditUi> = combine(loaded, c.calendar.labels, c.money.categories) { (l, e), labels, cats ->
        EventEditUi(l, e, labels, cats.filter { it.type == "EXPENSE" })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventEditUi())

    fun save(e: EventEntity) = viewModelScope.launch {
        c.calendar.saveEvent(e)
        CalendarReminder.schedule(c.context)
        closed.value = true
    }

    fun delete() = viewModelScope.launch {
        c.calendar.deleteEvent(id)
        CalendarReminder.schedule(c.context)
        closed.value = true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditScreen(vm: EventEditViewModel, presetDay: LocalDate, presetMinute: Int?, onBack: () -> Unit, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    val closed by vm.closed.collectAsState()
    LaunchedEffect(closed) { if (closed) onDone() }
    if (!s.loaded) return
    val init = s.event
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current

    var title by remember { mutableStateOf(init?.title ?: "") }
    var label by remember { mutableStateOf(init?.labelId) }
    var allDay by remember { mutableStateOf(init?.allDay ?: (presetMinute == null)) }
    var startDay by remember { mutableStateOf(init?.startDay?.let(LocalDate::ofEpochDay) ?: presetDay) }
    var endDay by remember { mutableStateOf(init?.endDay?.let(LocalDate::ofEpochDay) ?: presetDay) }
    var startMinute by remember { mutableStateOf(init?.startMinute ?: presetMinute ?: (9 * 60)) }
    var endMinute by remember { mutableStateOf(init?.endMinute ?: ((presetMinute ?: (9 * 60)) + 60).coerceAtMost(23 * 60 + 59)) }
    var repeat by remember { mutableStateOf(init?.rep ?: EventRepeat.NONE) }
    var until by remember { mutableStateOf(init?.repeatUntil?.let(LocalDate::ofEpochDay)) }
    var remind by remember { mutableStateOf(init?.remind ?: true) }
    val offsets = remember { mutableStateListOf<Int>().apply { addAll(init?.offsets ?: listOf(15)) } }
    var location by remember { mutableStateOf(init?.location ?: "") }
    var note by remember { mutableStateOf(init?.note ?: "") }
    var cost by remember { mutableStateOf(AmountInput(init?.cost ?: 0)) }
    var costCategory by remember { mutableStateOf(init?.costCategoryId) }
    var typingCost by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    val birthdayLabel = s.labels.firstOrNull { it.kind == "BIRTHDAY" }?.id

    PixelScreen(if (init == null) "Sự kiện mới" else "Sửa sự kiện", onBack = onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelTextField(title, { title = it.take(40) }, Modifier.fillMaxWidth(), placeholder = "vd: Họp nhóm, Sinh nhật mẹ")
            Label("Nhãn")
            LabelChips(s.labels, label) {
                label = it
                // a birthday is an all-day yearly event by default
                if (it != null && it == birthdayLabel && init == null) {
                    allDay = true
                    repeat = EventRepeat.YEARLY
                }
            }
            Label("Thời gian")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Có giờ", selected = !allDay) { allDay = false }
                PixelChip("Cả ngày", selected = allDay) { allDay = true }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelText("Từ", style = type.body, color = Pal.Cream, modifier = Modifier.width(m.unit * 10))
                PixelTextButton(shortDate(startDay), onClick = { picking = "startDay" }, look = ButtonLook.Light)
                if (!allDay) PixelTextButton(hhmm(startMinute), onClick = { picking = "startMinute" }, look = ButtonLook.Light)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelText("Đến", style = type.body, color = Pal.Cream, modifier = Modifier.width(m.unit * 10))
                PixelTextButton(shortDate(endDay), onClick = { picking = "endDay" }, look = ButtonLook.Light)
                if (!allDay) PixelTextButton(hhmm(endMinute), onClick = { picking = "endMinute" }, look = ButtonLook.Light)
            }
            Label("Lặp lại")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (r in EventRepeat.entries) PixelChip(r.label, selected = repeat == r) { repeat = r }
            }
            if (repeat != EventRepeat.NONE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelText("Đến ngày", style = type.body, color = Pal.Cream)
                    PixelTextButton(until?.let { shortDate(it) } ?: "Mãi mãi", onClick = { picking = "until" }, look = ButtonLook.Light)
                }
            }
            Label("Nhắc")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Bật", selected = remind) { remind = true }
                PixelChip("Tắt", selected = !remind) { remind = false }
            }
            if (remind) {
                if (allDay) {
                    PixelText("Sự kiện cả ngày: trẫm nhắc trong thông báo 8:00 sáng hôm đó.", style = type.caption, color = Pal.Paper)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                        for ((off, text) in OFFSETS) PixelChip(text, selected = off in offsets) {
                            if (off in offsets) {
                                if (offsets.size > 1) offsets.remove(off)
                            } else {
                                offsets.add(off)
                            }
                        }
                    }
                }
            }
            Label("Địa điểm & ghi chú")
            PixelTextField(location, { location = it.take(60) }, Modifier.fillMaxWidth(), placeholder = "Địa điểm")
            PixelTextField(note, { note = it.take(300) }, Modifier.fillMaxWidth(), placeholder = "Ghi chú")
            Label("Dự chi")
            PixelTextButton(
                if (cost.value == 0L) "Không tốn tiền" else formatVnd(cost.value),
                onClick = { typingCost = !typingCost }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth(),
            )
            if (typingCost) PixelKeypad(onKey = { cost = cost.press(it) })
            if (cost.value > 0) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    for (cat in s.categories) PixelChip(cat.name, selected = costCategory == cat.id) { costCategory = cat.id }
                }
            }
            val end = if (endDay.isBefore(startDay)) startDay else endDay
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Lưu", enabled = title.isNotBlank(), onClick = {
                    val base = init ?: EventEntity(title = "", startDay = 0, endDay = 0, createdAt = 0)
                    vm.save(
                        base.copy(
                            title = title.trim(), labelId = label, allDay = allDay,
                            startDay = startDay.toEpochDay(), endDay = end.toEpochDay(),
                            startMinute = if (allDay) null else startMinute,
                            endMinute = if (allDay) null else if (end == startDay) maxOf(endMinute, startMinute) else endMinute,
                            repeat = repeat.name, repeatUntil = if (repeat == EventRepeat.NONE) null else until?.toEpochDay(),
                            remind = remind, remindOffsets = offsets.sorted().joinToString(","),
                            location = location.trim(), note = note.trim(),
                            cost = cost.value, costCategoryId = if (cost.value > 0) costCategory else null,
                        ),
                    )
                })
                if (init != null) PixelTextButton(if (init.rep == EventRepeat.NONE) "Xoá" else "Xoá cả chuỗi", onClick = vm::delete, look = ButtonLook.Light)
            }
            Spacer(Modifier.height(m.unit * 4))
        }
    }

    when (picking) {
        "startDay" -> PixelDatePicker(startDay, onPick = { d ->
            if (d != null) {
                val len = endDay.toEpochDay() - startDay.toEpochDay()
                startDay = d
                endDay = d.plusDays(len.coerceAtLeast(0))
            }
            picking = null
        }, onDismiss = { picking = null })
        "endDay" -> PixelDatePicker(endDay, onPick = { d -> if (d != null) endDay = maxOf(d, startDay); picking = null }, onDismiss = { picking = null })
        "until" -> PixelDatePicker(until, allowNone = true, onPick = { until = it; picking = null }, onDismiss = { picking = null })
        "startMinute" -> PixelTimePicker(startMinute, onPick = { v ->
            if (v != null) {
                val len = endMinute - startMinute
                startMinute = v
                endMinute = (v + len.coerceAtLeast(15)).coerceAtMost(23 * 60 + 59)
            }
            picking = null
        }, onDismiss = { picking = null })
        "endMinute" -> PixelTimePicker(endMinute, onPick = { v -> if (v != null) endMinute = v; picking = null }, onDismiss = { picking = null })
    }
}

// ------------------------------------------------------------------ to-do

data class TodoEditUi(
    val loaded: Boolean = false,
    val todo: TodoEntity? = null,
    val steps: List<TodoStepEntity> = emptyList(),
    val labels: List<CalLabelEntity> = emptyList(),
)

class TodoEditViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    private val loaded = MutableStateFlow(Triple<Boolean, TodoEntity?, List<TodoStepEntity>>(id <= 0, null, emptyList()))
    val closed = MutableStateFlow(false)

    init {
        if (id > 0) viewModelScope.launch { loaded.value = Triple(true, c.calendar.todo(id), c.calendar.stepsOf(id)) }
    }

    val state: StateFlow<TodoEditUi> = combine(loaded, c.calendar.labels) { (l, t, steps), labels -> TodoEditUi(l, t, steps, labels) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoEditUi())

    fun save(t: TodoEntity, steps: List<TodoStepEntity>) = viewModelScope.launch {
        c.calendar.saveTodo(t, steps)
        CalendarReminder.schedule(c.context)
        closed.value = true
    }

    fun delete() = viewModelScope.launch {
        c.calendar.deleteTodo(id)
        CalendarReminder.schedule(c.context)
        closed.value = true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TodoEditScreen(vm: TodoEditViewModel, presetDay: LocalDate?, onBack: () -> Unit, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    val closed by vm.closed.collectAsState()
    LaunchedEffect(closed) { if (closed) onDone() }
    if (!s.loaded) return
    val init = s.todo
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current

    var title by remember { mutableStateOf(init?.title ?: "") }
    var label by remember { mutableStateOf(init?.labelId) }
    var due by remember { mutableStateOf(if (init != null) init.dueDay?.let(LocalDate::ofEpochDay) else presetDay) }
    var dueMinute by remember { mutableStateOf(init?.dueMinute) }
    var difficulty by remember { mutableStateOf(init?.difficulty ?: 2) }
    var starred by remember { mutableStateOf(init?.starred ?: false) }
    var repeat by remember { mutableStateOf(init?.repeat?.let { EventRepeat.valueOf(it) } ?: EventRepeat.NONE) }
    var remind by remember { mutableStateOf(init?.remind ?: true) }
    var note by remember { mutableStateOf(init?.note ?: "") }
    val steps = remember { mutableStateListOf<TodoStepEntity>().apply { addAll(s.steps) } }
    var newStep by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }

    PixelScreen(if (init == null) "Việc mới" else "Sửa việc", onBack = onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelTextField(title, { title = it.take(60) }, Modifier.fillMaxWidth(), placeholder = "vd: Nộp báo cáo, Mua quà sinh nhật mẹ")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip(if (starred) "★ Quan trọng" else "☆ Quan trọng", selected = starred) { starred = !starred }
            }
            Label("Hạn")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton(due?.let { shortDate(it) } ?: "Chưa xếp lịch", onClick = { picking = "due" }, look = ButtonLook.Light)
                if (due != null) PixelTextButton(dueMinute?.let { hhmm(it) } ?: "Không giờ", onClick = { picking = "time" }, look = ButtonLook.Light)
            }
            if (due != null) {
                Label("Lặp lại")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    for (r in EventRepeat.entries) PixelChip(r.label, selected = repeat == r) { repeat = r }
                }
                Label("Nhắc")
                Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                    PixelChip("Bật", selected = remind) { remind = true }
                    PixelChip("Tắt", selected = !remind) { remind = false }
                }
                if (remind) {
                    PixelText(
                        if (dueMinute == null) "Trẫm nhắc trong thông báo 8:00 sáng hôm đó." else "Trẫm nhắc đúng ${hhmm(dueMinute!!)}.",
                        style = type.caption, color = Pal.Paper,
                    )
                }
            }
            Label("Nhãn")
            LabelChips(s.labels, label) { label = it }
            Label("Độ khó (xu khi xong, đúng hạn +2)")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                PixelChip("Dễ · 2", selected = difficulty == 1) { difficulty = 1 }
                PixelChip("Vừa · 4", selected = difficulty == 2) { difficulty = 2 }
                PixelChip("Khó · 6", selected = difficulty == 3) { difficulty = 3 }
            }
            Label("Việc con")
            for ((i, step) in steps.withIndex()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelCheckbox(step.done, onCheckedChange = { steps[i] = step.copy(done = it) })
                    PixelText(step.text, style = type.body, color = Pal.Cream, modifier = Modifier.weight(1f))
                    PixelTextButton("x", onClick = { steps.removeAt(i) }, look = ButtonLook.Light)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextField(newStep, { newStep = it.take(60) }, Modifier.weight(1f), placeholder = "Thêm việc con")
                PixelTextButton("Thêm", enabled = newStep.isNotBlank(), onClick = {
                    steps.add(TodoStepEntity(todoId = 0, text = newStep.trim()))
                    newStep = ""
                }, look = ButtonLook.Light)
            }
            Label("Ghi chú")
            PixelTextField(note, { note = it.take(300) }, Modifier.fillMaxWidth(), placeholder = "Ghi chú")
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Lưu", enabled = title.isNotBlank(), onClick = {
                    val base = init ?: TodoEntity(title = "", createdAt = 0)
                    vm.save(
                        base.copy(
                            title = title.trim(), labelId = label, dueDay = due?.toEpochDay(), dueMinute = if (due == null) null else dueMinute,
                            difficulty = difficulty, starred = starred, note = note.trim(),
                            repeat = if (due == null) "NONE" else repeat.name, remind = remind,
                        ),
                        steps.toList(),
                    )
                })
                if (init != null) PixelTextButton("Xoá", onClick = vm::delete, look = ButtonLook.Light)
            }
            Spacer(Modifier.height(m.unit * 4))
        }
    }

    when (picking) {
        "due" -> PixelDatePicker(due, allowNone = true, onPick = { due = it; picking = null }, onDismiss = { picking = null })
        "time" -> PixelTimePicker(dueMinute, allowNone = true, onPick = { dueMinute = it; picking = null }, onDismiss = { picking = null })
    }
}

// ------------------------------------------------------------------ labels

class LabelsViewModel(private val c: AppContainer) : ViewModel() {
    val labels = c.calendar.labels.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun save(l: CalLabelEntity) = viewModelScope.launch { c.calendar.saveLabel(l) }
    fun delete(l: CalLabelEntity) = viewModelScope.launch { c.calendar.deleteLabel(l) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LabelsScreen(vm: LabelsViewModel, onBack: () -> Unit) {
    val labels by vm.labels.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var editing by remember { mutableStateOf<CalLabelEntity?>(null) }
    PixelScreen("Nhãn lịch", onBack = onBack, actions = {
        PixelTextButton("+", onClick = { editing = CalLabelEntity(name = "", color = LABEL_COLORS[(labels.size) % LABEL_COLORS.size]) })
    }) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            items(labels, key = { it.id }) { l ->
                Row(Modifier.fillMaxWidth().clickable { editing = l }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    LabelDot(l.color, 5)
                    PixelText(l.name, style = type.bodyBold, color = Pal.Cream, modifier = Modifier.weight(1f))
                    if (l.kind == "BIRTHDAY") PixelText("đếm ngược + mũ", style = type.caption, color = Pal.Paper)
                }
            }
        }
    }
    editing?.let { l ->
        var name by remember(l) { mutableStateOf(l.name) }
        var color by remember(l) { mutableStateOf(l.color) }
        PixelDialog(onDismiss = { editing = null }) {
            PixelText(if (l.id == 0L) "Nhãn mới" else "Sửa nhãn", style = type.title)
            PixelTextField(name, { name = it.take(20) }, Modifier.fillMaxWidth(), placeholder = "Tên nhãn")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                for (cl in LABEL_COLORS) {
                    Canvas(Modifier.size(m.unit * 8).clickable { color = cl }) {
                        drawRect(if (cl == color) Pal.Gold else Pal.WoodDark)
                        val u = m.ui.toFloat() * 2
                        drawRect(Color(cl), androidx.compose.ui.geometry.Offset(u, u), androidx.compose.ui.geometry.Size(size.width - 2 * u, size.height - 2 * u))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Lưu", enabled = name.isNotBlank(), onClick = {
                    vm.save(l.copy(name = name.trim(), color = color))
                    editing = null
                })
                if (l.id != 0L && l.kind != "BIRTHDAY") PixelTextButton("Xoá", onClick = {
                    vm.delete(l)
                    editing = null
                }, look = ButtonLook.Light)
            }
        }
    }
}
