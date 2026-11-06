package app.meowney.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.meowney.core.sprite.drawNinePatch

// ------------------------------------------------------------------ text

@Composable
fun PixelText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalPixelType.current.body,
    color: Color = Color.Unspecified,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = if (color == Color.Unspecified) style.color else color,
            textAlign = align,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

// ------------------------------------------------------------------ panels

/** A box whose background is a nine-patch from the wood theme. Padding = the art's border. */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    sprite: String = "ui/wood/nine_path_panel",
    extraPadding: Int = 2,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val art = remember(sprite) { atlas[sprite] }
    val inset = (art.entry.nine?.maxOrNull() ?: 0) + extraPadding
    Box(
        modifier = modifier
            .drawBehind {
                drawNinePatch(art, IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), m.ui)
            }
            .padding(m.dp(inset * m.ui)),
        contentAlignment = contentAlignment,
        content = content,
    )
}

// ------------------------------------------------------------------ buttons

enum class ButtonLook(val normal: String, val pressed: String, val disabled: String) {
    Orange("ui/wood/button_normal", "ui/wood/button_pressed", "ui/wood/button_disabled"),
    Light("ui/wood/button_hover", "ui/wood/button_pressed", "ui/wood/button_disabled"),
    Tab("ui/wood/tab_box", "ui/wood/tab_box_selected", "ui/wood/tab_box"),
}

@Composable
fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    look: ButtonLook = ButtonLook.Orange,
    padded: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val art = atlas[
        when {
            !enabled -> look.disabled
            pressed -> look.pressed
            else -> look.normal
        },
    ]
    // Pressed buttons sink one art pixel, like the wood theme in the old game.
    val sink = if (pressed && enabled) m.dp(m.ui) else m.dp(0)
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = m.dp(m.ui * 12))
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .drawBehind {
                drawNinePatch(art, IntOffset.Zero, IntSize(size.width.toInt(), size.height.toInt()), m.ui)
            }
            .padding(
                horizontal = if (padded) m.dp(m.ui * 5) else m.dp(0),
                vertical = if (padded) m.dp(m.ui * 2) else m.dp(0),
            )
            .offset(y = sink),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        content = content,
    )
}

@Composable
fun PixelTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    look: ButtonLook = ButtonLook.Orange,
) {
    PixelButton(onClick, modifier, enabled, look) {
        PixelText(
            text,
            style = LocalPixelType.current.bodyBold,
            color = if (look == ButtonLook.Orange) Pal.Cream else Pal.Ink,
            align = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ checkbox

@Composable
fun PixelCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val atlas = LocalSprites.current
    val m = LocalPixelMetrics.current
    val art = atlas[if (checked) "ui/wood/checked" else "ui/wood/unchecked"]
    Row(
        modifier = modifier.clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(m.dp(art.width * m.ui), m.dp(art.height * m.ui))) {
            drawImage(
                art.page, art.srcOffset(0), art.srcSize,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.None,
            )
        }
        if (label != null) {
            PixelText(label, Modifier.padding(start = m.dp(m.ui * 3)))
        }
    }
}

// ------------------------------------------------------------------ progress

/** A flat pixel bar: dark trough, coloured fill, 1-art-pixel highlight on top. */
@Composable
fun PixelProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Pal.Green,
    heightPx: Int = 5,
) {
    val m = LocalPixelMetrics.current
    Canvas(modifier.fillMaxWidth().height(m.dp(heightPx * m.ui))) {
        val u = m.ui.toFloat()
        drawRect(Pal.WoodDark)
        drawRect(Pal.Night, Offset(u, u), Size(size.width - 2 * u, size.height - 2 * u))
        val inner = size.width - 2 * u
        // snap the fill to whole art pixels
        val fillPx = (inner * fraction.coerceIn(0f, 1f) / u).toInt() * u
        if (fillPx > 0) {
            drawRect(color, Offset(u, u), Size(fillPx, size.height - 2 * u))
            drawRect(Color.White.copy(alpha = 0.35f), Offset(u, u), Size(fillPx, u))
        }
    }
}

// ------------------------------------------------------------------ speech bubble

@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    PixelPanel(modifier = modifier, sprite = "ui/wood/nine_path_panel_interior", extraPadding = 1) {
        PixelText(text, style = LocalPixelType.current.body, color = Pal.Cream)
    }
}
