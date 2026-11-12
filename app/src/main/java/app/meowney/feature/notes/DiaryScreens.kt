package app.meowney.feature.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.CalDay
import app.meowney.core.data.DiaryReward
import app.meowney.core.data.NoteFull
import app.meowney.core.data.NoteSaved
import app.meowney.core.format.formatVndShort
import app.meowney.core.notes.BlockType
import app.meowney.core.notes.DiaryPrompts
import app.meowney.core.notes.Mood
import app.meowney.core.notes.MoodEngine
import app.meowney.core.notes.PromptContext
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.PixelTextField
import app.meowney.core.ui.WEEKDAYS_SHORT
import app.meowney.core.ui.shortDate
import app.meowney.feature.calendar.gridRange
import app.meowney.feature.money.SpriteView
import app.meowney.feature.room.CatLines
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** A mood emote at [scale] art pixels (an empty speech bubble when there is no mood). */
@Composable
fun MoodIcon(mood: Mood?, scale: Int, modifier: Modifier = Modifier, framed: Boolean = false) {
    val m = LocalPixelMetrics.current
    // the emotes are cream bubbles: on paper they need a dark frame to show
    val frame = if (framed) Modifier.background(Pal.WoodDark).padding(m.unit).background(moodTint(mood)).padding(horizontal = m.unit * 2, vertical = m.unit) else Modifier
    Box(modifier.then(frame), contentAlignment = Alignment.Center) {
        SpriteView(mood?.emote ?: "ui/emote/emote5", scale = scale, frame = 0)
    }
}

/** Light tint behind a mood in the diary calendar. */
fun moodTint(m: Mood?): Color = when (m) {
    Mood.GREAT -> Color(0xFF5CBF6A)
    Mood.GOOD -> Color(0xFF9CC07A)
    Mood.OK -> Color(0xFFC9B48A)
    Mood.BAD -> Color(0xFF8FB3CC)
    Mood.AWFUL -> Color(0xFF6F7FA8)
    null -> Pal.Wood
}

// ------------------------------------------------------------------ diary tab

