package app.meowney.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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

/** Full-screen page: cream background, back button + title row, then [content]. */
@Composable
fun PixelScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = LocalPixelMetrics.current
    Column(
        modifier
            .fillMaxSize()
            .background(Pal.Canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = m.unit * 2),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = m.unit * 2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(m.unit * 2),
        ) {
            if (onBack != null) PixelTextButton("<", onClick = onBack, look = ButtonLook.Light)
            PixelText(title, style = LocalPixelType.current.title, color = Pal.Ink, modifier = Modifier.weight(1f), maxLines = 1)
            actions()
        }
        content()
    }
}

/** Small toggle-able pill. */
@Composable
fun PixelChip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    PixelButton(onClick = onClick, modifier = modifier, look = if (selected) ButtonLook.Orange else ButtonLook.Light) {
        PixelText(text, style = LocalPixelType.current.caption, color = if (selected) Pal.Cream else Pal.Ink, maxLines = 1)
    }
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(m.unit)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(m.unit)) {
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
                        modifier = Modifier.weight(1f).height(m.unit * 20),
                        look = if (label in listOf("k", "tr")) ButtonLook.Orange else ButtonLook.Light,
                        padded = false,
                    ) {
                        PixelText(
                            label,
                            style = LocalPixelType.current.title,
                            color = if (label in listOf("k", "tr")) Pal.Cream else Pal.Ink,
                            align = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Single-line text input on a white wood panel. */
@Composable
fun PixelTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    numeric: Boolean = false,
) {
    val type = LocalPixelType.current
    PixelPanel(modifier, sprite = "ui/wood/nine_path_panel_3", extraPadding = 1) {
        Box {
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
}

/** Modal wood panel. */
@Composable
fun PixelDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val m = LocalPixelMetrics.current
    Dialog(onDismissRequest = onDismiss) {
        PixelPanel(Modifier.fillMaxWidth().imePadding(), sprite = "ui/wood/nine_path_panel_3") {
            Column(verticalArrangement = Arrangement.spacedBy(m.unit * 2), content = content)
        }
    }
}
