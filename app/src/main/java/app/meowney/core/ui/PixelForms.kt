package app.meowney.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
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

/** The round "back" button at the top left of a page. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Pal.Card)
            .border(1.dp, Pal.Line, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = Pal.Ink, modifier = Modifier.size(20.dp))
    }
}

/**
 * Full-screen page: Lemon Cream background (or [backdrop], drawn behind everything), a title
 * row with the back button when there is somewhere to go back to, then [content].
 */
@Composable
fun PixelScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    backdrop: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(Pal.Canvas)) {
        backdrop?.invoke()
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (onBack != null) BackButton(onBack)
                PixelText(title, style = LocalPixelType.current.title, color = Pal.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                actions()
            }
            content()
        }
    }
}

/** A small pill: Golden Dune when chosen, white with a border otherwise. */
@Composable
fun PixelChip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PixelButton(
        onClick = onClick,
        modifier = modifier,
        look = if (selected) ButtonLook.Tab else ButtonLook.Light,
        shape = RoundedCornerShape(50),
    ) {
        PixelText(text, style = LocalPixelType.current.caption, color = Pal.Ink, maxLines = 1)
    }
}

/** Tabs of equal width in one row (the chosen one Golden Dune). */
@Composable
fun <T> PixelTabs(items: List<T>, selected: T, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Pal.Card)
            .border(1.dp, Pal.Line, shape)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (item in items) {
            val on = item == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) Pal.Selected else Pal.Card)
                    .clickable(role = Role.Tab) { onSelect(item) },
                contentAlignment = Alignment.Center,
            ) {
                PixelText(label(item), style = LocalPixelType.current.bodyBold, color = if (on) Pal.Ink else Pal.InkSoft, maxLines = 1)
            }
        }
    }
}

/** The amount keypad: digits, 000, k (×1000), tr (×1.000.000), back, clear. */
@Composable
fun PixelKeypad(onKey: (AmountKey) -> Unit, modifier: Modifier = Modifier) {
    val rows = listOf(
        listOf("1", "2", "3", "<"),
        listOf("4", "5", "6", "k"),
        listOf("7", "8", "9", "tr"),
        listOf("000", "0", "C", ""),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    PixelButton(
                        onClick = { onKey(key) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        look = if (label in listOf("k", "tr")) ButtonLook.Orange else ButtonLook.Light,
                        padded = false,
                    ) {
                        PixelText(label, style = LocalPixelType.current.title, color = Pal.Ink, align = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Single-line text input: white, rounded, thin border. */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    numeric: Boolean = false,
) {
    val type = LocalPixelType.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .clip(shape)
            .background(Pal.Card)
            .border(1.dp, Pal.Line, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) PixelText(placeholder, style = type.body, color = Pal.InkSoft)
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

/** A dialog on a white rounded sheet. */
@Composable
fun PixelDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .clip(RoundedCornerShape(20.dp))
                .background(Pal.Card)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
