package app.meowney.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import java.time.YearMonth

val WEEKDAYS_SHORT = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")

/** "T3 10/11" (with the year when it is not this year). */
fun shortDate(d: LocalDate, today: LocalDate = LocalDate.now()): String {
    val wd = WEEKDAYS_SHORT[d.dayOfWeek.value - 1]
    return if (d.year == today.year) "$wd %02d/%02d".format(d.dayOfMonth, d.monthValue) else "$wd %02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year)
}

fun hhmm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

/** A month grid to pick a day. [allowNone] adds "Không có ngày". */
@Composable
fun PixelDatePicker(initial: LocalDate?, allowNone: Boolean = false, onPick: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val today = LocalDate.now()
    var month by remember { mutableStateOf(YearMonth.from(initial ?: today)) }
    PixelDialog(onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PixelTextButton("<", onClick = { month = month.minusMonths(1) }, look = ButtonLook.Light)
            PixelText("Tháng ${month.monthValue}/${month.year}", style = type.title, align = TextAlign.Center, modifier = Modifier.weight(1f))
            PixelTextButton(">", onClick = { month = month.plusMonths(1) }, look = ButtonLook.Light)
        }
        Row {
            for (d in WEEKDAYS_SHORT) PixelText(d, style = type.caption, color = Pal.InkSoft, align = TextAlign.Center, modifier = Modifier.weight(1f))
        }
        val lead = month.atDay(1).dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (week in 0 until (cells + 6) / 7) {
                Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
                    for (col in 0 until 7) {
                        val n = week * 7 + col - lead + 1
                        Box(Modifier.weight(1f).aspectRatio(1.2f), contentAlignment = Alignment.Center) {
                            if (n in 1..month.lengthOfMonth()) {
                                val day = month.atDay(n)
                                val selected = day == initial
                                Canvas(Modifier.fillMaxSize().clickable { onPick(day) }) {
                                    val u = m.ui.toFloat()
                                    drawRect(if (day == today) Pal.Gold else Pal.WoodDark)
                                    drawRect(if (selected) Pal.Orange else Pal.Cream, Offset(u, u), Size(size.width - 2 * u, size.height - 2 * u))
                                }
                                PixelText("$n", style = type.body, color = if (selected) Pal.Cream else Pal.Ink)
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelTextButton("Hôm nay", onClick = { onPick(today) }, look = ButtonLook.Light)
            PixelTextButton("Mai", onClick = { onPick(today.plusDays(1)) }, look = ButtonLook.Light)
            if (allowNone) PixelTextButton("Không có ngày", onClick = { onPick(null) }, look = ButtonLook.Light)
        }
    }
}

/** Hour chips + minute steps. [allowNone] adds "Không giờ". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PixelTimePicker(initial: Int?, allowNone: Boolean = false, onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var minute by remember { mutableStateOf(initial ?: (9 * 60)) }
    PixelDialog(onDismiss) {
        PixelText(hhmm(minute), style = type.display, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        PixelText("Giờ", style = type.caption, color = Pal.InkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for (h in 0..23) PixelChip("%02d".format(h), selected = minute / 60 == h) { minute = h * 60 + minute % 60 }
        }
        PixelText("Phút", style = type.caption, color = Pal.InkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit), verticalAlignment = Alignment.CenterVertically) {
            for (mm in listOf(0, 15, 30, 45)) PixelChip("%02d".format(mm), selected = minute % 60 == mm) { minute = minute / 60 * 60 + mm }
            PixelTextButton("-5", onClick = { minute = (minute - 5 + 1440) % 1440 }, look = ButtonLook.Light)
            PixelTextButton("+5", onClick = { minute = (minute + 5) % 1440 }, look = ButtonLook.Light)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelTextButton("Chọn", onClick = { onPick(minute) })
            if (allowNone) PixelTextButton("Không giờ", onClick = { onPick(null) }, look = ButtonLook.Light)
        }
    }
}
