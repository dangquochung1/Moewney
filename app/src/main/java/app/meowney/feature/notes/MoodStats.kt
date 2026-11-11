package app.meowney.feature.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.format.formatVndShort
import app.meowney.core.notes.Mood
import app.meowney.core.notes.MoodMonth
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelProgress
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

class MoodStatsViewModel(private val c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(YearMonth.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<Pair<MoodMonth, Map<Long, Int>>?> = combine(month, c.notes.moods) { m, moods -> m to moods }
        .mapLatest { (m, moods) ->
            val from = m.atDay(1).toEpochDay()
            val to = m.atEndOfMonth().toEpochDay()
            c.notes.moodMonth(m) to moods.filterKeys { it in from..to }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun shift(by: Long) {
        month.value = month.value.plusMonths(by)
    }
}

@Composable
fun MoodStatsScreen(vm: MoodStatsViewModel, onBack: () -> Unit) {
    val month by vm.month.collectAsState()
    val data by vm.stats.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    PixelScreen("Tâm trạng", onBack = onBack) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelTextButton("<", onClick = { vm.shift(-1) }, look = ButtonLook.Light)
                PixelText("Tháng ${month.monthValue}/${month.year}", style = type.bodyBold, color = Pal.Ink, align = TextAlign.Center, modifier = Modifier.weight(1f))
                PixelTextButton(">", onClick = { vm.shift(1) }, look = ButtonLook.Light)
            }
            val (s, days) = data ?: return@Column
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelText("Sen viết ${s.written}/${s.days} ngày", style = type.bodyBold, color = Pal.Ink)
                    MoodLine(month, days)
                    val most = s.counts.maxOrNull()?.coerceAtLeast(1) ?: 1
                    for (md in Mood.entries.reversed()) {
                        val n = s.counts[md.level - 1]
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                            MoodIcon(md, scale = (m.ui - 2).coerceAtLeast(2), framed = true)
                            PixelText(md.label, style = type.caption, color = Pal.Ink, modifier = Modifier.width(m.unit * 46), maxLines = 1)
                            PixelProgress(n / most.toFloat(), Modifier.weight(1f), color = moodTint(md))
                            PixelText("$n", style = type.caption, color = Pal.Ink, modifier = Modifier.width(m.unit * 8), align = TextAlign.End)
                        }
                    }
                }
            }
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                    PixelText("Ngày vui và ngày buồn", style = type.bodyBold, color = Pal.Ink)
                    Compare("Ngày vui", s.spentGood, s.habitsGood)
                    Compare("Ngày buồn", s.spentBad, s.habitsBad)
                    insight(s)?.let { PixelText(it, style = type.caption, color = Pal.Wood) }
                }
            }
            if (s.written == 0) PixelText("Tháng này chưa có trang nhật ký nào.", style = type.body, color = Pal.InkSoft)
        }
    }
}

@Composable
private fun Compare(label: String, spent: Long?, habits: Int?) {
    val type = LocalPixelType.current
    PixelText(
        "$label: " + if (spent == null) "chưa có ngày nào" else "chi TB ${formatVndShort(spent)}" + (habits?.let { " · thói quen $it%" } ?: ""),
        style = type.caption, color = Pal.Ink,
    )
}

/** One line from the cat when the numbers say something. */
private fun insight(s: MoodMonth): String? = when {
    s.spentGood != null && s.spentBad != null && s.spentBad > s.spentGood * 13 / 10 ->
        "Ngày buồn Sen hay tiêu nhiều hơn. Trẫm để ý đó nha."
    s.habitsGood != null && s.habitsBad != null && s.habitsGood >= s.habitsBad + 20 ->
        "Ngày làm đủ thói quen thì Sen vui hơn hẳn. Trẫm nói rồi mà."
    s.spentGood != null && s.spentBad != null && s.spentGood > s.spentBad * 13 / 10 ->
        "Ngày vui Sen chịu chi hơn. Vui là được, nhưng nhớ hạn mức nhé."
    else -> null
}

/** Each day of the month as a column; the square sits higher the happier the day. */
@Composable
private fun MoodLine(month: YearMonth, days: Map<Long, Int>) {
    val m = LocalPixelMetrics.current
    val n = month.lengthOfMonth()
    val first = month.atDay(1).toEpochDay()
    Canvas(Modifier.fillMaxWidth().height(m.unit * 30)) {
        val u = m.ui.toFloat()
        val colW = size.width / n
        val rowH = size.height / 5
        for (i in 0 until 5) drawRect(Pal.Paper, Offset(0f, i * rowH + rowH / 2), Size(size.width, u / 2))
        for (d in 0 until n) {
            val mood = Mood.of(days[first + d]) ?: continue
            val y = (5 - mood.level) * rowH
            val w = (colW - u).coerceAtLeast(u)
            drawRect(Pal.WoodDark, Offset(d * colW, y + rowH / 2 - w / 2 - u), Size(w + u, w + u))
            drawRect(moodTint(mood), Offset(d * colW + u / 2, y + rowH / 2 - w / 2 - u / 2), Size(w, w))
        }
    }
}
