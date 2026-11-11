package app.meowney.feature.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.Bar
import app.meowney.core.data.MonthReport
import app.meowney.core.data.ReportData
import app.meowney.core.data.yearMonthOf
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.money.JarCode
import app.meowney.core.money.MonthScore
import app.meowney.core.sound.Sfx
import app.meowney.core.sprite.drawSprite
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.LocalSprites
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelProgress
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.RavenIcon
import app.meowney.core.ui.SpeechBubble
import app.meowney.feature.entry.jarColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class ReportUi(
    val months: List<YearMonth> = emptyList(),
    val index: Int = 0,
    val report: MonthReport? = null,
    val loading: Boolean = true,
    /** Coins just won from the chest (plays the opening animation). */
    val justClaimed: Int = 0,
)

class ReportViewModel(private val c: AppContainer, start: Int) : ViewModel() {
    private val _state = MutableStateFlow(ReportUi())
    val state: StateFlow<ReportUi> = _state

    init {
        viewModelScope.launch {
            val today = LocalDate.now()
            val months = c.reports.months(today)
            val wanted = if (start > 0) yearMonthOf(start) else YearMonth.from(today)
            val index = months.indexOf(wanted).coerceAtLeast(0)
            _state.update { it.copy(months = months, index = index) }
            load(index)
        }
    }

    /** [step] +1 = older month, -1 = newer. */
    fun go(step: Int) {
        val s = _state.value
        val i = s.index + step
        if (i in s.months.indices) viewModelScope.launch { load(i) }
    }

    private suspend fun load(i: Int) {
        val month = _state.value.months.getOrNull(i)
        _state.update { it.copy(index = i, loading = true, justClaimed = 0) }
        val r = month?.let { c.reports.load(it, LocalDate.now()) }
        _state.update { it.copy(report = r, loading = false) }
    }

    fun claim() = viewModelScope.launch {
        val r = _state.value.report ?: return@launch
        val coins = c.reports.claim(r.month, LocalDate.now())
        if (coins > 0) {
            c.sfx.play(Sfx.Sound.Gold)
            c.events.post(CatEvent.Coins(coins, "Phiếu điểm tháng ${r.month.monthValue}: hạng ${r.data.score.grade}!"))
            val updated = c.reports.load(r.month, LocalDate.now())
            _state.update { it.copy(report = updated, justClaimed = coins) }
        }
    }
}

private fun catLine(score: MonthScore, final: Boolean): String = if (!final) {
    "Tháng này đang được hạng ${score.grade}. Cố lên Sen!"
} else {
    when (score.grade) {
        "S" -> "Sen giỏi quá! Trẫm thưởng to."
        "A" -> "Khá lắm Sen. Trẫm hài lòng."
        "B" -> "Tạm được. Tháng sau cố hơn nha."
        else -> "Hừm... Trẫm vẫn thương Sen. Tháng sau làm lại!"
    }
}

@Composable
fun ReportScreen(vm: ReportViewModel, catColor: Int, onBack: () -> Unit) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelScreen("Tổng kết", onBack = onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PixelTextButton("<", onClick = { vm.go(1) }, enabled = s.index < s.months.lastIndex, look = ButtonLook.Light)
            val month = s.months.getOrNull(s.index)
            PixelText(
                month?.let { "Tháng ${it.monthValue}/${it.year}" + if (s.report?.final == false) " (tạm tính)" else "" } ?: "",
                style = type.bodyBold, color = Pal.Ink, modifier = Modifier.weight(1f).padding(horizontal = m.unit * 2),
            )
            PixelTextButton(">", onClick = { vm.go(-1) }, enabled = s.index > 0, look = ButtonLook.Light)
        }
        Spacer(Modifier.height(m.unit * 2))
        val r = s.report
        if (r == null) {
            if (!s.loading) PixelText("Chưa có số liệu tháng này.", style = type.body, color = Pal.InkSoft)
            return@PixelScreen
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            item { GradeCard(r, catColor) }
            if (r.final) item { ChestRow(r, s.justClaimed, onOpen = vm::claim) }
            item { Totals(r.data) }
            if (r.data.byJar.isNotEmpty()) item {
                ChartPanel("Chi theo lọ") {
                    HBars(r.data.byJar) { bar -> runCatching { jarColor(JarCode.valueOf(bar.code)) }.getOrDefault(Pal.Sky) }
                }
            }
            if (r.data.byCategory.isNotEmpty()) item {
                ChartPanel("Chi nhiều nhất") { HBars(r.data.byCategory) { Pal.Orange } }
            }
            item { ChartPanel("Chi theo ngày") { DayChart(r.data) } }
            r.data.mood?.let { mood -> item { ChartPanel("Tâm trạng") { MoodRow(mood) } } }
        }
    }
}

