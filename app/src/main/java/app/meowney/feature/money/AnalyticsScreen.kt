package app.meowney.feature.money

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.money.AnalyticsEngine
import app.meowney.core.money.JarCode
import app.meowney.core.money.JarPlan
import app.meowney.core.money.Share
import app.meowney.core.money.Trend
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelChip
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.core.ui.RavenIcon
import app.meowney.core.ui.SegmentedTabs
import app.meowney.feature.entry.jarColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

data class AnalyticsUi(
    /** Months that have data, newest first. */
    val months: List<YearMonth> = emptyList(),
    val index: Int = 0,
    /** How many months the trend charts show: 3, 6 or 12. */
    val range: Int = 6,
    /** The share chart shows income instead of spending. */
    val income: Boolean = false,
    val trend: Trend? = null,
    val shares: List<Share> = emptyList(),
    val categories: List<Pair<Long, String>> = emptyList(),
    val categoryId: Long? = null,
    val categoryTrend: Trend? = null,
    val jars: List<JarPlan> = emptyList(),
    val loading: Boolean = true,
) {
    val month get() = months.getOrNull(index)
}

class AnalyticsViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(AnalyticsUi())
    val state: StateFlow<AnalyticsUi> = _state

    init {
        viewModelScope.launch {
            val months = c.reports.months(LocalDate.now())
            val cats = c.analytics.expenseCategories()
            _state.update { it.copy(months = months, categories = cats, categoryId = cats.firstOrNull()?.first) }
            reload()
        }
    }

    /** [step] +1 = older month, -1 = newer; only months with data can be reached. */
    fun go(step: Int) {
        val s = _state.value
        val i = s.index + step
        if (i in s.months.indices) change { it.copy(index = i) }
    }

    fun range(n: Int) = change { it.copy(range = n) }
    fun income(on: Boolean) = change { it.copy(income = on) }
    fun category(id: Long) = change { it.copy(categoryId = id) }

    private fun change(f: (AnalyticsUi) -> AnalyticsUi) {
        _state.update(f)
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val s = _state.value
        val month = s.month
        if (month == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        val a = c.analytics
        val trend = a.trend(month, s.range)
        val shares = a.shares(month, s.income)
        val category = s.categoryId?.let { a.categoryTrend(it, month, s.range) }
        val jars = a.jarPlans(month)
        _state.update { it.copy(trend = trend, shares = shares, categoryTrend = category, jars = jars, loading = false) }
    }
}

