package app.meowney.feature.entry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.meowney.core.format.formatVnd
import app.meowney.core.money.BorrowPlan
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelDialog
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton

/**
 * "Lọ X thiếu Y": shows who lends what. Savings jars are offered only on request, and the
 * cat asks once more before touching them.
 */
@Composable
fun BorrowDialog(
    pending: PendingExpense,
    jarNames: Map<Long, String>,
    onConfirm: (BorrowPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    var useSavings by remember { mutableStateOf(false) }
    var askSavings by remember { mutableStateOf(false) }
    val plan = if (useSavings && pending.savingsPlan != null) pending.savingsPlan else pending.plan
    val jarName = jarNames[pending.draft.jarId] ?: "Lọ"

    PixelDialog(onDismiss) {
        if (askSavings) {
            PixelText("Sen chắc chưa?", style = type.title)
            PixelText(
                "Tiền trong lọ tiết kiệm là để dành đó. Trẫm cho mượn lần này, nhưng khoản thu sau sẽ trả lại lọ trước nhé.",
                style = type.body,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelTextButton("Chắc rồi", onClick = {
                    useSavings = true
                    askSavings = false
                })
                PixelTextButton("Thôi", onClick = { askSavings = false }, look = ButtonLook.Light)
            }
            return@PixelDialog
        }
        PixelText("$jarName thiếu ${formatVnd(pending.shortfall)}", style = type.title)
        PixelText("Mượn tạm từ lọ khác, khoản thu sau sẽ trả lại:", style = type.caption, color = Pal.InkSoft)
        Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
            for ((source, amount) in plan.sources) {
                Row(Modifier.fillMaxWidth()) {
                    PixelText("• ${jarNames[source] ?: "?"}", style = type.body, modifier = Modifier.weight(1f))
                    PixelText(formatVnd(amount), style = type.bodyBold)
                }
            }
            if (plan.uncovered > 0) {
                PixelText(
                    "Các lọ không đủ, $jarName sẽ âm ${formatVnd(plan.uncovered)}.",
                    style = type.body,
                    color = Pal.Red,
                )
            }
        }
        if (!useSavings && pending.savingsPlan != null) {
            PixelTextButton("Dùng cả lọ tiết kiệm", onClick = { askSavings = true }, look = ButtonLook.Light, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
            PixelTextButton("Đồng ý", onClick = { onConfirm(plan) })
            PixelTextButton("Huỷ", onClick = onDismiss, look = ButtonLook.Light)
        }
    }
}
