package app.meowney.feature.entry

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meowney.AppContainer
import app.meowney.core.CatEvent
import app.meowney.core.data.TxnDraft
import app.meowney.core.format.formatVnd
import app.meowney.core.format.formatVndShort
import app.meowney.core.money.JarCode
import app.meowney.core.money.Loan
import app.meowney.core.money.MoneyEngine
import app.meowney.core.sound.Sfx
import app.meowney.core.ui.ButtonLook
import app.meowney.core.ui.LocalPixelMetrics
import app.meowney.core.ui.LocalPixelType
import app.meowney.core.ui.Pal
import app.meowney.core.ui.PixelPanel
import app.meowney.core.ui.PixelScreen
import app.meowney.core.ui.PixelText
import app.meowney.core.ui.PixelTextButton
import app.meowney.feature.room.ToyArt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One jar as the pour editor shows it. */
data class PourJar(val code: JarCode, val name: String)

/** Colour of each jar in the 6-jar order; "Chưa phân bổ" is grey. */
fun jarColor(code: JarCode) = when (code) {
    JarCode.UNALLOC -> androidx.compose.ui.graphics.Color(0xFFB0A89A)
    else -> Pal.Jars[code.ordinal]
}

/** Step for the −/+ buttons: 1% of the amount, at least 1.000đ, rounded to 1.000đ. */
fun pourStep(amount: Long) = maxOf(1_000L, amount / 100 / 1_000 * 1_000)

/**
 * Splits [amount] between jars: a row of pixel jars filling up, then −/+ per jar. Whatever is
 * not given to a jar sits in "Chưa phân bổ", so the shares always add up to [amount].
 */
@Composable
fun PourEditor(
    amount: Long,
    jars: List<PourJar>,
    shares: Map<JarCode, Long>,
    onChange: (Map<JarCode, Long>) -> Unit,
    typeface: Typeface,
) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    val step = pourStep(amount)
    val unalloc = amount - jars.filter { it.code != JarCode.UNALLOC }.sumOf { shares[it.code] ?: 0 }
    val scale = (m.ui - 2).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
        for (j in jars.filter { it.code != JarCode.UNALLOC }) {
            val share = shares[j.code] ?: 0
            val level = if (amount > 0) (share.toFloat() / amount * 1.6f).coerceIn(0f, 1f) else 0f
            val q = (level * 16).toInt() / 16f
            val image = remember(j.code, q, typeface) { ToyArt.jarImage(q, typeface, jarColor(j.code), label = "") }
            Canvas(Modifier.size(m.dp(image.width * scale), m.dp(image.height * scale))) {
                drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(m.unit)) {
        for (j in jars.filter { it.code != JarCode.UNALLOC }) {
            val share = shares[j.code] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                PixelText(j.name, style = type.body, color = Pal.Cream, modifier = Modifier.weight(1f), maxLines = 1)
                PixelText(formatVndShort(share), style = type.bodyBold, color = jarColor(j.code))
                PixelTextButton("-", onClick = {
                    onChange(shares + (j.code to (share - step).coerceAtLeast(0)))
                }, look = ButtonLook.Light, enabled = share > 0)
                PixelTextButton("+", onClick = {
                    onChange(shares + (j.code to share + minOf(step, unalloc)))
                }, look = ButtonLook.Light, enabled = unalloc > 0)
            }
        }
        Row {
            PixelText("Chưa phân bổ", style = type.body, color = Pal.Paper, modifier = Modifier.weight(1f))
            PixelText(formatVnd(unalloc), style = type.bodyBold, color = Pal.Paper)
        }
    }
}

// ------------------------------------------------------------------ screen

data class PourState(
    val loaded: Boolean = false,
    val jars: List<PourJar> = emptyList(),
    val shares: Map<JarCode, Long> = emptyMap(),
    val loans: List<Loan> = emptyList(),
    val jarNames: Map<Long, String> = emptyMap(),
)

class PourViewModel(private val c: AppContainer, val draft: TxnDraft, private val editing: Long?) : ViewModel() {
    private val _state = MutableStateFlow(PourState())
    val state: StateFlow<PourState> = _state
    val done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var codeToId: Map<JarCode, Long> = emptyMap()

    init {
        viewModelScope.launch {
            val jars = c.money.jarsNow()
            codeToId = jars.associate { JarCode.valueOf(it.code) to it.id }
            val infos = c.money.jarInfos()
            val split = MoneyEngine.split(draft.amount, infos)
            val idToCode = codeToId.entries.associate { it.value to it.key }
            _state.value = PourState(
                loaded = true,
                jars = jars.map { PourJar(JarCode.valueOf(it.code), it.name) },
                shares = split.mapKeys { idToCode.getValue(it.key) },
                // when editing, this income's own repayments do not count as already done
                loans = c.money.loansNow(excludingTxn = editing),
                jarNames = jars.associate { it.id to it.name },
            )
        }
    }

    fun change(shares: Map<JarCode, Long>) = _state.update { it.copy(shares = shares) }

    fun confirm() = viewModelScope.launch {
        val s = _state.value
        val given = s.shares.filterKeys { it != JarCode.UNALLOC }
        val all = given + (JarCode.UNALLOC to draft.amount - given.values.sum())
        val byId = all.mapKeys { codeToId.getValue(it.key) }
        val entries = MoneyEngine.allocateIncome(draft.amount, c.money.jarInfos(), s.loans, byId)
        val result = c.money.addIncome(draft, entries, editing)
        c.sfx.play(Sfx.Sound.Gold)
        if (editing == null) c.events.post(CatEvent.Poured(draft.amount, result.coins, result.notes))
        done.emit(Unit)
    }
}

@Composable
fun PourScreen(vm: PourViewModel, typeface: Typeface, onBack: () -> Unit, onDone: () -> Unit) {
    val s by vm.state.collectAsState()
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    LaunchedEffect(vm) { vm.done.collect { onDone() } }
    PixelScreen("Rót xu vào lọ", onBack = onBack) {
        if (!s.loaded) return@PixelScreen
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(m.unit * 3)) {
            PixelPanel(Modifier.fillMaxWidth(), sprite = "ui/wood/nine_path_panel_3") {
                Column {
                    PixelText("Khoản thu", style = type.caption, color = Pal.InkSoft)
                    PixelText("+" + formatVnd(vm.draft.amount), style = type.display, color = Pal.Green)
                }
            }
            if (s.loans.isNotEmpty()) {
                PixelText("Trả nợ giữa các lọ trước:", style = type.bodyBold, color = Pal.Gold)
                for (l in s.loans) {
                    PixelText(
                        "• ${s.jarNames[l.borrowerJarId]} trả ${s.jarNames[l.lenderJarId]} tới ${formatVndShort(l.amount)}",
                        style = type.caption, color = Pal.Cream,
                    )
                }
            }
            PourEditor(vm.draft.amount, s.jars, s.shares, vm::change, typeface)
        }
        PixelTextButton("Rót vào lọ", onClick = { vm.confirm() }, modifier = Modifier.fillMaxWidth().padding(vertical = m.unit * 2))
    }
}