class DiaryViewModel(private val c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())
    val selected = MutableStateFlow(LocalDate.now())
    val pages: StateFlow<Map<Long, NoteFull>> = c.notes.diaries.map { l -> l.associateBy { it.note.day!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val written: StateFlow<Set<Long>> = c.notes.writtenDays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val hide: StateFlow<Boolean> = c.settings.flow.map { it.diaryHidePreview }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun shiftMonth(by: Long) {
        month.value = month.value.plusMonths(by)
    }

    fun select(d: LocalDate) {
        selected.value = d
    }
}

@Composable
fun DiaryTab(vm: DiaryViewModel, actions: NotesActions) {
    val month by vm.month.collectAsState()
    val selected by vm.selected.collectAsState()
    val pages by vm.pages.collectAsState()
    val written by vm.written.collectAsState()
    val hide by vm.hide.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    val todayPage = pages[today.toEpochDay()]
    val streak = MoodEngine.streak(written, today.toEpochDay())

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelTextButton("<", onClick = { vm.shiftMonth(-1) }, look = ButtonLook.Light)
            PixelText("Tháng ${month.monthValue}/${month.year}", style = type.bodyBold, color = Pal.Ink, align = TextAlign.Center, modifier = Modifier.weight(1f))
            PixelTextButton(">", onClick = { vm.shiftMonth(1) }, look = ButtonLook.Light)
        }
        DiaryGrid(month, selected, pages, today, onSelect = vm::select, onSwipe = vm::shiftMonth)
        PixelText(
            when {
                streak > 1 -> "Sen viết $streak ngày liền rồi đó!"
                streak == 1 -> "Chuỗi bắt đầu rồi. Mai viết tiếp nhé."
                else -> "Chưa có chuỗi. Viết hôm nay để bắt đầu."
            },
            style = type.caption, color = Pal.Gold,
        )
        val page = pages[selected.toEpochDay()]
        when {
            page != null -> DiaryCard(page, hidden = hide, Modifier.fillMaxWidth().clickable { actions.onDiary(selected, null) })
            selected == today -> PixelText("Hôm nay Sen chưa viết.", style = type.body, color = Pal.InkSoft)
            selected.isAfter(today) -> PixelText("Chưa tới ngày này mà Sen.", style = type.body, color = Pal.InkSoft)
            else -> PixelText("Hôm đó Sen không viết. Nhật ký chỉ viết trong ngày thôi.", style = type.body, color = Pal.InkSoft)
        }
        PixelTextButton(
            if (todayPage?.hasContent == true) "Mở trang hôm nay" else "Viết hôm nay",
            onClick = { actions.onDiary(today, null) }, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun DiaryGrid(
    month: YearMonth,
    selected: LocalDate,
    pages: Map<Long, NoteFull>,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onSwipe: (Long) -> Unit,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val (start, _) = gridRange(month)
    var drag by remember { mutableStateOf(0f) }
    Column(
        Modifier.pointerInput(month) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (drag > 120) onSwipe(-1) else if (drag < -120) onSwipe(1)
                    drag = 0f
                },
                onHorizontalDrag = { _, dx -> drag += dx },
            )
        },
    ) {
        Row {
            for (d in WEEKDAYS_SHORT) PixelText(d, style = type.caption, color = Pal.InkSoft, align = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        val weeks = if (start.plusDays(35).month == month.month) 6 else 5
        for (w in 0 until weeks) {
            Row {
                for (col in 0 until 7) {
                    val day = start.plusDays(w * 7L + col)
                    val page = pages[day.toEpochDay()]
                    Box(
                        Modifier.weight(1f).aspectRatio(0.9f).clickable { onSelect(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val inMonth = day.month == month.month
                        Canvas(Modifier.fillMaxSize()) {
                            val u = m.ui.toFloat()
                            val frame = when {
                                day == selected -> Pal.Orange
                                day == today -> Pal.Gold
                                else -> Pal.Night
                            }
                            drawRect(frame, Offset(u / 2, u / 2), Size(size.width - u, size.height - u))
                            val fill = if (page?.mood != null) moodTint(page.mood).copy(alpha = 0.55f) else Pal.WoodDark
                            drawRect(fill, Offset(u * 1.5f, u * 1.5f), Size(size.width - 3 * u, size.height - 3 * u))
                            if (!inMonth) drawRect(Pal.Night.copy(alpha = 0.6f), Offset.Zero, size)
                        }
                        PixelText(
                            "${day.dayOfMonth}", style = type.caption,
                            color = if (inMonth) Pal.Cream else Pal.InkSoft,
                            modifier = Modifier.align(Alignment.TopStart).padding(start = m.unit * 2, top = m.unit),
                        )
                        when {
                            page?.mood != null -> MoodIcon(page.mood, scale = (m.ui - 1).coerceAtLeast(2), Modifier.padding(top = m.unit * 3))
                            page != null && page.hasContent -> PixelText("•", style = type.title, color = Pal.Cream)
                            day == today -> PixelText("!", style = type.title, color = Pal.Gold, modifier = Modifier.padding(top = m.unit * 3))
                        }
                    }
                }
            }
        }
    }
}

/** A diary page in a list: day, mood and, unless [hidden], the first lines and photo. */
@Composable
fun DiaryCard(n: NoteFull, hidden: Boolean, modifier: Modifier = Modifier) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Column(
        modifier.paperSheet(Pal.Cream, m.ui.toFloat()).padding(m.unit * 3),
        verticalArrangement = Arrangement.spacedBy(m.unit),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            MoodIcon(n.mood, scale = m.ui - 1, framed = true)
            Column {
                PixelText("Nhật ký · " + (n.day?.let { shortDate(it) } ?: ""), style = type.bodyBold, color = Pal.Ink)
                PixelText(n.mood?.label ?: "Chưa chọn tâm trạng", style = type.caption, color = Pal.InkSoft)
            }
        }
        if (hidden) {
            PixelText("(nội dung được che, chạm để đọc)", style = type.caption, color = Pal.InkSoft)
        } else {
            n.firstImage?.let { NotePhoto(it, maxSide = 400, maxHeight = m.unit * 50) }
            val text = n.blocks.filter { it.type != BlockType.IMAGE.name && it.text.isNotBlank() }.joinToString("\n") { it.text.trim() }
            if (text.isNotBlank()) PixelText(text, style = type.caption, color = Pal.Ink, maxLines = 4)
            if (n.tags.isNotEmpty()) PixelText(n.tags.joinToString(" ") { "#" + it.name }, style = type.caption, color = Pal.Wood)
        }
    }
}

// ------------------------------------------------------------------ diary page

class DiaryPageViewModel(c: AppContainer, val day: LocalDate, private val presetMood: Int?) : PageEditViewModel(c) {
    val today: LocalDate = LocalDate.now()
    val missing = MutableStateFlow(false)
    val summary: StateFlow<CalDay?> = c.calendar.days(day, day).map { it[day] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val catColor: StateFlow<Int> = c.settings.flow.map { it.catColor }.stateIn(viewModelScope, SharingStarted.Eagerly, 5)
    val reward = MutableStateFlow<DiaryReward?>(null)
    /** The cat's answer to the mood just picked on this visit. */
    val reaction = MutableStateFlow<String?>(null)
    private var ctx = PromptContext()
    private var pickedMood: Int? = null

    val preset get() = presetMood

    init {
        start()
    }

    override suspend fun load(): NoteFull? {
        ctx = promptContext()
        val p = c.notes.diaryFor(day, today, DiaryPrompts.pick(ctx, day.toEpochDay()))
        if (p == null) missing.value = true
        return p
    }

    private suspend fun promptContext(): PromptContext {
        if (day != today) return PromptContext()
        val d = c.calendar.days(today, today).first()[today]
        val moods = c.notes.moods.first()
        return PromptContext(
            overspent = d?.overLimit == true,
            spent = d?.spent ?: 0,
            allHabitsDone = d?.habitsAllDone == true,
            birthday = d?.events?.firstOrNull { it.label?.kind == "BIRTHDAY" }?.event?.title,
            yesterdayMood = moods[today.toEpochDay() - 1],
            weekend = today.dayOfWeek.value >= 6,
            firstEvent = d?.events?.firstOrNull { it.label?.kind != "BIRTHDAY" }?.event?.title,
            todosDone = d?.todos?.count { it.done } ?: 0,
        )
    }

    fun prompt(skip: Int) = DiaryPrompts.pick(ctx, day.toEpochDay(), skip)

    fun moodPicked(level: Int) = viewModelScope.launch {
        pickedMood = level
        val moods = c.notes.moods.first() + (day.toEpochDay() to level)
        reaction.value = CatLines.mood(level, low = day == today && MoodEngine.lowStreak(moods, today.toEpochDay()))
    }

    override fun onSaved(r: NoteSaved) {
        r.reward?.let { reward.value = it }
    }

    override fun onCleared() {
        super.onCleared()
        val level = pickedMood ?: return
        val coins = reward.value?.let { " " + CatLines.diaryCoins(it.coins, it.streak, it.bonus) }.orEmpty()
        c.events.post(CatEvent.Mood(level, (reaction.value ?: CatLines.mood(level)) + coins))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiaryPageScreen(vm: DiaryPageViewModel, onBack: () -> Unit) {
    val loaded by vm.page.collectAsState()
    val missing by vm.missing.collectAsState()
    val closed by vm.closed.collectAsState()
    LaunchedEffect(closed) { if (closed) onBack() }
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val title = if (vm.day == vm.today) "Nhật ký hôm nay" else "Nhật ký " + shortDate(vm.day)
    if (missing) {
        PixelScreen(title, onBack = onBack) {
            PixelText("Hôm đó Sen không viết. Trẫm chỉ cho viết nhật ký trong ngày thôi, để nó thật lòng.", style = type.body, color = Pal.InkSoft)
        }
        return
    }
    val p = loaded ?: return
    val allTags by vm.tags.collectAsState()
    val summary by vm.summary.collectAsState()
    val catColor by vm.catColor.collectAsState()
    val reward by vm.reward.collectAsState()
    val reaction by vm.reaction.collectAsState()

    val state = remember { EditorState(p.blocks) }
    var mood by remember { mutableStateOf(p.note.mood ?: vm.preset) }
    var skip by remember { mutableIntStateOf(0) }
    var prompt by remember { mutableStateOf(p.note.prompt.ifBlank { vm.prompt(0) }) }
    val tags = remember { mutableStateListOf<String>().apply { addAll(p.tags.map { it.name }) } }
    var tagging by remember { mutableStateOf(false) }
    var addingPhoto by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        // a mood picked on the home screen is saved right away
        val presetApplied = p.note.mood == null && vm.preset != null
        if (presetApplied) vm.moodPicked(vm.preset!!)
        snapshotFlow { Draft(p.note.copy(mood = mood, prompt = prompt), state.entities(), tags.toList()) }
            .let { if (presetApplied) it else it.drop(1) }
            .collect { vm.changed(it) }
    }

    PixelScreen(title, onBack = onBack, actions = { PixelTextButton("⋯", onClick = { menu = true }, look = ButtonLook.Light) }) {
        Column(
            Modifier.weight(1f).fillMaxWidth()
                .paperSheet(Pal.Cream, m.ui.toFloat())
                .padding(start = m.unit * 4, end = m.unit * 4, top = m.unit * 3, bottom = m.unit * 4)
                .tapToType(state)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(m.unit * 2),
        ) {
            PixelText(if (vm.day == vm.today) "Hôm nay Sen thấy sao?" else "Hôm đó Sen thấy sao?", style = type.bodyBold, color = Pal.Ink)
            Row(Modifier.fillMaxWidth()) {
                for (md in Mood.entries) {
                    Column(
                        Modifier.weight(1f).clickable {
                            mood = md.level
                            vm.moodPicked(md.level)
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MoodIcon(
                            md, scale = m.ui,
                            Modifier.background(if (mood == md.level) Pal.Orange else Pal.WoodDark)
                                .padding(m.unit)
                                .background(if (mood == md.level) moodTint(md) else Pal.Wood)
                                .padding(horizontal = m.unit * 2, vertical = m.unit),
                        )
                        PixelText(md.label, style = type.caption, color = if (mood == md.level) Pal.Ink else Pal.InkSoft, align = TextAlign.Center, maxLines = 1)
                    }
                }
            }
            summary?.let { DaySummary(it) }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                val anim = when {
                    reaction == null -> "sit"
                    (mood ?: 3) >= 4 -> "meow_sit"
                    (mood ?: 3) == 3 -> "sit"
                    (mood ?: 3) == 2 -> "sad"
                    else -> "lie"
                }
                SpriteView("cat/$catColor/$anim", scale = m.ui)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_interior", extraPadding = 1) {
                        PixelText(reaction ?: prompt, style = type.body, color = Pal.Ink)
                    }
                    if (reaction == null && vm.day == vm.today) {
                        PixelText("Câu khác ›", style = type.caption, color = Pal.Wood, modifier = Modifier.clickable {
                            skip++
                            prompt = vm.prompt(skip)
                        })
                    } else if (reaction != null) {
                        PixelText("Câu hỏi: $prompt", style = type.caption, color = Pal.InkSoft, maxLines = 2)
                    }
                }
            }
            reward?.let { PixelText(CatLines.diaryCoins(it.coins, it.streak, it.bonus), style = type.bodyBold, color = Pal.Orange) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                for (t in tags) PixelChip("#$t", selected = true) { tagging = true }
                PixelChip(if (tags.isEmpty()) "+ Tag" else "+") { tagging = true }
            }
            BlockEditor(state, "Sen viết vào đây...", onPhoto = { viewing = it })
        }
        EditorToolbar(state, onAddPhoto = { addingPhoto = true }, Modifier.padding(vertical = m.unit * 2))
    }

    PhotoAdder(addingPhoto, onDismiss = { addingPhoto = false }, onAdded = { state.addImage(it) })
    viewing?.let { PhotoViewer(it) { viewing = null } }
    if (tagging) {
        TagDialog(allTags, tags) {
            tags.clear()
            tags.addAll(it)
            tagging = false
        }
    }
    if (menu) {
        PixelDialog(onDismiss = { menu = false }) {
            PixelText("Xoá trang nhật ký này? Nó nằm trong thùng rác 30 ngày.", style = type.body, color = Pal.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Thôi", onClick = { menu = false }, look = ButtonLook.Light, modifier = Modifier.weight(1f))
                PixelTextButton("Xoá", onClick = { menu = false; vm.trash() }, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** What else happened that day, read only. */
@Composable
private fun DaySummary(d: CalDay) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val parts = buildList {
        add("Chi " + formatVndShort(d.spent))
        if (d.habitsDone > 0) add("Thói quen ${d.habitsDone} xong" + if (d.habitsAllDone) " (hết)" else "")
        val done = d.todos.count { it.done }
        if (done > 0) add("$done việc xong")
    }
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3", extraPadding = 1) {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
            PixelText(parts.joinToString(" · "), style = type.caption, color = Pal.Ink)
            val events = d.events.take(3).map { it.event.title }
            if (events.isNotEmpty()) PixelText("Lịch: " + events.joinToString(", "), style = type.caption, color = Pal.InkSoft, maxLines = 2)
        }
    }
}

// ------------------------------------------------------------------ search

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NoteSearchViewModel(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val results: StateFlow<List<NoteFull>> = query.debounce(250).flatMapLatest { c.notes.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val hide: StateFlow<Boolean> = c.settings.flow.map { it.diaryHidePreview }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
}

@Composable
fun NoteSearchScreen(vm: NoteSearchViewModel, onBack: () -> Unit, onNote: (Long) -> Unit, onDiary: (LocalDate) -> Unit) {
    val q by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val hide by vm.hide.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelScreen("Tìm trong sổ tay", onBack = onBack) {
        PixelTextField(q, { vm.query.value = it.take(60) }, Modifier.fillMaxWidth(), placeholder = "vd: da lat, qua me (không cần dấu)")
        if (q.isNotBlank() && results.isEmpty()) PixelText("Không thấy gì cả.", style = type.body, color = Pal.InkSoft, modifier = Modifier.padding(top = m.unit * 2))
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(m.unit * 3),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = m.unit * 3, bottom = m.unit * 4),
        ) {
            items(results, key = { it.id }) { n ->
                if (n.diary) DiaryCard(n, hidden = hide, Modifier.fillMaxWidth().clickable { onDiary(n.day!!) })
                else NoteCard(n, Modifier.fillMaxWidth().clickable { onNote(n.id) })
            }
        }
    }
}