@Composable
private fun GradeCard(r: MonthReport, catColor: Int) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val score = r.data.score
    val d = r.data
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
        Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpriteView("cat/$catColor/${if (score.grade == "S" || score.grade == "A") "meow_sit" else "sit"}", scale = m.ui * 2)
                Spacer(Modifier.width(m.unit * 2))
                Column(Modifier.weight(1f)) {
                    PixelText("HẠNG", style = type.caption, color = Pal.InkSoft)
                    PixelText(score.grade, style = type.display.copy(fontSize = type.display.fontSize * 2), color = gradeColor(score.grade))
                    PixelText("${(score.total * 100).toInt()} điểm", style = type.caption, color = Pal.InkSoft)
                }
            }
            SpeechBubble(catLine(score, r.final), Modifier.fillMaxWidth())
            Criterion("Đều đặn ghi sổ", score.logging, "${d.loggedDays}/${d.days} ngày có ghi")
            Criterion(
                "Để dành được", score.saving,
                if (d.planned > 0) "${formatVndShort(d.saved)} / ${formatVndShort(d.planned)} dự tính" else "chưa có khoản thu nào",
            )
            Criterion("Giữ trong hạn mức", score.limit, if (d.statDays > 0) "${d.daysWithin}/${d.statDays} ngày không vượt" else "chưa có ngày nào")
            Criterion(
                "Không nợ lọ", score.noDebt,
                when {
                    d.hasNegativeJar -> "có lọ bị âm"
                    d.hasJarLoans -> "còn nợ giữa các lọ"
                    else -> "sạch nợ"
                },
            )
        }
    }
}

private fun gradeColor(g: String) = when (g) {
    "S" -> Pal.Gold
    "A" -> Pal.Green
    "B" -> Pal.Sky
    else -> Pal.Orange
}

@Composable
private fun Criterion(label: String, value: Float, detail: String) {
    val type = LocalPixelType.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(label, style = type.bodyBold, modifier = Modifier.weight(1f))
            PixelText("${(value * 100).toInt()}%", style = type.bodyBold)
        }
        PixelProgress(value, color = if (value >= 0.75f) Pal.Green else if (value >= 0.5f) Pal.Gold else Pal.Orange)
        PixelText(detail, style = type.caption, color = Pal.InkSoft)
    }
}

@Composable
private fun ChestRow(r: MonthReport, justClaimed: Int, onOpen: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val coins = r.kept?.coins ?: 0
    PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_interior") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                justClaimed > 0 -> SpriteView("chest/chest_open", scale = m.ui * 3, once = true)
                r.claimed -> SpriteView("chest/chest_open", scale = m.ui * 3, frame = 3)
                else -> SpriteView("chest/chest", scale = m.ui * 3)
            }
            Spacer(Modifier.width(m.unit * 3))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(m.unit)) {
                if (r.claimed) {
                    PixelText(if (justClaimed > 0) "+$justClaimed xu!" else "Đã nhận $coins xu", style = type.title, color = Pal.Gold)
                    PixelText("Xu để dành mua đồ cho Hoàng thượng.", style = type.caption, color = Pal.InkSoft)
                } else {
                    PixelText("Rương thưởng hạng ${r.data.score.grade}", style = type.bodyBold, color = Pal.Ink)
                    PixelTextButton("Mở rương (+$coins xu)", onClick = onOpen)
                }
            }
        }
    }
}

@Composable
private fun Totals(d: ReportData) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        for ((label, value, color) in listOf(
            Triple("Thu", d.income, Pal.Green),
            Triple("Chi", d.expense, Pal.Ink),
            Triple("Để dành", d.saved, Pal.Gold),
        )) {
            Column(Modifier.weight(1f)) {
                PixelText(label, style = type.caption, color = Pal.InkSoft)
                PixelText(formatVndShort(value), style = type.bodyBold, color = color)
            }
        }
    }
}

@Composable
private fun ChartPanel(title: String, content: @Composable () -> Unit) {
    val m = LocalPixelMetrics.current
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        PixelText(title.uppercase(), style = LocalPixelType.current.bodyBold, color = Pal.Gold)
        content()
    }
}

