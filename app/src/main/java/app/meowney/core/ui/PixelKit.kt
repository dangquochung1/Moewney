package app.meowney.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The names keep their "Pixel" prefix from the wood-theme days so screens did not have to
// change; everything here is now drawn in code like a regular app.

/** Corner radius of cards. */
val CardRadius = 14.dp

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

// ------------------------------------------------------------------ cards

/** How a card is filled. */
enum class CardTone(val fill: Color) {
    Plain(Pal.Card),
    Faded(Color(0xFFF7F1E2)),
    Sky(Pal.MorningSky),
    Lemon(Pal.LemonCream),

    /** Chosen (a picked cat, a picked option). */
    Selected(Pal.GoldenDune),
}

/** A white card: rounded corners, a thin pale border, no shadow. */
@Composable
fun PixelPanel(
    modifier: Modifier = Modifier,
    tone: CardTone = CardTone.Plain,
    extraPadding: Int = 2,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val m = LocalPixelMetrics.current
    val shape = RoundedCornerShape(CardRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(tone.fill)
            .border(1.dp, Pal.Line, shape)
            .padding(horizontal = 10.dp + m.unit * extraPadding, vertical = 8.dp + m.unit * extraPadding / 2),
        contentAlignment = contentAlignment,
        content = content,
    )
}

// ------------------------------------------------------------------ buttons

enum class ButtonLook(val fill: Color, val border: Color?) {
    /** The main action: Coral Reef. */
    Orange(Pal.Primary, null),

    /** Everything else: white with a thin border. */
    Light(Pal.Card, Pal.Line),

    /** A chosen chip or tab: Golden Dune. */
    Tab(Pal.Selected, null),
}

@Composable
fun PixelButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    look: ButtonLook = ButtonLook.Orange,
    padded: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 38.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(look.fill)
            .let { if (look.border != null) it.border(1.dp, look.border, shape) else it }
            .drawBehind { if (pressed && enabled) drawRect(Color.Black.copy(alpha = 0.08f)) }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = if (padded) 14.dp else 0.dp,
                vertical = if (padded) 6.dp else 0.dp,
            ),
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
            color = Pal.Ink,
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
    Row(
        modifier = modifier.clickable(role = Role.Checkbox) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickBox(checked, 22.dp)
        if (label != null) PixelText(label, Modifier.padding(start = 10.dp))
    }
}

/** A rounded tick box: white with a border, or Ocean Mist with a tick. */
@Composable
fun TickBox(checked: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size / 4)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (checked) Pal.Mint else Pal.Card)
            .border(1.5.dp, if (checked) Pal.Green else Pal.InkSoft.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Pal.Ink, modifier = Modifier.size(size * 0.75f))
    }
}

// ------------------------------------------------------------------ progress

/** A rounded bar: pale track, coloured fill. [heightPx] is in UI art pixels as before. */
@Composable
fun PixelProgress(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Pal.Mint,
    heightPx: Int = 5,
) {
    val m = LocalPixelMetrics.current
    Canvas(modifier.fillMaxWidth().height(maxOf(6.dp, m.dp(heightPx * m.ui) * 0.8f))) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(Pal.Track, cornerRadius = r)
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) drawRoundRect(color, size = Size(maxOf(size.height, size.width * f), size.height), cornerRadius = r)
    }
}

// ------------------------------------------------------------------ speech bubble

/** What the cat says: a white bubble with a little tail pointing down at it. */
@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier) {
    val tail = 8.dp
    Box(
        modifier
            .drawBehind {
                val t = tail.toPx()
                val body = Size(size.width, size.height - t)
                val r = CornerRadius(14.dp.toPx())
                drawRoundRect(Pal.Card, size = body, cornerRadius = r)
                drawRoundRect(Pal.Line, size = body, cornerRadius = r, style = Stroke(1.dp.toPx()))
                val cx = size.width / 2
                val path = Path().apply {
                    moveTo(cx - t, body.height - 1f)
                    lineTo(cx, size.height)
                    lineTo(cx + t, body.height - 1f)
                    close()
                }
                drawPath(path, Pal.Card)
                drawLine(Pal.Line, Offset(cx - t, body.height - 0.5f), Offset(cx, size.height), 1.dp.toPx())
                drawLine(Pal.Line, Offset(cx, size.height), Offset(cx + t, body.height - 0.5f), 1.dp.toPx())
            }
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + tail),
    ) {
        PixelText(text, style = LocalPixelType.current.body, color = Pal.Ink)
    }
}