/** The analysis tab of the report screen: trends, shares, one category over time, jars against the plan. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalyticsTab(vm: AnalyticsViewModel, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val month = s.month
    if (month == null) {
        if (!s.loading) PixelText("Chưa có số liệu. Ghi vài khoản rồi quay lại nhé.", style = type.body, color = Pal.InkSoft)
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(m.unit * 4), contentPadding = PaddingValues(bottom = m.unit * 6)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PixelTextButton("<", onClick = { vm.go(1) }, enabled = s.index < s.months.lastIndex, look = ButtonLook.Plain)
                PixelText("Tháng ${month.monthValue}/${month.year}", style = type.bodyBold, align = TextAlign.Center, modifier = Modifier.weight(1f))
                PixelTextButton(">", onClick = { vm.go(-1) }, enabled = s.index > 0, look = ButtonLook.Plain)
            }
        }
        item {
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("THU SO VỚI CHI", Modifier.weight(1f))
                        RangePicker(s.range, vm::range)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        Dot(Pal.Green)
                        PixelText("Thu", style = type.caption, color = Pal.InkSoft)
                        Dot(Pal.Gold)
                        PixelText("Chi", style = type.caption, color = Pal.InkSoft)
                    }
                    val t = s.trend
                    if (t != null) {
                        MonthBars(t, listOf(Pal.Green to { f -> f.income }, Pal.Gold to { f -> f.expense }))
                        Hairline()
                        Row {
                            PixelText(avgLabel(t), style = type.caption, color = Pal.InkSoft, modifier = Modifier.weight(1f))
                            PixelText("Thu ${formatVndShort(t.avgIncome)} · Chi ${formatVndShort(t.avgExpense)}", style = type.caption, color = Pal.Ink)
                        }
                    }
                }
            }
        }
        item {
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                    Label(if (s.income) "THU THEO NGUỒN" else "CHI THEO DANH MỤC")
                    SegmentedTabs(listOf("Chi", "Thu"), if (s.income) 1 else 0) { vm.income(it == 1) }
                    if (s.shares.isEmpty()) {
                        PixelText(if (s.income) "Tháng này chưa có khoản thu nào." else "Tháng này chưa tiêu khoản nào.", style = type.body, color = Pal.InkSoft)
                    } else {
                        ShareBar(s.shares)
                        for (share in s.shares) ShareRow(share)
                        PixelText("Tổng ${formatVnd(s.shares.sumOf { it.amount })}", style = type.caption, color = Pal.InkSoft)
                    }
                }
            }
        }
        if (s.categories.isNotEmpty()) item {
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("XU HƯỚNG MỘT DANH MỤC", Modifier.weight(1f))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit * 2), verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                        for ((id, name) in s.categories.take(8)) PixelChip(name, selected = id == s.categoryId) { vm.category(id) }
                    }
                    val t = s.categoryTrend
                    if (t != null) {
                        MonthBars(t, listOf(Pal.Gold to { f -> f.expense }))
                        Hairline()
                        Row {
                            PixelText(avgLabel(t), style = type.caption, color = Pal.InkSoft, modifier = Modifier.weight(1f))
                            PixelText(formatVnd(t.avgExpense), style = type.caption, color = Pal.Ink)
                        }
                    }
                }
            }
        }
        if (s.jars.isNotEmpty()) item {
            PixelPanel(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
                    Label("LỌ: KẾ HOẠCH VÀ THỰC TẾ")
                    for (j in s.jars) JarPlanRow(j)
                    PixelText(
                        "Vạch đen là % thu nhập dự tính cho lọ. Thanh màu là % thu nhập tháng này đã tiêu từ lọ; với lọ tiết kiệm là % đã để vào.",
                        style = type.caption, color = Pal.InkSoft,
                    )
                }
            }
        }
    }
}

private fun avgLabel(t: Trend) = if (t.activeMonths <= 1) "Mới có 1 tháng số liệu" else "Trung bình ${t.activeMonths} tháng"

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    PixelText(text, style = LocalPixelType.current.caption.copy(fontWeight = LocalPixelType.current.bodyBold.fontWeight), color = Pal.GoldDeep, modifier = modifier)
}

@Composable
private fun Dot(color: Color) {
    val m = LocalPixelMetrics.current
    Box(Modifier.size(m.unit * 5).background(color, CircleShape))
}

@Composable
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pal.Line))
}

@Composable
private fun RangePicker(range: Int, onPick: (Int) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(Modifier.background(Pal.Well, RoundedCornerShape(50)).padding(m.unit)) {
        for (n in listOf(3, 6, 12)) {
            val on = n == range
            Box(
                Modifier
                    .then(if (on) Modifier.background(Pal.Gold, RoundedCornerShape(50)) else Modifier)
                    .clickable { onPick(n) }
                    .padding(horizontal = m.unit * 4, vertical = m.unit * 2),
            ) {
                PixelText("$n", style = type.caption, color = if (on) Pal.OnPrimary else Pal.InkSoft)
            }
        }
    }
}

/** One group of bars per month, all on the same scale, month labels underneath. */
@Composable
private fun MonthBars(t: Trend, series: List<Pair<Color, (app.meowney.core.money.MonthFlow) -> Long>>) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val top = t.months.maxOf { f -> series.maxOf { it.second(f) } }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        Row {
            PixelText("cao nhất ${formatVndShort(top)}", style = type.caption, color = Pal.InkFaint)
        }
        Canvas(Modifier.fillMaxWidth().height(m.unit * 60)) {
            val n = t.months.size
            val slot = size.width / n
            val barW = (slot * 0.62f / series.size).coerceAtMost(14.dp.toPx())
            val gap = 3.dp.toPx().coerceAtMost(barW / 3)
            val groupW = barW * series.size + gap * (series.size - 1)
            val base = size.height - 2.dp.toPx()
            drawRect(Pal.Line, Offset(0f, base), Size(size.width, 2.dp.toPx()))
            t.months.forEachIndexed { i, f ->
                val x0 = i * slot + (slot - groupW) / 2
                series.forEachIndexed { k, (color, value) ->
                    val v = value(f)
                    if (v > 0) {
                        val h = (v.toFloat() / top * base).coerceAtLeast(2.dp.toPx())
                        drawRoundRect(color, Offset(x0 + k * (barW + gap), base - h), Size(barW, h), CornerRadius(barW / 3))
                    }
                }
            }
        }
        Row {
            for (f in t.months) {
                PixelText("T${f.month.monthValue}", style = type.caption, color = Pal.InkSoft, align = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** A share's colour: its own steady slot, so a category looks the same every month. */
private fun shareColor(s: Share) = Pal.Series[AnalyticsEngine.slot(s.key, Pal.Series.size)]

@Composable
private fun ShareBar(shares: List<Share>) {
    val m = LocalPixelMetrics.current
    val total = shares.sumOf { it.amount }.coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(m.unit * 9)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Pal.Line, Offset.Zero, size, r)
        var x = 0f
        shares.forEachIndexed { i, s ->
            val w = size.width * s.amount / total
            // round only the outer ends so the slices read as one bar
            if (i == 0 || i == shares.lastIndex) {
                drawRoundRect(shareColor(s), Offset(x, 0f), Size(w, size.height), r)
                val inner = if (i == 0) Offset(x + w / 2, 0f) else Offset(x, 0f)
                if (shares.size > 1) drawRect(shareColor(s), inner, Size(w / 2, size.height))
            } else {
                drawRect(shareColor(s), Offset(x, 0f), Size(w, size.height))
            }
            x += w
        }
    }
}

@Composable
private fun ShareRow(s: Share) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 3)) {
        Dot(shareColor(s))
        if (s.icon >= 0) RavenIcon(s.icon, scale = (m.ui - 2).coerceAtLeast(2))
        Column(Modifier.weight(1f)) {
            PixelText(s.label, style = type.body, maxLines = 1)
            val change = s.change
            if (change != null && change != 0L) {
                PixelText(
                    "${if (change > 0) "tăng" else "giảm"} ${formatVndShort(abs(change))} so với tháng trước",
                    style = type.caption, color = Pal.InkSoft, maxLines = 1,
                )
            }
        }
        Box(Modifier.background(Pal.GreenDeep, RoundedCornerShape(6.dp)).padding(horizontal = m.unit * 2, vertical = m.unit)) {
            PixelText("${s.percent}%", style = type.caption, color = Pal.Card)
        }
        PixelText(formatVndShort(s.amount), style = type.bodyBold, align = TextAlign.End, maxLines = 1, modifier = Modifier.widthIn(min = m.unit * 30))
    }
}

@Composable
private fun JarPlanRow(j: JarPlan) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val color = runCatching { jarColor(JarCode.valueOf(j.code)) }.getOrDefault(Pal.Sky)
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelText(j.name, style = type.body, maxLines = 1, modifier = Modifier.weight(1f))
            PixelText(
                "${j.actual}% / ${j.planned}%",
                style = if (j.over) type.bodyBold else type.caption, color = if (j.over) Pal.Red else Pal.InkSoft,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(m.unit * 6)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Pal.Well, Offset.Zero, size, r)
            val w = size.width * j.actual.coerceIn(0, 100) / 100f
            if (w > 0f) drawRoundRect(color, Offset.Zero, Size(w.coerceAtLeast(size.height), size.height), r)
            val x = size.width * j.planned.coerceIn(0, 100) / 100f
            drawRect(Pal.Ink, Offset(x - 1.dp.toPx(), -2.dp.toPx()), Size(2.dp.toPx(), size.height + 4.dp.toPx()))
        }
    }
}
