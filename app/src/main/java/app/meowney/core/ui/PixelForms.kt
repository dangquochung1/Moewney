package app.meowney.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.meowney.core.money.AmountKey

/** The 16 px Raven icon sheet (assets/icons/raven16.png), 16 cells per row. */
val LocalIcons = staticCompositionLocalOf<ImageBitmap> { error("Icons not loaded") }

/** One Raven icon cell, drawn at [scale] screen pixels per art pixel (default: UI scale). */
@Composable
fun RavenIcon(index: Int, modifier: Modifier = Modifier, scale: Int = LocalPixelMetrics.current.ui) {
    val sheet = LocalIcons.current
    val m = LocalPixelMetrics.current
    Canvas(modifier.size(m.dp(16 * scale))) {
        drawImage(
            sheet,
            srcOffset = IntOffset((index % 16) * 16, (index / 16) * 16),
            srcSize = IntSize(16, 16),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.None,
        )
    }
}

/** A pixel icon sitting in a soft tinted tile, the way list rows show what they are about. */
@Composable
fun IconChip(index: Int, modifier: Modifier = Modifier, tint: Color = Pal.GreenTint) {
    val m = LocalPixelMetrics.current
    val scale = (m.ui - 2).coerceAtLeast(2)
    Box(
        modifier.size(m.dp(16 * scale) + m.unit * 6).background(tint, Soft.Field),
        contentAlignment = Alignment.Center,
    ) {
        RavenIcon(index, scale = scale)
    }
}

/** The round "back" button at the top left of a page. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại", onClick, modifier)
}

/**
 * Full-screen page: cream background (or [backdrop], drawn behind everything), round back
 * button + title row, then [content].
 */
@Composable
fun PixelScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    titleColor: Color = Pal.Ink,
    backdrop: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = LocalPixelMetrics.current
    Box(modifier.fillMaxSize().background(Pal.Canvas)) {
        backdrop?.invoke()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = m.unit * 6),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = m.unit * 4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(m.unit * 3),
            ) {
                if (onBack != null) BackButton(onBack)
                PixelText(title, style = LocalPixelType.current.title, color = titleColor, modifier = Modifier.weight(1f), maxLines = 1)
                actions()
            }
            content()
        }
    }
}

/** Small toggle-able pill. */
@Composable
fun PixelChip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val m = LocalPixelMetrics.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = if (selected) (if (pressed) Pal.GoldPressed else Pal.Gold) else (if (pressed) Pal.Well else Pal.Card)
    Box(
        modifier
            .background(fill, Soft.Pill)
            .then(if (selected) Modifier else Modifier.border(1.dp, Pal.Line, Soft.Pill))
            .clip(Soft.Pill)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = m.unit * 6, vertical = m.unit * 3),
        contentAlignment = Alignment.Center,
    ) {
        val type = LocalPixelType.current
        PixelText(text, style = if (selected) type.caption.copy(fontWeight = type.bodyBold.fontWeight) else type.caption, color = if (selected) Pal.OnPrimary else Pal.Ink, maxLines = 1)
    }
}

/** A row of options in one rounded well; the chosen one is gold. For Chi / Thu / Chuyển and tabs. */
@Composable
fun SegmentedTabs(options: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val m = LocalPixelMetrics.current
    val type = LocalPixelType.current
    Row(modifier.background(Pal.Well, Soft.Pill).padding(m.unit * 2)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .then(if (on) Modifier.background(Pal.Gold, Soft.Pill) else Modifier)
                    .clip(Soft.Pill)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(i) }
                    .padding(vertical = m.unit * 4),
                contentAlignment = Alignment.Center,
            ) {
                PixelText(label, style = if (on) type.bodyBold else type.body, color = if (on) Pal.OnPrimary else Pal.InkSoft, maxLines = 1)
            }
        }
    }
}

/** [SegmentedTabs] over any list of items, for tabs that are an enum rather than an index. */
@Composable
fun <T> PixelTabs(items: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SegmentedTabs(items.map(label), items.indexOf(selected), modifier.fillMaxWidth()) { onSelect(items[it]) }
}

/** The amount keypad: digits, 000, k (×1000), tr (×1.000.000), back, clear. */
@Composable
fun PixelKeypad(onKey: (AmountKey) -> Unit, modifier: Modifier = Modifier) {
    val m = LocalPixelMetrics.current
    val rows = listOf(
        listOf("1", "2", "3", "<"),
        listOf("4", "5", "6", "k"),
        listOf("7", "8", "9", "tr"),
        listOf("000", "0", "C", ""),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.unit * 2)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit * 2)) {
                for (label in row) {
                    if (label.isEmpty()) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val key = when (label) {
                        "<" -> AmountKey.Back
                        "k" -> AmountKey.Thousand
                        "tr" -> AmountKey.Million
                        "000" -> AmountKey.Triple0
                        "C" -> AmountKey.Clear
                        else -> AmountKey.Digit(label.toInt())
                    }
                    val unit = label in listOf("k", "tr")
                    val source = remember { MutableInteractionSource() }
                    val pressed by source.collectIsPressedAsState()
                    val fill = if (pressed) Pal.Well else if (unit) Pal.GoldTint else Pal.Card
                    Box(
                        Modifier
                            .weight(1f)
                            .height(m.unit * 22)
                            .softSurface(fill, Soft.Field, elevation = 1.dp)
                            .clip(Soft.Field)
                            .clickable(interactionSource = source, indication = null, role = Role.Button) { onKey(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (label == "<") {
                            BackspaceGlyph(Modifier.size(m.unit * 12))
                        } else {
                            PixelText(label, style = LocalPixelType.current.title, color = if (unit) Pal.GoldDeep else Pal.Ink, align = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** The "delete one digit" key, so "<" no longer means three different things. */
@Composable
private fun BackspaceGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w / 11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(w * 0.36f, h * 0.22f)
            lineTo(w * 0.92f, h * 0.22f)
            lineTo(w * 0.92f, h * 0.78f)
            lineTo(w * 0.36f, h * 0.78f)
            lineTo(w * 0.08f, h * 0.5f)
            close()
        }
        drawPath(body, Pal.Ink, style = stroke)
        drawLine(Pal.Ink, Offset(w * 0.50f, h * 0.38f), Offset(w * 0.74f, h * 0.62f), stroke.width, StrokeCap.Round)
        drawLine(Pal.Ink, Offset(w * 0.74f, h * 0.38f), Offset(w * 0.50f, h * 0.62f), stroke.width, StrokeCap.Round)
    }
}

/** Single-line text input in a soft well. */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    numeric: Boolean = false,
) {
    val type = LocalPixelType.current
    val m = LocalPixelMetrics.current
    Box(modifier.background(Pal.Well, Soft.Field).padding(horizontal = m.unit * 6, vertical = m.unit * 5)) {
        if (value.isEmpty()) PixelText(placeholder, style = type.body, color = Pal.InkFaint)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = type.body,
            cursorBrush = SolidColor(Pal.Ink),
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Modal card. */
@Composable
fun PixelDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val m = LocalPixelMetrics.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .background(Pal.Canvas, RoundedCornerShape(24.dp))
                .padding(m.unit * 10),
            verticalArrangement = Arrangement.spacedBy(m.unit * 4),
            content = content,
        )
    }
}
