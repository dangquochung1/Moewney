package app.meowney.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import app.meowney.MainActivity
import app.meowney.container
import app.meowney.core.data.TodaySnapshot
import app.meowney.core.data.TxnDraft
import app.meowney.core.data.todaySnapshot
import app.meowney.core.data.todaySnapshots
import app.meowney.core.format.formatVndShort
import app.meowney.core.money.BorrowPlan
import app.meowney.feature.entry.ExpenseSaver
import java.time.LocalDate

/** Intent extra telling MainActivity to open the entry screen. */
const val EXTRA_OPEN = "open"
const val OPEN_ENTRY = "entry"
const val OPEN_POMODORO = "pomodoro"
const val EXTRA_HABIT = "habit"

private fun entryIntent(context: Context) =
    Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, OPEN_ENTRY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** 2×2: cat + what is left today; tap opens the entry screen. */
class MeowneySmallWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val first = todaySnapshot(c)
        provideContent {
            // a Glance session stays open for a while and only recomposes on update(),
            // so the numbers must come from a live flow, not from provideGlance
            val snap by remember { todaySnapshots(c) }.collectAsState(first)
            Small(snap)
        }
    }

    @Composable
    private fun Small(s: TodaySnapshot) {
        val context = LocalContext.current
        val c = context.container
        val size = LocalSize.current
        val d = context.resources.displayMetrics.density
        val card = WidgetArt.card(c.sprites, c.typeface, s, (size.width.value * d).toInt(), (size.height.value * d).toInt(), wide = false)
        Image(
            ImageProvider(card), contentDescription = "Meowney",
            contentScale = ContentScale.FillBounds,
            modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity(entryIntent(context))),
        )
    }
}

/** 4×2: cat + left today + 3 one-tap expenses + "+". */
class MeowneyWideWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = context.container
        val first = todaySnapshot(c)
        provideContent {
            // a Glance session stays open for a while and only recomposes on update(),
            // so the numbers must come from a live flow, not from provideGlance
            val snap by remember { todaySnapshots(c) }.collectAsState(first)
            Wide(snap)
        }
    }

    @Composable
    private fun Wide(s: TodaySnapshot) {
        val context = LocalContext.current
        val c = context.container
        val size = LocalSize.current
        val d = context.resources.displayMetrics.density
        val rowH = 44.dp
        val cardH = (size.height - rowH - 4.dp).coerceAtLeast(60.dp)
        val px = { dp: Float -> (dp * d).toInt() }
        val card = WidgetArt.card(c.sprites, c.typeface, s, px(size.width.value), px(cardH.value), wide = true)
        val plusW = if (s.templates.isEmpty()) size.width else 52.dp
        val chipW = if (s.templates.isEmpty()) 0.dp else (size.width - plusW) / s.templates.size
        Column(GlanceModifier.fillMaxSize()) {
            Image(
                ImageProvider(card), contentDescription = "Meowney",
                contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.size(size.width, cardH).clickable(actionStartActivity(entryIntent(context))),
            )
            Row(GlanceModifier.padding(top = 4.dp)) {
                for (t in s.templates) {
                    val bmp = WidgetArt.button(c.sprites, c.typeface, "${t.label}\n${formatVndShort(t.amount)}", px(chipW.value), px(rowH.value), primary = false)
                    Image(
                        ImageProvider(bmp), contentDescription = t.label,
                        contentScale = ContentScale.FillBounds,
                        modifier = GlanceModifier.size(chipW, rowH).clickable(
                            actionRunCallback<QuickLogAction>(
                                actionParametersOf(
                                    QuickLogAction.AMOUNT to t.amount,
                                    QuickLogAction.CATEGORY to t.categoryId,
                                    QuickLogAction.LABEL to t.label,
                                ),
                            ),
                        ),
                    )
                }
                Box {
                    val plus = WidgetArt.button(c.sprites, c.typeface, if (s.templates.isEmpty()) "+ Ghi chi tiêu" else "+", px(plusW.value), px(rowH.value), primary = true)
                    Image(
                        ImageProvider(plus), contentDescription = "Ghi chi tiêu",
                        contentScale = ContentScale.FillBounds,
                        modifier = GlanceModifier.size(plusW, rowH).clickable(actionStartActivity(entryIntent(context))),
                    )
                }
            }
        }
    }
}

/** One tap on a quick expense in the wide widget: logged without opening the app. */
class QuickLogAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val c = context.container
        val amount = parameters[AMOUNT] ?: return
        val categoryId = parameters[CATEGORY] ?: return
        val label = parameters[LABEL] ?: "Chi"
        val cat = c.money.categoriesNow().firstOrNull { it.id == categoryId } ?: return
        val wallet = c.money.defaultWalletId() ?: return
        val draft = TxnDraft(amount, wallet, categoryId = cat.id, jarId = cat.defaultJarId, date = LocalDate.now(), source = "WIDGET")
        val saver = ExpenseSaver(c)
        // No dialog on the home screen: borrow from spending jars only, never the savings jars.
        val plan = saver.prepare(draft, null, label)?.plan ?: BorrowPlan(emptyList(), 0)
        saver.save(draft, plan, null, label)
        updateWidgets(context)
    }

    companion object {
        val AMOUNT = ActionParameters.Key<Long>("amount")
        val CATEGORY = ActionParameters.Key<Long>("category")
        val LABEL = ActionParameters.Key<String>("label")
    }
}

class MeowneySmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MeowneySmallWidget()
}

class MeowneyWideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MeowneyWideWidget()
}

suspend fun updateWidgets(context: Context) {
    MeowneySmallWidget().updateAll(context)
    MeowneyWideWidget().updateAll(context)
}