/** The diary moods of the month: days written and the share of good, ok and bad days (not graded). */
@Composable
private fun MoodRow(mood: app.meowney.core.notes.MoodSummary) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        PixelText("Sen viết nhật ký ${mood.written} ngày", style = type.body, color = Pal.Ink)
        for ((label, n, md) in listOf(
            Triple("Vui", mood.good, app.meowney.core.notes.Mood.GOOD),
            Triple("Bình thường", mood.ok, app.meowney.core.notes.Mood.OK),
            Triple("Buồn", mood.bad, app.meowney.core.notes.Mood.BAD),
        )) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                app.meowney.feature.notes.MoodIcon(md, scale = (m.ui - 2).coerceAtLeast(2), framed = true)
                Column(Modifier.weight(1f)) {
                    Row {
                        PixelText(label, style = type.body, color = Pal.Ink, modifier = Modifier.weight(1f))
                        PixelText("$n ngày", style = type.caption, color = Pal.InkSoft)
                    }
                    PixelProgress(n.toFloat() / mood.written.coerceAtLeast(1), color = app.meowney.feature.notes.moodTint(md), heightPx = 4)
                }
            }
        }
    }
}

/** Horizontal bars: label + amount above a bar scaled to the biggest one. */
@Composable
private fun HBars(bars: List<Bar>, color: (Bar) -> Color) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val max = bars.maxOf { it.amount }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        for (b in bars) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (b.icon >= 0) {
                    RavenIcon(b.icon)
                    Spacer(Modifier.width(m.unit * 2))
                }
                Column(Modifier.weight(1f)) {
                    Row {
                        PixelText(b.label, style = type.body, color = Pal.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                        PixelText(formatVnd(b.amount), style = type.caption, color = Pal.InkSoft)
                    }
                    PixelProgress(b.amount.toFloat() / max, color = color(b), heightPx = 4)
                }
            }
        }
    }
}

/** One pixel column per day of the month; the biggest day is marked in orange. */
@Composable
private fun DayChart(d: ReportData) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val max = d.byDay.maxOrNull()?.coerceAtLeast(1) ?: 1
    val peak = d.byDay.indexOf(d.byDay.maxOrNull() ?: 0)
    val first = LocalDate.ofEpochDay(d.from).withDayOfMonth(1).toEpochDay()
    val today = (d.to - first).toInt()
    val start = (d.from - first).toInt() // before this the app was not in use yet
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        Canvas(Modifier.fillMaxWidth().height(m.dp(40 * m.ui))) {
            val u = m.ui.toFloat()
            val n = d.byDay.size
            val col = ((size.width / u).toInt() / n).coerceAtLeast(2) // art pixels per day incl. gap
            val left = ((size.width / u).toInt() - col * n) / 2 * u
            val floor = size.height - u
            drawRect(Pal.WoodDark, Offset(0f, floor), Size(size.width, u))
            d.byDay.forEachIndexed { i, v ->
                val x = left + i * col * u
                if (v > 0) {
                    val h = ((v.toFloat() / max) * 38).toInt().coerceAtLeast(1) * u
                    drawRect(if (i == peak) Pal.Orange else Pal.Sky, Offset(x, floor - h), Size((col - 1) * u, h))
                } else if (i in start..today) {
                    drawRect(Pal.Green, Offset(x, floor - u), Size((col - 1) * u, u)) // a no-spend day
                }
            }
        }
        Row {
            PixelText("1", style = type.caption, color = Pal.InkSoft, modifier = Modifier.weight(1f))
            if (d.byDay.getOrNull(peak) ?: 0 > 0) {
                PixelText("nhiều nhất: ngày ${peak + 1} · ${formatVndShort(d.byDay[peak])}", style = type.caption, color = Pal.Orange)
            }
            PixelText("  ${d.byDay.size}", style = type.caption, color = Pal.InkSoft)
        }
    }
}

/**
 * A sprite in the layout. Loops its frames unless [once] (stops on the last frame) or a
 * fixed [frame] is given.
 */
@Composable
fun SpriteView(name: String, scale: Int, frame: Int = -1, once: Boolean = false) {
    val sprites = LocalSprites.current
    val sprite = sprites.getOrNull(name) ?: return
    val m = LocalPixelMetrics.current
    var f by remember(name, frame, once) { mutableIntStateOf(if (frame >= 0) frame else 0) }
    if (frame < 0 && sprite.frames > 1) {
        LaunchedEffect(name, once) {
            while (true) {
                delay(1000L / sprite.entry.fps.coerceAtLeast(1))
                if (once && f == sprite.frames - 1) break
                f = (f + 1) % sprite.frames
            }
        }
    }
    Box(Modifier.size(m.dp(sprite.width * scale), m.dp(sprite.height * scale))) {
        Canvas(Modifier.size(m.dp(sprite.width * scale), m.dp(sprite.height * scale))) {
            drawSprite(sprite, f, size.width / 2f, size.height, scale = scale)
        }
    }
}
